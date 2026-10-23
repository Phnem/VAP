// Adapted from MIT Vetro's vetro_whisper.cpp. See licenses/whisper-NOTICE.txt.
#include <jni.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <string>
#include <vector>
#include "whisper.h"

namespace {
struct Context {
    whisper_context *value = nullptr;
    std::atomic<bool> cancelled{false};
    ~Context() { if (value) whisper_free(value); }
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Context>> registry;
jlong next_id = 1;
std::shared_ptr<Context> lookup(jlong id) {
    std::lock_guard<std::mutex> guard(registry_mutex);
    auto it = registry.find(id);
    return it == registry.end() ? nullptr : it->second;
}
void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
struct Progress { JNIEnv *env; jobject sink; jmethodID method; };
void progress(whisper_context *, whisper_state *, int percent, void *data) {
    auto *p = static_cast<Progress *>(data);
    p->env->CallVoidMethod(p->sink, p->method, percent);
    if (p->env->ExceptionCheck()) p->env->ExceptionClear();
}
bool abort_run(void *data) { return static_cast<Context *>(data)->cancelled.load(); }
// whisper returns ordinary UTF-8, not JNI modified UTF-8 (emoji need four bytes).
jstring utf8(JNIEnv *env, const std::string &text) {
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    auto type = env->FindClass("java/lang/String");
    auto encoding = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(type, env->GetMethodID(type, "<init>", "([BLjava/lang/String;)V"), bytes, encoding));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(type);
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_phnem_vap_ai_whisper_WhisperNative_create(JNIEnv *env, jobject, jstring path) {
    const char *filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return 0;
    auto params = whisper_context_default_params(); params.use_gpu = false;
    auto context = std::make_shared<Context>();
    context->value = whisper_init_from_file_with_params(filename, params);
    env->ReleaseStringUTFChars(path, filename);
    if (!context->value) { fail(env, "Cannot load Whisper model"); return 0; }
    std::lock_guard<std::mutex> guard(registry_mutex);
    auto id = next_id++; registry[id] = context; return id;
}
extern "C" JNIEXPORT void JNICALL
Java_com_phnem_vap_ai_whisper_WhisperNative_cancel(JNIEnv *, jobject, jlong id) {
    if (auto context = lookup(id)) context->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_phnem_vap_ai_whisper_WhisperNative_close(JNIEnv *, jobject, jlong id) {
    std::shared_ptr<Context> closing;
    { std::lock_guard<std::mutex> guard(registry_mutex); auto found = registry.find(id); if (found != registry.end()) { closing = std::move(found->second); registry.erase(found); } }
    // Free outside the mutex; a concurrent decode retains its own shared_ptr until completion.
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_phnem_vap_ai_whisper_WhisperNative_transcribe(JNIEnv *env, jobject, jlong id, jfloatArray pcm, jstring language, jint threads, jboolean words, jobject sink) {
    auto context = lookup(id);
    if (!context) { fail(env, "Closed Whisper context"); return nullptr; }
    auto size = env->GetArrayLength(pcm);
    if (size <= 0 || size > 16000 * 61) { fail(env, "Whisper audio must be between 0 and 61 seconds"); return nullptr; }
    std::vector<float> samples(static_cast<size_t>(size));
    env->GetFloatArrayRegion(pcm, 0, size, samples.data());
    const char *code = env->GetStringUTFChars(language, nullptr);
    if (!code) return nullptr;
    std::string lang(code); env->ReleaseStringUTFChars(language, code);
    if (lang != "auto" && whisper_lang_id(lang.c_str()) < 0) { fail(env, "Unsupported Whisper language"); return nullptr; }
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads < 1 ? 1 : (threads > 6 ? 6 : threads);
    params.language = lang.c_str(); params.translate = false; params.no_context = true;
    params.print_progress = params.print_realtime = params.print_timestamps = params.print_special = false;
    params.token_timestamps = words; params.max_len = words ? 1 : 0; params.split_on_word = words;
    auto sink_class = env->GetObjectClass(sink);
    Progress bridge{env, sink, env->GetMethodID(sink_class, "onProgress", "(I)V")};
    env->DeleteLocalRef(sink_class);
    if (env->ExceptionCheck()) return nullptr;
    params.progress_callback = progress; params.progress_callback_user_data = &bridge;
    params.abort_callback = abort_run; params.abort_callback_user_data = context.get();
    int status = whisper_full(context->value, params, samples.data(), size);
    if (context->cancelled.load()) { fail(env, "Whisper cancelled"); return nullptr; }
    if (status != 0) { fail(env, "Whisper inference failed"); return nullptr; }
    std::vector<std::string> lines;
    int detected = whisper_full_lang_id(context->value);
    lines.emplace_back(detected >= 0 ? whisper_lang_str(detected) : "");
    for (int i = 0; i < whisper_full_n_segments(context->value); ++i) {
        lines.emplace_back(std::to_string(whisper_full_get_segment_t0(context->value, i) * 10) + "\t" +
            std::to_string(whisper_full_get_segment_t1(context->value, i) * 10) + "\t" + whisper_full_get_segment_text(context->value, i));
    }
    auto array = env->NewObjectArray(static_cast<jsize>(lines.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < lines.size(); ++i) {
        auto text = utf8(env, lines[i]); env->SetObjectArrayElement(array, static_cast<jsize>(i), text); env->DeleteLocalRef(text);
    }
    return array;
}
