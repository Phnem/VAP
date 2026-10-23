# Native subtitle backend

VAP uses libass 0.17.5 (ISC), FreeType 2.15.0 (under the FreeType License), HarfBuzz 13.0.1 (licenses listed in COPYING), and dynamically linked FriBidi 1.0.17 (LGPL). Full notices are bundled in the APK under `assets/licenses`. VAP's own code remains MIT licensed. Each library retains its own license.

`tools/build-subtitle-native.py` builds the libraries from source for arm64-v8a, armeabi-v7a, x86_64, and x86. libass is pinned by a tag archive and SHA-256; other archive versions and hashes are recorded in `tools/native/wraps`. Gradle's `prepareSubtitleNative` task runs the script before CMake. Requirements: Python 3.12+, NDK 27.0.12077973, CMake 3.22.1, and a host C compiler for FriBidi's Unicode generators. Meson detects installed MSVC Build Tools on Windows; Linux needs a system compiler.

The script installs Meson 1.6.1 and Ninja 1.11.1.3 in an isolated virtual environment under `app/build`. The first build downloads sources; later builds reuse the local cache. Offline builds require all source archives to be prepared in advance, including Meson's package cache. ARM assembly, optional applications and tests, Fontconfig, DirectWrite, CoreText, libunibreak, and optional HarfBuzz subsetting/raster/vector libraries are disabled. FreeType renders fonts; HarfBuzz performs text shaping.

## FriBidi cross-build patch

In `gen.tab/meson.build`, the compiler used to check headers for the generators is selected with `meson.get_compiler('c', native: true)`. Without this change, an Android `strings.h` check incorrectly contributes to the MSVC configuration during Windows cross-builds. The build script records the exact source transformation.

## JNI and source distribution

VAP's JNI implementation is `app/src/main/cpp/ass_jni.c`. FriBidi remains a separate `libfribidi.so`; its code is not embedded in the JNI library. Source archives and scripts reproduce the modified sources. You can replace the dynamic libraries, rebuild the app, and sign the APK with your own key.

For release distribution, the source archives, build instructions, and scripts are published alongside the corresponding APK. The `VAP-v0.1-Stable-native-sources.zip` release asset contains those materials. F-Droid metadata is covered by development stage 10.

## Alignment and subtitle data

Each library built by the script uses `max-page-size=16384`. ELF and APK packaging alignment must be checked separately; the linker flag alone does not confirm behavior on a device with 16 KB pages. Original ASS files are copied into private storage without conversion. Normalized text is stored separately for search, editing, and export.

Upstream references: [libass COPYING](https://github.com/libass/libass/blob/0.17.5/COPYING), [libass build configuration](https://github.com/libass/libass/blob/0.17.5/meson.build), and [libass API](https://github.com/libass/libass/blob/0.17.5/libass/ass.h). FreeType, FriBidi, and HarfBuzz versions and checksums are pinned in the repository's wrap files.
