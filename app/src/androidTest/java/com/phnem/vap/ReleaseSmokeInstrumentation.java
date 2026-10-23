package com.phnem.vap;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import java.util.Arrays;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.lang.reflect.Proxy;

/** Uses only platform/Java classes: the target may have had even Kotlin runtime classes renamed by R8. */
public final class ReleaseSmokeInstrumentation extends Instrumentation {
    private String distribution = "foss";
    private boolean whisper;
    private boolean translation;
    @Override public void onCreate(Bundle arguments) {
        if (arguments != null) distribution = arguments.getString("distribution", "foss");
        if (arguments != null) whisper = "true".equals(arguments.getString("whisper"));
        if (arguments != null) translation = "true".equals(arguments.getString("translation"));
        super.onCreate(arguments); start();
    }
    private void verify(boolean condition) { if (!condition) throw new IllegalStateException("Distribution invariant failed"); }
    @Override public void onStart() {
        Throwable failure = null;
        try {
            Context target = getTargetContext();
            ClassLoader loader = target.getClassLoader();
            String providerName = "com.phnem.vap.cast.cast.VapCastOptionsProvider";
            Bundle meta = target.getPackageManager().getApplicationInfo(target.getPackageName(), PackageManager.GET_META_DATA).metaData;
            String metadata = meta == null ? null : meta.getString("com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME");
            boolean notice = Arrays.asList(target.getAssets().list("licenses")).contains("Google-Cast-NOTICE.txt");
            if ("cast".equals(distribution)) {
                verify(providerName.equals(metadata));
                Class<?> type = Class.forName(providerName, true, loader);
                Object provider = type.getDeclaredConstructor().newInstance();
                verify(type.getMethod("getCastOptions", Context.class).invoke(provider, target) != null);
                verify(notice);
            } else {
                verify(metadata == null); verify(!notice);
                boolean absent = false;
                try { Class.forName(providerName, false, loader); } catch (ClassNotFoundException expected) { absent = true; }
                verify(absent);
            }
            if (whisper) verifyWhisper(target, loader);
            if (translation) verifyTranslation(target, loader);
        } catch (Throwable error) { failure = error; }
        Bundle report = new Bundle();
        report.putString("stream", failure == null ? "PASS: minified " + distribution + " metadata, Cast provider reflection/options and distribution-specific notice" + (whisper ? "; actual Whisper JNI inference and UTF-8/progress ABI" : "") + (translation ? "; actual local EN-RU/RU-EN translation JNI" : "") + "\n" : "FAIL: " + android.util.Log.getStackTraceString(failure));
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
    }
    private void verifyWhisper(Context target, ClassLoader loader) throws Exception {
        Class<?> nativeType = Class.forName("com.phnem.vap.ai.whisper.WhisperNative", true, loader);
        Object instance = nativeType.getField("INSTANCE").get(null);
        Class<?> progressType = Class.forName("com.phnem.vap.ai.whisper.WhisperNative$Progress", true, loader);
        java.lang.reflect.Constructor<?> constructor = progressType.getConstructors()[0];
        Class<?> callbackType = constructor.getParameterTypes()[0];
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> null);
        Object progress = constructor.newInstance(callback);
        byte[] wav = Files.readAllBytes(new File(target.getFilesDir(), "whisper-fixtures/jfk.wav").toPath());
        ByteBuffer bytes = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 12, length = 0;
        while (offset + 8 <= wav.length) {
            int chunk = bytes.getInt(offset), size = bytes.getInt(offset + 4);
            verify(size >= 0 && (long) offset + 8 + size <= wav.length);
            if (chunk == 0x61746164) { length = size; offset += 8; break; }
            offset += 8 + size + (size & 1);
        }
        verify(length > 0);
        float[] samples = new float[length / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = bytes.getShort(offset + i * 2) / 32768f;
        long handle = (Long) nativeType.getMethod("create", String.class).invoke(instance, new File(target.getFilesDir(), "whisper-fixtures/ggml-tiny-q5_1.bin").getAbsolutePath());
        try {
            String[] result = (String[]) nativeType.getMethod("transcribe", long.class, float[].class, String.class, int.class, boolean.class, progressType)
                .invoke(instance, handle, samples, "auto", 4, false, progress);
            verify(result.length > 1 && "en".equals(result[0]) && String.join(" ", result).toLowerCase(java.util.Locale.ROOT).contains("country"));
        } finally { nativeType.getMethod("close", long.class).invoke(instance, handle); }
    }
    private void verifyTranslation(Context target, ClassLoader loader) throws Exception {
        Class<?> type = Class.forName("com.phnem.vap.ai.translation.TranslationNative", true, loader);
        Object instance = type.getField("INSTANCE").get(null);
        for (String pair : new String[]{"en-ru", "ru-en"}) {
            long handle = (Long) type.getMethod("create", String.class).invoke(instance, new File(target.getFilesDir(), "translation-models/" + pair).getAbsolutePath());
            try {
                String text = pair.equals("en-ru") ? "Hello, world!" : "Привет, мир!";
                byte[] output = (byte[]) type.getMethod("translate", long.class, byte[].class).invoke(instance, handle, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String result = new String(output, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
                verify(result.contains(pair.equals("en-ru") ? "мир" : "world"));
            } finally { type.getMethod("close", long.class).invoke(instance, handle); }
        }
    }
}
