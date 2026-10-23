#include <jni.h>
#include <android/bitmap.h>
#include <ass/ass.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    ASS_Library *library;
    ASS_Renderer *renderer;
    ASS_Track *track;
    int width, height;
} VapAss;

static void destroy(VapAss *state) {
    if (!state) return;
    if (state->track) ass_free_track(state->track);
    if (state->renderer) ass_renderer_done(state->renderer);
    if (state->library) ass_library_done(state->library);
    free(state);
}

JNIEXPORT jlong JNICALL Java_com_phnem_vap_player_subtitles_AssNative_create(
        JNIEnv *env, jobject self, jbyteArray document, jstring font, jobjectArray font_files) {
    jsize length = (*env)->GetArrayLength(env, document);
    if (length <= 0 || length > 5 * 1024 * 1024) return 0;
    VapAss *state = calloc(1, sizeof(*state));
    if (!state) return 0;
    state->library = ass_library_init();
    if (!state->library) { destroy(state); return 0; }
    ass_set_fonts_dir(state->library, "/system/fonts");
    ass_set_extract_fonts(state->library, 1);
    size_t font_total = 0;
    jsize count = (*env)->GetArrayLength(env, font_files);
    if (count > 16) count = 16;
    for (jsize index = 0; index < count; index++) {
        jstring name = (*env)->GetObjectArrayElement(env, font_files, index);
        const char *path = (*env)->GetStringUTFChars(env, name, NULL);
        if (!path) { destroy(state); return 0; }
        FILE *file = fopen(path, "rb");
        if (file) {
            if (fseek(file, 0, SEEK_END) == 0) {
                long size = ftell(file);
                if (size > 0 && size <= 8388608 && font_total + (size_t)size <= 33554432 && fseek(file, 0, SEEK_SET) == 0) {
                    char *data = malloc((size_t)size);
                    if (data) {
                        if (fread(data, 1, (size_t)size, file) == (size_t)size) {
                            ass_add_font(state->library, (char *)path, data, (int)size);
                            font_total += (size_t)size;
                        }
                        free(data);
                    }
                }
            }
            fclose(file);
        }
        (*env)->ReleaseStringUTFChars(env, name, path);
        (*env)->DeleteLocalRef(env, name);
    }
    state->renderer = ass_renderer_init(state->library);
    if (!state->renderer) { destroy(state); return 0; }
    ass_set_cache_limits(state->renderer, 2048, 32);
    const char *default_font = (*env)->GetStringUTFChars(env, font, NULL);
    if (!default_font) { destroy(state); return 0; }
    ass_set_fonts(state->renderer, default_font, "sans-serif", ASS_FONTPROVIDER_NONE, NULL, 1);
    (*env)->ReleaseStringUTFChars(env, font, default_font);
    char *bytes = malloc((size_t)length + 1);
    if (!bytes) { destroy(state); return 0; }
    (*env)->GetByteArrayRegion(env, document, 0, length, (jbyte *)bytes);
    if ((*env)->ExceptionCheck(env)) { free(bytes); destroy(state); return 0; }
    bytes[length] = 0;
    state->track = ass_read_memory(state->library, bytes, (size_t)length, "UTF-8");
    free(bytes);
    if (!state->track || state->track->n_events < 1 || state->track->n_events > 50000) { destroy(state); return 0; }
    return (jlong)(intptr_t)state;
}

JNIEXPORT jobject JNICALL Java_com_phnem_vap_player_subtitles_AssNative_render(
        JNIEnv *env, jobject self, jlong handle, jlong time, jint width, jint height) {
    VapAss *state = (VapAss *)(intptr_t)handle;
    if (!state || width < 1 || height < 1 || width > 4096 || height > 4096) return NULL;
    if (state->width != width || state->height != height) {
        ass_set_frame_size(state->renderer, width, height);
        state->width = width; state->height = height;
    }
    int changed = 0;
    ASS_Image *images = ass_render_frame(state->renderer, state->track, time, &changed);
    if (!changed) return NULL;
    int left = width, top = height, right = 0, bottom = 0;
    for (ASS_Image *i = images; i; i = i->next) {
        if (i->w <= 0 || i->h <= 0) continue;
        if (i->dst_x < left) left = i->dst_x;
        if (i->dst_y < top) top = i->dst_y;
        if (i->dst_x + i->w > right) right = i->dst_x + i->w;
        if (i->dst_y + i->h > bottom) bottom = i->dst_y + i->h;
    }
    if (left < 0) left = 0;
    if (top < 0) top = 0;
    if (right > width) right = width;
    if (bottom > height) bottom = height;
    jclass frame_class = (*env)->FindClass(env, "com/phnem/vap/player/subtitles/AssFrame");
    jmethodID frame_init = (*env)->GetMethodID(env, frame_class, "<init>", "(Landroid/graphics/Bitmap;II)V");
    if (right <= left || bottom <= top) return (*env)->NewObject(env, frame_class, frame_init, NULL, 0, 0);
    jclass bitmap_class = (*env)->FindClass(env, "android/graphics/Bitmap");
    jclass config_class = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
    jfieldID config_field = (*env)->GetStaticFieldID(env, config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = (*env)->GetStaticObjectField(env, config_class, config_field);
    jmethodID create = (*env)->GetStaticMethodID(env, bitmap_class, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = (*env)->CallStaticObjectMethod(env, bitmap_class, create, right-left, bottom-top, config);
    if (!bitmap || (*env)->ExceptionCheck(env)) return NULL;
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return NULL;
    memset(pixels, 0, (size_t)info.stride * info.height);
    for (ASS_Image *i = images; i; i = i->next) {
        unsigned r = i->color >> 24, g = (i->color >> 16) & 255, b = (i->color >> 8) & 255;
        unsigned alpha = 255 - (i->color & 255);
        for (int y = 0; y < i->h; y++) {
            int dy = i->dst_y + y;
            if (dy < top || dy >= bottom) continue;
            uint8_t *out = (uint8_t *)pixels + (size_t)(dy-top) * info.stride;
            const uint8_t *in = i->bitmap + (size_t)y * i->stride;
            for (int x = 0; x < i->w; x++) {
                int dx = i->dst_x + x;
                if (dx < left || dx >= right) continue;
                unsigned a = (in[x] * alpha + 127) / 255, inverse = 255 - a;
                uint8_t *p = out + (size_t)(dx-left) * 4;
                /* Android RGBA_8888 uses premultiplied RGBA byte components. */
                p[0] = (uint8_t)((r*a + p[0]*inverse + 127) / 255);
                p[1] = (uint8_t)((g*a + p[1]*inverse + 127) / 255);
                p[2] = (uint8_t)((b*a + p[2]*inverse + 127) / 255);
                p[3] = (uint8_t)(a + (p[3]*inverse + 127) / 255);
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return (*env)->NewObject(env, frame_class, frame_init, bitmap, left, top);
}

JNIEXPORT void JNICALL Java_com_phnem_vap_player_subtitles_AssNative_release(JNIEnv *env, jobject self, jlong handle) {
    destroy((VapAss *)(intptr_t)handle);
}
