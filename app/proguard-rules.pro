# R8/ProGuard: правила добавлять по мере появления рефлексии и нативных библиотек.
# Version-pinned Media3 playback tone-map bridge; tested with minified APK on device.
-keepclassmembers class androidx.media3.exoplayer.video.MediaCodecVideoRenderer {
    androidx.media3.exoplayer.video.VideoSink videoSink;
}
# JCA loads algorithm SPI implementations and their Mappings classes by names in Provider tables.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
# SMB is explicitly NTLM-only; Android has no optional desktop Kerberos/GSS API.
-dontwarn org.ietf.jgss.**
# MBassador's optional expression-language dispatch is unused: SMB listeners have no conditions.
-dontwarn javax.el.**
-keepclassmembers class * { @net.engio.mbassy.listener.Handler <methods>; }
