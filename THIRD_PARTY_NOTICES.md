# Third-party notices

The root MIT License applies to VAP's own code. Third-party components retain their licenses and notices. Full license texts included in the APK are available in [app/src/main/assets/licenses](app/src/main/assets/licenses/).

| Component | License / notice |
| --- | --- |
| Components from Vetro Collection | MIT; original copyright and license texts are preserved in `*-NOTICE.txt` |
| AndroidX, Jetpack Compose, Media3 | Apache-2.0; licenses are also distributed in dependency metadata |
| SQLDelight | Apache-2.0 |
| libass 0.17.5 | ISC — `libass-ISC.txt` |
| FreeType 2.15.0 | FreeType License — `freetype-FTL.txt`, `freetype-LICENSE.txt` |
| HarfBuzz 13.0.1 | Applicable notices in `harfbuzz-COPYING.txt` |
| FriBidi 1.0.17 | LGPL-2.1-or-later, dynamically linked as a separate `libfribidi.so`; `fribidi-LGPL.txt`, `fribidi-AUTHORS.txt` |
| whisper.cpp / ggml | MIT — `whisper-NOTICE.txt` |
| CTranslate2, SentencePiece, and their dependencies | Licenses in `translation-NOTICE.txt` and the pinned source revisions |
| OkHttp | Apache-2.0 — `okhttp-Apache-2.0.txt` |
| SMBJ, SSHJ, Apache Commons Net | `smbj-NOTICE.txt`, `sshj-Apache-2.0.txt`, and `commons-net-*` |
| Bouncy Castle, SLF4J, MBassador, ASN.1 | Corresponding texts in `assets/licenses/` |
| Google Cast SDK — `cast` only | Separate Google terms; [notice in cast assets](app/src/cast/assets/licenses/Google-Cast-NOTICE.txt) |

The app uses Android's system font. Downloadable models are not bundled in the APK and have their own terms, recorded in the model catalogs and the [Whisper](docs/stage8-whisper.md) and [translation](docs/stage8-translation.md) reports.

## Native dependency sources

Release `v0.1-Stable` includes `VAP-v0.1-Stable-native-sources.zip` alongside the APKs. It contains pinned native dependency source archives, wrap files, and build scripts. App sources are available in Git at the release tag and through GitHub's standard Source code archives.

Local upstream changes are reproduced by `tools/build-subtitle-native.py` and `tools/prepare-translation-source.py`. JNI build settings are in `app/src/main/cpp/CMakeLists.txt`. See the [native build guide](docs/subtitle-native-build.md) and [APK build instructions](README.md#build-from-source). You can replace the dynamic libraries by rebuilding and signing the APK with your own key. VAP's private signing key is not distributed.
