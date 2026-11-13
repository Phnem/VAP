# Changelog

## v0.6-Stable — 2026-11-13

Pinch zoom that never drags the picture, and new episodes that show up right away.

### Fixed

- **Zoom moved the picture.** Pinching to change the scale also dragged the picture, and once the video was zoomed a single finger panned it instead of changing brightness, volume or the playback position. A pinch now only scales the picture around the centre of the screen. One finger always keeps its normal job, zoomed or not, and hold-to-speed works in a zoomed video too.
- **New episodes appeared hours late.** A file written by another app (a downloader, a torrent client) can stay in the system media database as "pending", and VAP, like any other app, does not see pending files until the system scans them, which can take hours. After every scan VAP now looks in the folders it already knows (and their new subfolders) for video files missing from the database, asks the system to index them and refreshes the list. This runs in the background, does not hold the refresh spinner, and tries each file once.

### Changed

- Dragging the picture while zoomed is gone: the picture can no longer be moved, only scaled.

### Technical

- `versionCode = 7`, `versionName = "v0.6-Stable"`.
- No database changes. Updating from `v0.5-Stable` keeps your data.

## v0.5-Stable — 2026-11-10

Picture-in-picture that works, no more frozen picture, and no resume prompt.

### Fixed

- **Frozen or missing picture.** Some videos opened with a still frame or no picture at all while the sound and the timeline kept going, most often after going from the mini window back to the player. The player and the library mini window show the same playback and took the video surface away from each other; the picture is now handed over explicitly, so only one of them holds it at a time.
- **Two PiP windows, one of them black.** The *PiP* button opened the system picture-in-picture window and the library with its mini window at once: the video played in the mini window and the system window stayed black. Leaving the app then kept the black window. The system window now always has the picture, and the mini window stays hidden while the player or its PiP window is on screen.
- **Title of a video opened from another app.** A video opened from a file manager showed a number such as *881* instead of its name. The player now asks for the file name and cleans it the same way the library does.

### Changed

- **PiP goes to the home screen.** The *PiP* button at the bottom of the player puts the video in the system window and takes you to the home screen instead of the library.
- **Folder button.** The button at the top of the player that returns to the library and keeps playing in the mini window is now a folder with an arrow, so it no longer looks like a second PiP button.

### Removed

- **"Continue watching?" prompt.** The library no longer asks to continue the last video when it opens. The last session still waits in the mini window, and the *Continue watching* row is unchanged.

### Technical

- `versionCode = 6`, `versionName = "v0.5-Stable"`.
- No database changes. Updating from `v0.4-Stable` keeps your data.

## v0.4-Stable — 2026-11-03

Tracks what is new, selects several videos at once, and closes folders reliably.

### Added

- **New videos and folders.** Anything you have not seen yet rises to the top: folders with new videos on the home screen, new videos inside a folder (in series folders they form a *New* section above the seasons). A video is new until you open it; a folder is new until you open and close it. Everything that was on the device (or in a picked folder) at its first full scan counts as already seen, so a library that existed before VAP is not flagged. Widening video access (selected videos → all videos) does not flag the old videos either. The playback queue keeps the normal order, so episodes still play one after another.
- **"New" chip and folder indicator.** A new video thumbnail carries an orange *New* chip. A folder shows the full chip when the folder itself is new or all of its videos are; when only some videos inside are new it shows a small orange dot. In list mode the chip sits next to the video count and the dot on the folder icon.
- **Multi-select by long press.** Hold a video to select it (check mark and a green frame, the same green as the watched mark), tap others to add them. A small glass dock appears at the top right with outline icons: trash (red, always asks to confirm), move to another folder, favorite, mark as watched, and *More* (the old video menu) when one video is selected. *Move* sends the selected thumbnails as a small square stack to the top-left corner under the Back button and turns the dock into two bubbles: confirm and cancel. The stack stays on top while you go back and open any folder; the confirm bubble stays dim until a different, compatible folder is open. Confirm flies the stack into that folder's grid and removes the videos from the old one; cancel throws the stack and the bubbles off the top of the screen and moves nothing. Back clears a selection that has not been sent to the corner.
- **Glass notifications.** Results of delete and move appear in a dark glass pill above the dock and the mini player, instead of a white system toast.

### Changed

- Long press on a video now selects it instead of opening the video menu; the menu is behind *More* when one video is selected.
- Delete and move need the system's consent dialog on Android 11 and later (*Allow VAP to modify/delete…*). Moving works inside one storage volume, and inside the same provider for folders added by hand. Folders you add by hand now keep read and write access; folders added by an earlier version have read-only access, so add one again to let VAP change files in it.

### Fixed

- Closing a folder sometimes skipped the flying sheets: after the activity was recreated (rotation, theme or language change, returning from a killed process), after the folder screen was scrolled past its first cards, or when the first videos changed while the folder was open. The sheets now always fly back to what the folder screen shows, and the home list keeps its order while a folder is open so the sheets land on the card they came from. A second Back press during the closing animation no longer starts a second close.

### Technical

- `versionCode = 5`, `versionName = "v0.4-Stable"`.
- Database version 2: `seen_video` and `seen_folder` tables (migration `1.sqm`); the baseline is stored per source (device storage or one SAF tree) in the `library_new` preferences. Updating from `v0.3-Stable` upgrades the database in place and keeps your data.

## v0.3-Stable — 2026-10-27

Matte black folders by default, a tidier Settings sheet, and sorting cards in the Vetro style.

### Added

- **Colored folders switch.** Settings → *Colored folders*. Off by default: every folder is matte black with a thin light rim, the back plate slightly lighter than the front so the tab and edges read on a black theme. On: the previous hand-picked colors.
- **Sort cards.** The sort sheet now uses Vetro Collection cards: an icon tile, the option name, a short hint, and a radio mark; the selected card is tinted with the accent and shows the current direction (ascending or descending). Tapping the selected card flips the direction.

### Changed

- **AI models in one card.** Speech recognition and subtitle translation models are collapsed into a single *AI models* card that shows how many are downloaded and expands into the same list on tap. Download progress stays visible while it is collapsed.
- **Settings without a header plate.** The title scrolls with the content, no straight-edged plate cuts the list under it, and the *Close* button floats at the top while you scroll.

### Fixed

- The *English* language pill lost its last letter when the three language options were wider than the screen. Option padding now shrinks to fit; the library filter uses the same selector.

### Technical

- `versionCode = 4`, `versionName = "v0.3-Stable"`; `coloredFolders` is a new library setting stored with the other view settings.

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
