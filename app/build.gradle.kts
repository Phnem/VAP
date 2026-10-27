import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("VapDatabase") {
            packageName.set("com.phnem.vap.data")
        }
    }
}

android {
    namespace = "com.phnem.vap"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.phnem.vap"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "v0.3-Stable"
        // Откуда встроенное обновление берёт релизы.
        buildConfigField("String", "GITHUB_OWNER", "\"Phnem\"")
        buildConfigField("String", "GITHUB_REPO", "\"VAP\"")
        testInstrumentationRunner = if (providers.gradleProperty("vapSmokeRunner").orNull == "true") "com.phnem.vap.ReleaseSmokeInstrumentation" else "com.phnem.vap.PlaybackDeviceTest"
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        externalNativeBuild { cmake {
            arguments += "-DANDROID_STL=c++_shared"
            targets += listOf("vap_ass", "vap_whisper", "vap_translation")
        } }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution"; isDefault = true }
        create("cast") { dimension = "distribution"; proguardFile("src/cast/proguard-rules.pro") }
    }
    sourceSets.getByName("foss").java.srcDir("src/nocast/java")

    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Release signing comes from an untracked keystore.properties (storeFile, storePassword,
    // keyAlias, keyPassword); without it release stays unsigned and Android refuses to install it.
    val signingProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword") ?: signingProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

val prepareSubtitleNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python", "tools/build-subtitle-native.py")
    inputs.file(rootProject.file("tools/build-subtitle-native.py"))
    inputs.dir(rootProject.file("tools/native/wraps"))
    outputs.dir(layout.buildDirectory.dir("native-install"))
    outputs.dir(layout.buildDirectory.dir("generated/subtitle-jni"))
}
val prepareWhisperNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python", "tools/prepare-whisper-source.py")
    inputs.file(rootProject.file("tools/prepare-whisper-source.py"))
    outputs.dir(layout.buildDirectory.dir("native-src/whisper.cpp-927cfce34f31707e17f2bff35c349632fb9e2c3a"))
}
val prepareTranslationNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python", "tools/prepare-translation-source.py")
    inputs.file(rootProject.file("tools/prepare-translation-source.py"))
    outputs.dir(layout.buildDirectory.dir("native-src/translation"))
}
tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(prepareSubtitleNative, prepareWhisperNative, prepareTranslationNative)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.effect)
    implementation(libs.media3.transformer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.okhttp)
    implementation(libs.okhttp)
    implementation(libs.smbj) { exclude(group = "org.bouncycastle", module = "bcprov-jdk15on") }
    "castImplementation"("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation(libs.sshj)
    implementation(libs.commons.net)
    implementation(libs.bouncycastle)

    implementation(libs.androidx.documentfile)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.graphics.path)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines)
    implementation(libs.backdrop)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    implementation(libs.work.runtime.ktx)
    testImplementation(libs.junit)
    // В JVM-тестах org.json из android.jar — заглушка; настоящий нужен разбору ответа GitHub.
    testImplementation("org.json:json:20240303")
    testImplementation(libs.sqldelight.sqlite.driver)
}
