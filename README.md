# VAP — Vetro Android Player

**The player from Vetro Collection, now a standalone Android app in the Vetro family.**

[![Release](https://img.shields.io/github/v/release/Phnem/VAP?label=release)](https://github.com/Phnem/VAP/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/Phnem/VAP/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

VAP (Vetro Android Player) brings the video player from Vetro Collection into its own app, with a dedicated video library, local and network playback, subtitle tools, and on-device AI. It shares the Vetro family's visual language: frosted glass panels, animations, and light and dark themes. The interface supports English and Russian.

## Download

The current release is **v0.2-Stable**. Download signed APKs from [GitHub Releases](https://github.com/Phnem/VAP/releases/latest).

| APK | Choose this for |
| --- | --- |
| **VAP-v0.2-Stable-foss.apk** | The main build without the Google Cast SDK. Includes local playback, network sources, DLNA, and AI tools. |
| **VAP-v0.2-Stable-cast.apk** | Everything in the main build, plus Google Cast. Includes the Google Cast SDK. |

Both APKs are universal builds for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`. Requires **Android 8.0 / API 26 or later**. Both variants use the package ID `com.phnem.vap` and replace each other when installed. Updating an installation signed with the same key preserves app data.

Download an APK, allow installation from your browser or file manager, and open the file. On first launch, choose a language and theme, then grant access to videos. You can add more folders through the system folder picker.

### Updates

APKs from GitHub Releases update themselves. VAP checks for a new release at launch and every 12 hours, downloads it over several parallel connections, verifies the file size, SHA-256 checksum, and signing key, and then opens the system installer. The first time, Android asks you to allow VAP to install apps. You can also check by hand in **Settings → Check for updates**. Builds from F-Droid or your own signing key do not update themselves and keep using their store.

## Features

- **Video library:** MediaStore scanning, additional Storage Access Framework (SAF) folders, directory trees, grid and list layouts with animated switching, flat colored folders that open and close, thumbnails, forgiving search (`911` finds `9-1-1`), sorting, filters, and pull to refresh.
- **Watch organization:** favorites, hidden folders, history, resume playback, playlists, and a playback queue.
- **Playback:** AndroidX Media3 / ExoPlayer, gesture controls, a playback speed ruler (0.5×–3.0×), zoom and aspect ratio settings, orientation chosen from the video, track selection, background playback, picture-in-picture, and an in-app floating mini player.
- **Updates:** built-in checks, fast resumable downloads, and signature verification for builds signed with the author's key.
- **Audio and video tools:** processing settings, external audio tracks, audio synchronization, screenshots, and clip export tools.
- **Subtitles:** embedded and external tracks, SRT/VTT, styled ASS/SSA through libass, two simultaneous tracks, timing offsets, text editing, and export.
- **Network sources:** HTTP(S), HLS, DASH, SMB, WebDAV, FTP/FTPS, and SFTP; Jellyfin, Emby, Plex, and browsing DLNA sources.
- **TV playback:** DLNA, with Google Cast available in the `cast` variant.
- **Whisper:** on-device subtitle generation after downloading a model, with chunked processing of finite videos, caching, and cancellation.
- **Translation:** local EN → RU and RU → EN translation through CTranslate2, plus optional cloud translation using a configurable compatible API and your own key.
- **Smart Sync:** estimates subtitle offsets and uniform timing drift from recognized speech.

## Limitations and requirements

Container, codec, HDR, audio format, and export support depend on Android, the device's decoders, and the source. Universal transcoding for TV receivers is not implemented. Compatibility with a particular DLNA or Cast receiver needs to be tested.

Whisper and local translation models are **downloaded separately from the app** and are not bundled in the APK. Once downloaded, local recognition and translation work offline. Streaming live subtitles are not implemented. Smart Sync expects subtitles in the language of the speech; heavily edited videos and inserted scenes may produce unreliable results. Cloud translation sends text to the service you configure.

OpenSubtitles search and cloud features require your own credentials. VAP does not provide movies, servers, or subscriptions to third-party services.

## Build from source

### Prerequisites

- JDK **21**.
- Android SDK Platform **36**, Build Tools **35.0.0** (the default for AGP 8.13), NDK **27.0.12077973**, and CMake **3.22.1**.
- Python **3.12+**, available as `python`, with `venv` and `pip`.
- A host C compiler for FriBidi's build-time generators: MSVC Build Tools on Windows or a system toolchain on Linux.
- Internet access for the initial download of Gradle, dependencies, and pinned native source archives.

Gradle **8.13** is provided through the wrapper. Set the SDK location in `local.properties` (`sdk.dir=...`). For Python scripts, also set `ANDROID_HOME` or `ANDROID_SDK_ROOT` if your SDK uses a non-default location. Install the specified NDK and CMake versions through Android Studio's SDK Manager.

```sh
git clone https://github.com/Phnem/VAP.git
cd VAP
./gradlew :app:assembleFossDebug
```

In PowerShell, use `.\gradlew.bat` instead of `./gradlew`. The debug APK is written to `app/build/outputs/apk/foss/debug/app-foss-debug.apk`. To build with Google Cast, use `:app:assembleCastDebug`.

The build automatically prepares libass, FreeType, HarfBuzz, FriBidi, whisper.cpp, CTranslate2, and SentencePiece. Source revisions and hashes are pinned in `tools/prepare-*-source.py` and `tools/native/wraps/`. See the [native subtitle build guide](docs/subtitle-native-build.md).

### Signed release

Copy `keystore.properties.example` to an **untracked** `keystore.properties` and configure your signing key. Keep the key and passwords out of Git.

```sh
./gradlew :app:assembleFossRelease :app:assembleCastRelease
```

APKs are written to `app/build/outputs/apk/{foss,cast}/release/`. Without a local `keystore.properties`, release APKs remain unsigned and cannot be installed on Android. Release builds use R8 and resource shrinking. Use the same signing key and increase `versionCode` for updates.

After verifying the signatures with the Android SDK's `apksigner verify`, run `python tools/package-release.py`. It checks the version and ABIs, then creates named APKs, a native dependency source archive, and `SHA256SUMS.txt` in `dist/<version>/`.

### Checks

```sh
./gradlew :app:testFossReleaseUnitTest :app:testCastReleaseUnitTest
./gradlew :app:lintFossRelease :app:lintCastRelease
```

Instrumentation tests and media fixture tools are in `app/src/androidTest/` and `tools/`. The `tools/test-playback-device.ps1` script installs debug APKs. Use a test device with a compatible signing key to avoid removing data from an existing installation.

## Project structure

| Directory | Contents |
| --- | --- |
| `app/src/main/java/com/phnem/vap/library/` | Scanning, library projections, and thumbnails |
| `app/src/main/java/com/phnem/vap/player/` | Playback, audio, video, and subtitles |
| `app/src/main/java/com/phnem/vap/network/` | Network protocols, media servers, and saved connections |
| `app/src/main/java/com/phnem/vap/cast/` | Remote playback and DLNA |
| `app/src/main/java/com/phnem/vap/ai/` | Models, Whisper, translation, and Smart Sync |
| `app/src/main/java/com/phnem/vap/ui/` | Compose UI, themes, and animations |
| `app/src/main/cpp/` | JNI and native build configuration |
| `app/src/cast/`, `app/src/nocast/` | Implementations with and without Google Cast |
| `docs/`, `tools/` | Technical reports, build instructions, and verification tools |

## Documentation and contributing

- [Technical documentation index](docs/README.md)
- [Changelog](CHANGELOG.md)
- [Contributing and reporting bugs](CONTRIBUTING.md)
- [Issues and feature requests](https://github.com/Phnem/VAP/issues)

## License

VAP's own code is available under the [MIT License](LICENSE). Copyright and license notices for components from Vetro Collection and third-party dependencies are preserved. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); full dependency license texts are included in the APK's assets. The Google Cast SDK is included only in the `cast` variant and has separate terms.
