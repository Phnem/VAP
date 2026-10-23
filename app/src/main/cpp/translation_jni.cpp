#include <jni.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <string>
#include <vector>
#include "ctranslate2/translator.h"
#include "sentencepiece_processor.h"

namespace {
struct Context {
    std::unique_ptr<ctranslate2::Translator> translator;
    sentencepiece::SentencePieceProcessor source, target;
    std::atomic<bool> cancelled{false};
};
std::mutex mutex;
std::unordered_map<jlong, std::shared_ptr<Context>> registry;
jlong next_id = 1;
std::shared_ptr<Context> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(mutex);
    auto found = registry.find(id); return found == registry.end() ? nullptr : found->second;
}
void fail(JNIEnv *env, const char *message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_phnem_vap_ai_translation_TranslationNative_create(JNIEnv *env, jobject, jstring path) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::string folder(chars); env->ReleaseStringUTFChars(path, chars);
    try {
        auto context = std::make_shared<Context>();
        if (!context->source.Load(folder + "/source.spm").ok() || !context->target.Load(folder + "/target.spm").ok())
            throw std::runtime_error("Cannot load translation tokenizer");
        ctranslate2::models::ModelLoader loader(folder);
        loader.compute_type = ctranslate2::ComputeType::INT8;
        ctranslate2::ReplicaPoolConfig config; config.num_threads_per_replica = 2; config.max_queued_batches = 1;
        context->translator = std::make_unique<ctranslate2::Translator>(loader, config);
        std::lock_guard<std::mutex> lock(mutex); auto id = next_id++; registry[id] = context; return id;
    } catch (const std::exception &) { fail(env, "Cannot load local translation model"); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_phnem_vap_ai_translation_TranslationNative_cancel(JNIEnv *, jobject, jlong id) { if (auto context = lookup(id)) context->cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL
Java_com_phnem_vap_ai_translation_TranslationNative_close(JNIEnv *, jobject, jlong id) {
    std::shared_ptr<Context> closing;
    { std::lock_guard<std::mutex> lock(mutex); auto found = registry.find(id); if (found != registry.end()) { closing = std::move(found->second); registry.erase(found); } }
    // Destructors can join CT2 workers. Never hold the registry mutex while joining.
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_phnem_vap_ai_translation_TranslationNative_translate(JNIEnv *env, jobject, jlong id, jbyteArray text) {
    auto context = lookup(id);
    if (!context) { fail(env, "Closed translation context"); return nullptr; }
    auto size = env->GetArrayLength(text);
    if (size < 1 || size > 64 * 1024) { fail(env, "Translation input exceeds limit"); return nullptr; }
    try {
        std::string input(static_cast<size_t>(size), '\0'); env->GetByteArrayRegion(text, 0, size, reinterpret_cast<jbyte *>(input.data()));
        std::vector<std::string> tokens;
        if (!context->source.Encode(input, &tokens).ok() || tokens.size() > 510) throw std::runtime_error("Input token limit");
        tokens.emplace_back("</s>");
        ctranslate2::TranslationOptions options;
        options.beam_size = 1; options.max_input_length = 512; options.max_decoding_length = 512;
        // translate_batch owns the job until the future is ready. Retaining Context in that job
        // could destroy Translator on its own worker thread when the caller releases its handle.
        // This JNI frame retains Context while callbacks can execute; callback destruction needs no owner.
        auto *active = context.get();
        options.callback = [active](ctranslate2::GenerationStepResult) { return active->cancelled.load(); };
        auto result = context->translator->translate_batch({tokens}, options);
        if (context->cancelled.load()) throw std::runtime_error("Cancelled");
        if (result.empty() || result[0].output().size() >= 512) throw std::runtime_error("Output token limit");
        std::string output;
        if (!context->target.Decode(result[0].output(), &output).ok() || output.size() > 64 * 1024) throw std::runtime_error("Decode failed");
        auto bytes = env->NewByteArray(static_cast<jsize>(output.size()));
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
        return bytes;
    } catch (const std::exception &) { fail(env, "Local translation failed or was cancelled"); return nullptr; }
}
