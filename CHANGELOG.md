# Changelog

## v0.2-Stable — 2026-10-26

Built-in updates, a floating mini player, and a lighter, calmer library.

### Added

- **In-app updates.** VAP finds new releases on GitHub at launch, on return to the app, and every 12 hours in the background; downloads the APK over six parallel connections with resume; checks size, SHA-256, and the signing key; and hands it to the system installer. The update window shows progress, speed, the release notes, and the signature fingerprint. Settings → *Check for updates* runs a manual check. Only builds signed with the author's key update themselves; F-Droid and rebuilt installs keep updating through their store.
- **Mini player.** The arrow in the player now minimizes it: playback continues in a small draggable window over the library that snaps to the screen edges. Tap to expand, the center button pauses, the cross stops playback.
- **Playback speed sheet.** A ruler from 0.5× to 3.0× in 0.05× steps with haptic ticks, plus 0.8×, 1×, 1.25×, 1.5×, and 2× presets, as in the Vetro audiobook player.
- **Orientation by video.** New default mode: vertical videos open in portrait, everything else in landscape. The library hints the orientation before the player opens to avoid a double rotation.
- **Settings.** Language (System, Russian, English) and theme, next to the update check and the on-device AI model downloads. A settings button replaces the scan button.
- **Pull to refresh** on the library and inside a folder.
- **Spring selector.** One pill glides between neighbouring options with a light spring: library filters, language, and theme.
- **Smarter search.** Case, dots, dashes, and spaces are ignored: `911` and `9.1.1` find `9-1-1`, and word order does not matter. When a folder matches, all of its files are shown.

### Changed

- **Flat colored folders** replace frosted glass, which was too heavy for scrolling on phones. Colors come from a hand-picked palette: fixed for Downloads, Camera, Movies, Screenshots, and Documents, and stable per folder for the rest.
- **Folders open like folders.** The cover swings open, the sheets fly out to the first items, and on close they fly back into the open folder before the cover shuts. List mode and tree mode have the same animation.
- **Grid ↔ list** switches with a sliding transition.
- The player's back arrow became a minimize arrow; the system Back button minimizes too instead of pausing.

### Fixed

- Back from the player could close the whole app after opening the library from the player and starting a video again.
- Folder counts drifted sideways in list mode.
- The theme choice in Settings briefly highlighted the wrong option.
- Sheets flying over the folder cover while it closed.

### Technical

- `versionCode = 3`, `versionName = "v0.2-Stable"`; `REQUEST_INSTALL_PACKAGES` and a `FileProvider` are used by the updater; WorkManager 2.10.0 is a new dependency.
- Release assets must be named `VAP-<tag>-foss.apk` and `VAP-<tag>-cast.apk` so each variant finds its own file.

## v0.1-Stable — 2026-10-24

The first public release of **VAP — Vetro Android Player**: the player from Vetro Collection as a standalone Android app in the Vetro family.

- Local video library with folders, thumbnails, search, favorites, and playlists.
- Media3 playback, saved progress, track controls, and picture-in-picture.
- Styled and dual subtitles, text editing, and export.
- Network files, Jellyfin, Emby, Plex, and DLNA.
- Whisper subtitle generation, local EN/RU translation, and Smart Sync.
- Two variants: `foss` without the Google Cast SDK and `cast` with Google Cast.
- Signed universal APKs for four ABIs, supporting Android 8.0+.
- `v0.1-Stable` shown in app metadata and the library menu; `versionCode = 2` allows updates from the local Alpha build.
- README, MIT License, third-party notices, and build instructions.

See the [README](README.md#limitations-and-requirements) and technical reports in `docs/` for limitations.
