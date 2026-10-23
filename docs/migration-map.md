# VAP — карта миграции из Vetro Collection (Этап 1)

Дата: 2026-10-20. Основа: `audit.md` и `feature-matrix.md`. Все пути Vetro — под `app/src/main/java/com/example/myapplication/` (ниже `V/`), если не сказано иное. Цель — `D:\AndroidStudioProjects\VAP`, `namespace = applicationId = com.phnem.vap`.

## 1. Правила переноса

1. **Только копирование.** Vetro не меняется (`git status` в нём не должен получить новых изменений от VAP).
2. **Лицензия.** Vetro — MIT, VAP — MIT: код Vetro копируется свободно, заголовок автора (`Copyright (c) 2026 Ievg`) сохраняется в `LICENSE`/`NOTICE.md` VAP.
3. **Механические замены при копировании:**
   - пакет `com.example.myapplication` → `com.phnem.vap`;
   - класс ресурсов `com.phnem.vetro.R` → `com.phnem.vap.R` (в Vetro `namespace = com.phnem.vetro`, а исходный пакет другой; перепутать их — частая ошибка, см. память проекта);
   - `VetroApplication`/Koin-модули не копировать целиком: брать только нужные определения.
4. **Эквивалентность сначала, рефакторинг потом.** В первой волне файл переносится с минимальной правкой (пакет, импорты, вырезание аниме-параметров). Объединение дублей — отдельный шаг после работающей сборки.
5. **Сторонние репозитории (Next Player и другие GPL/AGPL) — только метод clean room.** Правила:
   - GPL-код не копируется и не пересказывается построчно;
   - сначала по публичной документации и по наблюдаемому поведению пишется словесная спецификация в `docs/cleanroom/<тема>.md` (что приложение делает, без кода и без имён внутренних классов);
   - код пишется по этой спецификации;
   - в `docs/cleanroom/` для каждой темы фиксируется, какие источники открывались.
   - **Ограничение:** один агент не может быть «двумя изолированными командами». Поэтому правило практическое: исходники GPL-проекта в реализуемых частях не открываются вовсе, спецификация строится по документации, README, скриншотам и поведению. Если нужна строгая изоляция «аналитик ≠ исполнитель», это решение владельца (см. §8, вопрос 2).
6. **Библиотеки с копилефт-лицензией** (FFmpeg-расширения, VLC, libass-обвязки) подключаются только отдельным опциональным модулем и после проверки лицензии (правило 9 из ТЗ).

## 2. Что куда переносится

Обозначения действий: **К** = копировать, **А** = адаптировать, **З** = заново, **—** = не переносить.

### Волна A (Этап 3): ядро плеера — достаточно для первой сборки

| Файл Vetro | Куда в VAP | Дейст. | Что вырезать / развязать | Зависит от |
|---|---|---|---|---|
| `V/localplayer/ui/PlayerScreen.kt` | `player/ui/PlayerScreen.kt` | А | Убрать `malId`, `anilistId`, `autoSkipEnabled`, `mediaAnimeId`, `LocalEpisode`, `DownloadedSkipStore` (аниме-пропуск), строки `"Файл повреждён…"` вынести в ресурсы. Заменить `LocalEpisode` на нейтральный `PlayableItem(uri, title, positionMs)` | `PlayerControls`, `PlayerLevels`, `PlayerZoomGestures`, `PlaybackClock`, `VideoMediaSession` |
| `V/localplayer/ui/PlayerControls.kt` (1700 строк) | `player/ui/PlayerControls.kt` | А | Убрать `skipVisible/onSkip/undoOffer/onUndoSkip` и `SkipButton/UndoSkipButton`, число `SEEK_FORWARD_MS=89_000` («пропуск опенинга») сделать настройкой. Остальные зависимости: `IosScroll`, `BubbleClusterLoader`, `RemoteButtonState`, `BrandOrangeBright`, `MotionTokens`, `SnProFamily`, `Haptic` | см. §3 |
| `V/localplayer/ui/PlayerZoom.kt` | `player/ui/PlayerZoom.kt` | К | — (чистая логика, без зависимостей) | — |
| `V/localplayer/ui/PlayerZoomGestures.kt` | `player/ui/PlayerZoomGestures.kt` | К | Позже заменить переключение Fit/Crop на свободный зум (5.2.6) | `PlayerZoom` |
| `V/localplayer/ui/PlayerLevels.kt` | `player/ui/PlayerLevels.kt` | К | — | `AudioManager`, `Window` |
| `V/localplayer/ui/PlaybackClock.kt` | `player/ui/PlaybackClock.kt` | К | — | `Player` |
| `V/localplayer/ui/PlayerArcMenu.kt` | `player/ui/PlayerArcMenu.kt` | К | — | `MotionTokens` |
| `V/localplayer/ui/ImmersivePlayerWindow.kt` | `player/ui/ImmersivePlayerWindow.kt` | К (после чтения, статус «Не проверено») | — | `WindowInsetsControllerCompat` |
| `V/localplayer/ui/PlayerSettings.kt` | `player/settings/PlayerSettings.kt` | А | Убрать `autoSkip`, `AppLanguage` | DataStore |
| `V/localplayer/ui/DownloadedPlayerActivity.kt` | `player/PlayerActivity.kt` | А | Убрать `animeId`, `LocalEpisode`, `progressSnapshot` по сезонам; вход — `ACTION_VIEW` `video/*` + внутренний Intent; сохранить PiP, поворот, `onStop`-логику | `PipHostActivity` |
| `V/media/ui/PipActionsController.kt` | `player/pip/PipActionsController.kt` | К | — | `Activity` API 26 |
| `V/media/ui/VideoSystemMedia.kt` | `player/session/VideoMediaSession.kt` | А | Убрать `collectionPosterUri`, плейсхолдер взять из ресурсов VAP; на Э5 заменить на сервис | `media3-session` |
| `V/media/player/StreamingPlaybackSession.kt` | `player/engine/PlayerFactory.kt` | А | Стать **единой фабрикой** для локальных и сетевых источников: добавить `AudioAttributes(handleAudioFocus=true)`, `setHandleAudioBecomingNoisy(true)`, `setWakeMode` (образец — `V/audiobooks/playback/AudiobookPlayerFactory.kt`) | `VetroVideoCache`, `AdaptiveBufferGuard`, `StallAwareAdaptiveTrackSelection`, `StreamingTransferMonitor` |
| `V/media/player/AdaptiveBufferGuard.kt`, `StallAwareAdaptiveTrackSelection.kt`, `StreamingTransferMonitor.kt`, `StreamRecoveryPolicy.kt`, `StreamingPlaybackDiagnostics.kt`, `VetroVideoCache.kt` | `player/engine/*` | К | Переименовать `Vetro*` → `Vap*` (ничего не менять в логике) | OkHttp, Media3 |
| `V/media/player/PlaybackSource.kt` | `player/engine/PlaybackSource.kt` | А | `Remote(VetroVideo)` → `Remote(StreamRequest)` | — |
| `V/media/progress/PlaybackProgressSaver.kt`, `EpisodePlaybackStore.kt` | `data/progress/*` | А | Ключ «тайтл+серия» → Uri/хеш файла | DataStore |
| Классы `AudioTrackOption`, `SubtitleOption`, `VideoFit` + `extractAudioOptions`, `applyAudioOverride`, `matchPreferredAudioTrack` (`PlayerScreen.kt:47-75, 394-433`) и `streamAudioOptions`, `textTracks`, `selectTextTrack*` (`StreamPlayerSurface.kt:383-504`) | `player/tracks/TrackModels.kt`, `TrackSelection.kt` | А | **Объединить дубли в один файл**, `VideoFit.ORIGINAL` → `FIT`, добавить `FILL/STRETCH/ORIGINAL` | `Tracks`, `TrackSelectionOverride` |

### Волна B (Этап 3): дизайн-система

| Файл Vetro | Куда в VAP | Дейст. | Что вырезать / развязать |
|---|---|---|---|
| `V/ui/shared/theme/MotionTokens.kt`, `ReducedMotion.kt` | `ui/theme/MotionTokens.kt` | К | — |
| `V/ui/shared/theme/Colors.kt` | `ui/theme/Colors.kt` | А | Палитра VAP из `design-spec.md`; убрать `Rate*`, `Settings*Accent*`, `iconWell*` |
| `V/ui/shared/theme/Theme.kt`, `AppThemed.kt`, `OverlayThemeTokens.kt` | `ui/theme/*` | А | `isAppInDarkTheme` (хаб на 65 мест в Vetro) завязан на настройки Vetro — заменить источником из VAP |
| `V/ui/shared/theme/IosDesign.kt`, `IosShapes.kt`, `IosScroll.kt` | `ui/theme/*` | К | — |
| `V/ui/shared/FrostedGlass.kt` | `ui/glass/FrostedGlass.kt` | К | — |
| `V/ui/shared/AdaptiveGlassProfile.kt`, `GlassBackdropRecovery.kt`, `PinnableBackdrop.kt`, `FadedBlur.kt`, `SheetGlass.kt`, `OverlayGlassPrimitives.kt` | `ui/glass/*` | К | Проверить ссылки на настройки Vetro (`LocalModernUi`, `LocalFullBleedCards`) |
| `V/ui/shared/components/GlassIconButton.kt`, `GlassMenuHeader.kt`, `LiquidGlassTrack.kt`, `IosBottomSheet.kt`, `MotionBottomSheet.kt`, `IosListComponents.kt`, `ProgressRing.kt` | `ui/components/*` | К/А | По одному, с проверкой импортов |
| `V/ui/shared/loading/BubbleClusterLoader.kt`, `BubbleClusterMotion.kt` | `ui/loading/*` | К | Нужен `PlayerControls` |
| `V/ui/shared/back/ExteraBack.kt`, `ExteraSheetBack.kt` | `ui/back/*` | А | Анимация «назад»; флаг `EXTERA_BACK` убрать, оставить включённой |
| `V/utils/HapticUtils.kt` | `util/HapticUtils.kt` | К | Нужен `PlayerControls` |
| Шрифты `res/font/*` | `res/font/*` | А | Выбор шрифта — по `design-spec.md` (Этап 2) |
| Иконки `res/drawable/ic_player_*.xml` (17 шт.), `ph_*.xml` (Phosphor; нужные ~25) | `res/drawable/*` | К | Нужные `ph_*`: `arrow_left, caret_*, magnifying_glass, subtitles, speaker_high, play_fill, pause_fill, rewind_fill, fast_forward_fill, screencast, hard_drive, monitor_play, folder_plus, list_dashes, clock, dots_three_vertical, x, check` |

### Волна C (Этап 4): библиотека

| Файл Vetro | Куда в VAP | Дейст. | Комментарий |
|---|---|---|---|
| `V/audiobooks/ui/home/BooksHomeScreen.kt` (`ShelfMorph`, `FlyingCovers`, `OpenShelf`), `ShelfParts.kt` (`FanShelfCard`, `FanPoses`) | `ui/library/FolderMorph.kt`, `FolderCard.kt` | А | Механика морфа универсальна; заменить `CachedBook`/`BookCover`/`PlaceholderBook` на `VideoFile`/`FileCard`, `FLYING=3` → число файлов в стопке папки. **Главная адаптация — визуальная** (см. референс Этапа 2) |
| `V/audiobooks/data/local/LocalFolderSource.kt` | `data/library/FolderScanner.kt` | А | Оставить SAF-обход, права, `hasGrant`; заменить аудио-фильтр и теги на видео-метаданные |
| `V/audiobooks/ui/LocalBooksPanel.kt` | образец для экрана «Добавить папку» | А | Обработка `SecurityException`, `OpenDocumentTree` |
| `V/ui/shared/WorkspaceSearch.kt` | `ui/library/SearchBar.kt` | А | UI поиска |
| `V/domain/seasons/SeasonEpisodePosition.kt`, `SeasonEpisodesResolver.kt` | `domain/series/*` | А | Образец правил; парсер имён файлов писать заново |
| `core/network/` (TMDB, AniList, Shikimori, MAL, Кинопоиск, `TitleIdContributor`) | `:core:network` или провайдеры Э9 | А | Подключать после Э4 как опциональный провайдер метаданных; **не тащить весь модуль в Э3** |

### Волна D (Этапы 6–7): субтитры, сеть, трансляция

| Файл Vetro | Куда в VAP | Дейст. | Комментарий |
|---|---|---|---|
| `V/media/subtitles/SubtitleMenu.kt` | `player/subtitles/SubtitleMenu.kt` | А | Убрать `WhisperUi` до Э8; оставить страницы OpenSubtitles |
| `V/media/subtitles/ExternalSubtitleService.kt` + `OpenSubtitlesClient` + `PlaybackSourceConfigStore` | `provider/subtitles/opensubtitles/*` | А | Выделить интерфейс `SubtitleProvider` (9.1.1) |
| `V/media/remote/**` (`RemotePlaybackManager`, `RemoteSessionPlayer`, `RemoteModels`, `RemoteMediaPreparer`, `RemotePlaybackService`, `LanNetwork`, `dlna/*`, `proxy/*`, `ui/RemoteDevicesPanel.kt`) | `cast/*` | К | Целиком; `ui/RemoteDevicesPanel.kt` — под стекло VAP |
| `app/src/cast/**` и `app/src/nocast/**` | `app/src/cast`, `app/src/nocast` | К | Flavor-разделение сохранить (F-Droid) |
| `V/media/source/WebDavPlaybackSource.kt`, `PersonalMediaServerPlaybackSource.kt`, `PlaybackSource*Store/Service.kt`, `UserAccounts.kt`, `VetroModels.kt` | `source/*` | А | Для VAP: добавить обзор каталогов и библиотек; **не переносить** `JutSuSource`, `AnimeHeavenSource`, `movieseries/*Provider` (Rutube, Смотрим, и т. д.) — это контент-источники Vetro |
| `V/ui/settings/ConnectionTypes.kt`, `PlaybackSourcesSettingsSheet.kt` | `ui/sources/*` | А | Модель «тип подключения = запись реестра» |

### Волна E (Этап 8): ИИ

| Файл Vetro | Куда в VAP | Дейст. | Комментарий |
|---|---|---|---|
| `app/src/main/cpp/**` (`CMakeLists.txt`, `vetro_whisper.cpp`, сабмодуль `whisper.cpp`) | `app/src/main/cpp/**` | К | Сабмодуль добавлять командой `git submodule add`, фиксируя тот же коммит. Только `arm64-v8a` |
| `V/media/subtitles/whisper/*` (`WhisperCppEngine`, `WhisperModels`, `WhisperSubtitles`, `SpeechRouter`, `AudioExtractor`) | `ai/whisper/*` | К | JNI-имена функций в `vetro_whisper.cpp` зависят от пакета — **переименовать пакет и имена `Java_com_...` синхронно** |
| `V/di/whisperModule.kt` | `di/WhisperModule.kt` | А | — |
| `V/data/ai/*` (`AiProvider`, `AiLlmEndpoint`, `AiCredentialsStore`, `AiThrottlePolicy`), `data/remote/GeminiStructuredClient.kt`, `data/local/EncryptedGeminiApiKeyRepository.kt` | `ai/llm/*` | А | Основа перевода субтитров (8.2) |
| `V/audiobooks/playback/SilenceSkipping.kt` (`LeveledSilenceChain`) | образец для `AudioProcessor`-цепочки (эквалайзер, баланс, Mono/Stereo, улучшение речи) | А | — |

## 3. Связность: зависимости переносимых компонентов

```
PlayerActivity ──► PlayerScreen ──► PlayerControlsOverlay ──► PlayerZoom, PlayerLevels,
      │                │                    │                 PlayerArcMenu, OptionMenu,
      │                │                    │                 BubbleClusterLoader, MotionTokens,
      │                │                    │                 IosScroll, Haptic, SnProFamily
      │                │                    └──► RemoteButtonState (cast/ui)   ← единственная связь с cast
      │                ├──► PlaybackClock
      │                ├──► PlayerZoomGestures ──► PlayerZoom
      │                ├──► VideoMediaSession ──► media3-session
      │                └──► [аниме] MediaSkipPlayback, DownloadedSkipStore, LocalEpisode   ← ВЫРЕЗАТЬ
      └──► PipActionsController (PipHostActivity)

PlayerFactory ──► AdaptiveBufferGuard, StallAwareAdaptiveTrackSelection,
                  StreamingTransferMonitor, VetroVideoCache, StreamRecoveryPolicy
```

**Сильно связано с Vetro Collection (требует развязки):**

1. `PlayerScreen` и `PlayerControlsOverlay` — аниме-пропуск (`malId`, `anilistId`, `SkipUndoOffer`, `FranchiseEpisodeMapper`).
2. `StreamPlayerSurface` (функция на 300 строк с ~40 параметрами) — `VetroVideo`, `OpenSubtitlesUi`, `WhisperUi`, `RemoteSurface`, IntroDB.
3. `Theme.kt` / `isAppInDarkTheme` — хаб из 65 мест, читает настройки Vetro.
4. `UiStrings` (лимит 255 параметров, память проекта) — строки плеера в VAP заводить отдельными небольшими классами.
5. `MediaDownloadWorker`/`DownloadedSkipStore` — завязаны на скачивание аниме; в VAP не нужны.

**Готово к переносу почти без изменений:** `PlayerZoom.kt`, `PlayerZoomGestures.kt`, `PlayerLevels.kt`, `PlaybackClock.kt`, `PlayerArcMenu.kt`, `PipActionsController.kt`, `FrostedGlass.kt`, `AdaptiveGlassProfile.kt`, `MotionTokens.kt`, `media/player/*` (кроме переименования), `media/remote/**`, `WH/*`, `AudioExtractor.kt`.

## 4. Не переносить

| Что | Почему |
|---|---|
| `V/manga/**`, `V/audiobooks/**` (кроме образцов из §2) | Другие продукты Vetro |
| `V/sync/**` (Supabase, AniList/MAL/Shikimori sync) | Коллекция аниме |
| `V/domain/recommendations/**`, `ui/home/**`, `ui/details/**`, `ui/inspect/**`, `ui/stats/**` | Коллекция |
| `V/media/source/{JutSuSource,AnimeHeavenSource}.kt`, `movieseries/*Provider.kt`, `localplayer/domain/*` (AniSkip) | Контент-источники и аниме-пропуск Vetro. Для VAP — только через расширения (Э9) и только легальное |
| `V/update/**` | Система обновлений Vetro (режим по подписи) |
| `my_keystore.jks`, `local.properties`, `poiskkino.env`, `hs_err_pid*.log`, `vetro_logcat.txt` | Секреты и мусор в корне Vetro, **никогда не копировать** |
| Python/Chaquopy (`python`-папка) | Retired в v3.3.5 |

## 5. Зависимости Gradle для VAP

**Эталон для Э3 (минимум):** `media3-exoplayer`, `media3-ui`, `media3-ui-compose`, `media3-session`, `media3-datasource-okhttp`; Compose BOM + Material3 + Navigation; `io.github.kyant0:backdrop`; Coil; Koin; DataStore; Kotlin serialization. Версии брать из Vetro (`media3 = 1.11.1`, `kotlin = 2.1.0`, `agp = 8.13.2`), обновлять отдельным шагом.

**Добавлять позже:** `media3-exoplayer-hls/dash` (Э7), `media3-inspector` (Э4–5), `media3-transformer` (Э5, Э8), OkHttp и Ktor (Э7), `play-services-cast` (flavor `cast`, Э7), `libtorrent4j` — **не нужен** (торренты у Vetro для аудиокниг).

**Лицензии, проверить до включения (Этап 10.3):** `backdrop` (kyant0), `libtorrent4j` (если вдруг понадобится), Cast SDK (проприетарная лицензия Google), whisper.cpp (MIT по моим сведениям, **проверить**), модели Whisper (отдельные условия), Phosphor-иконки (MIT по моим сведениям, **проверить**).

## 6. Порядок переноса (Этап 3)

1. Каркас VAP: `settings.gradle.kts`, `build.gradle.kts`, `libs.versions.toml`, `app/` с `com.phnem.vap`, Git, `.gitignore`.
2. Волна B (тема, стекло, иконки) → пустой экран с темой и стеклом.
3. Волна A: `PlayerFactory` → `PlayerScreen` + контролы без аниме-пропуска → `PlayerActivity` (`ACTION_VIEW`) → воспроизведение одного файла из файлового менеджера.
4. Сборка → проверка владельцем на устройстве (`audit.md` §0).
5. Минимальный экран «Папка → список → плеер» на SAF (4.1.1, 4.1.3, 4.1.13).
6. Первый отчёт Этапа 3. Рефакторинг дублей — после зелёной сборки.

## 7. Критерии эквивалентности для Этапа 3

Перенесённый плеер должен давать то же, что в Vetro: двойной тап, горизонтальный свайп, громкость и яркость вертикальным свайпом, удержание ×2, щипок Fit/Crop, блокировка, PiP, поворот, скорость, дорожки и встроенные субтитры, автопереход. Каждый пункт — в `feature-matrix.md` (строки 5.1.x, 5.2.x, 6.1.x, 6.2.x, 4.2.6) и отмечается «Готово (проверено на устройстве)» только после подтверждения владельцем.

## 8. Развилки (нужно решение владельца)

1. **Общее ядро или копия?** Правило 9.4: «избегать двух независимых копий одного движка». Варианты: (а) **копия в VAP**, Vetro не трогаем (быстрее, проще, даёт расхождение со временем); (б) выделить модуль `:player-core` в VAP и позже подключать его в Vetro (дороже, нужна правка Vetro, а ТЗ его не разрешает на этапах 1–3). Предлагаю (а) для Э3 и пересмотр на Э9.
2. **Строгость clean room.** Практический режим (§1, п. 5) или строгое разделение ролей «аналитик / исполнитель»?
3. **Стартовый `versionName`** и канал выпуска (Alpha/Beta/Stable, как у Vetro)?
4. **Нужны ли в VAP русский и английский с первого дня**, и RTL уже в Э3, или позже?
5. **Нужен ли аниме-пропуск (AniSkip) в VAP?** По умолчанию — нет, вырезаем.

## 9. Состояние Этапа 3 (2026-10-20)

**Перенесено и собрано** (`:app:assembleDebug` и `:app:assembleRelease` проходят):
`ui/theme/*` (MotionTokens, Colors, Theme, IosDesign, IosShapes, IosScroll, OverlayThemeTokens), `ui/glass/*` (FrostedGlass + новый материал `folder()`, AdaptiveGlassProfile, PinnableBackdrop), `ui/loading/*`, `util/HapticUtils.kt`, `player/ui/*` (PlayerScreen, PlayerControls, PlayerZoom, PlayerZoomGestures, PlayerLevels, PlaybackClock, PlayerArcMenu, ImmersivePlayerWindow, PlayerSettings), `player/pip/PipActionsController.kt`, 20 иконок `ic_player_*` и `ph_television_simple*`.

**Изменено при переносе:** убраны `SkipButton`, `UndoSkipButton`, параметры `skipVisible/onSkip/undoOffer/onUndoSkip`, зависимости от `LocalEpisode`, `DownloadedSkipStore`, `malId/anilistId`, `AsgrikeFamily`, `AppLanguagePrefs`; `RemoteButtonState` вынесен в `player/ui/RemoteButtonState.kt`; префикс PiP-действий `com.phnem.vap.player.PIP_CONTROL`; в `PlayerScreen` добавлены аудиофокус, обработка «наушники выдернули» и стартовая позиция.

**Написано заново:** `library/*` (сканер SAF, деревья, миниатюры, ViewModel), `player/PlayerActivity.kt`, `player/ProgressStore.kt`, `ui/library/*` (главный экран, карточка папки, экран папки, нижний док), `ui/brand/VapWordmark.kt` (знак «V-Play»: контур сгенерирован `docs/brand/logo-concepts/export_android.py`).

**Отложено:** `VideoMediaSession` (системная карточка и кнопки; Э5, нужен сервис), Koin и DataStore (пока не нужны), чипы-фильтры «Все / Не просмотрено / Просмотрено» (появятся с историей, Э4.2), морф «листы летят в ряд» и наклон пластины (Э4.3.2), переключатель-заголовок «Библиотека ▾» (нужны источники, Э4).

**Структура модулей:** один `:app`. Выделять `:player-core` стоит перед Этапом 9 (интеграция с Vetro), когда станет известен состав общего API (решение №1 из §8 принято как «копия»).

## 10. Состояние Этапа 4 (2026-10-20)

**Данные.** SQLDelight 2.0.2 (как в Vetro), база `vap.db`, схема `app/src/main/sqldelight/com/phnem/vap/data/Vap.sq`: `history`, `favorite`, `hidden_folder`, `playlist`, `playlist_item`, `queue_item`, `video_meta`. Настройки вида и последняя сессия лежат в SharedPreferences `library`.

**Сканирование.** `library/FolderScanner.kt` работает через `DocumentsContract` (один запрос на папку). Узлы дерева хранят `parentId`, `childIds`, `totalCount`. Кэш `library/ScanCache.kt` — JSON в `filesDir`: список виден сразу, обход идёт в фоне.

**Логика без Android.** `LibraryProjector` (фильтры, поиск, сортировка, «Продолжить»), `WatchRules` (единое правило «просмотрено»: остаток не больше большего из 30 с и 5%), `EpisodeParser`, `SeriesGrouping`. 18 JVM-тестов проходят. `android.net.TestUri` в тестовых исходниках заменяет `Uri`.

**Экран.** `ui/library/`: `LibraryScreen`, `FolderScreen`, `FolderCard`, `FolderMorph`, `LibraryMenus`, `PlaylistsScreen`, `LibraryDock`. Морф перенесён по схеме `ShelfMorph` из Vetro: общий прогресс, таблицы `from` и `to`, координаты через `onPlaced`, ожидание двух кадров.

**Отклонения от `design-spec.md`.**
- Чипов четыре вместо трёх: добавлено «Избранное».
- Сортировка стоит чипом в конце ряда. Меню «Библиотека ▾» переключает раздел (Папки, Скрытые, Плейлисты) и режим дерева.

**Не сделано в Этапе 4.**
- Метаданные из онлайн-источников (4.2.11) — Этап 9.
- Автоматическое предложение «продолжить» при запуске (4.2.7).
- Добавление в очередь во время просмотра (нужен сервис плеера, Этап 5).
- Макробенчмарк и Baseline Profile (4.3.7, Этап 10).
- Проверка USB-накопителей (4.1.14).

**Риски, видимые только на устройстве.**
1. Наклон передней пластины: `graphicsLayer` с `rotationX` стоит поверх узла стекла. Если стекло рисуется неверно во время наклона, поставить `PLATE_TILT_DEGREES = 0f` в `FolderMorph.kt`.
2. Число слоёв блюра: по одному `layerBackdrop` на карточку папки.
3. Скорость обхода SAF на облачных провайдерах (Drive и т. п.).

## 11. Этап 5: сервис и активная очередь (2026-10-20)

Выполнен первый блок, весь этап остаётся в работе. Движок перенесён из композиции `PlayerScreen` в `PlaybackService`; экран использует `MediaController`. История и снимки сессии принадлежат сервису. Добавлены системное уведомление, возврат к текущей сессии, мини-панель библиотеки и добавление в активную очередь. Фабрика использует аудиофокус/noisy/wake по образцу Vetro AudiobookPlayerFactory и fallback инициализации декодера. Подробности решения, изменённые файлы, проверки Xiaomi 13 и ограничения: `stage5-playback.md`.

## 12. Этап 5: управление, настройки и кадры (2026-10-20)

Продолжена адаптация `PlayerSettings` и существующих контролов Vetro: общий Preferences DataStore, ViewModel, переключатели жестов, шаг перемотки вместо 89 секунд, индивидуальные скорость/Fit-Crop/аудиометка. Таймкод и кадровое превью в исходном Vetro отсутствовали — реализованы заново. Проверки и ограничения: `stage5-controls.md`.

## 13. Этап 5, блок 5.2 — геометрия кадра и ориентация

Исходные `localplayer/ui/PlayerZoomGestures.kt` и `DownloadedPlayerActivity.kt` Vetro прочитаны, не изменены. Прежний переключатель двух масштабов заменён свободным преобразованием: новые `VideoGeometry.kt`, `PlayerViewportState.kt`, `VideoDisplayDialogs.kt`; адаптированы `PlayerScreen.kt`, `PlayerZoomGestures.kt`, `PlayerControls.kt`, `PlayerActivity.kt`, `ImmersivePlayerWindow.kt` и DataStore.

В VAP доступны Fit, Fill, Crop (+25% к Fill), Stretch и настоящий Original 1:1; старые сохранённые значения мигрируют без изменения прежнего вида. Зум 1–8× и панорамирование разделяют события с перемоткой/уровнями. Ориентация сохраняется, Auto следует системной политике. Видео занимает всё окно, контролы учитывают вырез.

Проверены debug/release, 32 JVM-теста, lint (0 ошибок), четыре набора на Xiaomi 13/API 36. Новая проверка измеряет реальные пиксели, отправляет мультитач, проверяет отмену и выключение жеста, системную ориентацию и вырез. Системный таймаут экрана не менялся. Подробности и ограничения — `stage5-display.md`. Следующий блок — 5.3, весь этап 5 ещё не завершён.

## 14. Этап 5, блок 5.3 — декодеры, изображение и экспорт

Из Vetro прочитаны `media/subtitles/whisper/AudioExtractor.kt` и `media/player/StreamingPlaybackDiagnostics.kt`; исходный проект не менялся. Инструменты написаны для самостоятельного VAP: `player/video/*`, `VideoToolsDialog.kt`, интеграция в сервис, PlayerFactory, PlayerScreen и DataStore. Подключены Media3 effect/transformer 1.11.1; FFmpeg используется только на компьютере для тестовых файлов, в APK не добавлен.

Реализованы Auto/HW/SW и ограниченное восстановление после отказа работающего кодека, HDR/SDR, запрос seamless-подбора FPS, GPU-цветокоррекция по URI, PixelCopy PNG, MP4 H.264/AAC, GIF и системное сохранение, отмена и диагностическая панель. Для tone mapping изолирован внутренний мост к Media3 1.11.1 с явным правилом R8; при обновлении библиотеки его необходимо перепроверять. Нативная поверхность вписана с правильным aspect ratio до внешнего масштабирования, чтобы GL не добавлял двойные поля.

Debug/release и 40 JVM-тестов прошли, lint — 0 ошибок. На Xiaomi 13/API 36 прошли шесть наборов: core, controls, ui, display, video, video-ui. Проверены реальные AVC/HEVC/VP9/AV1, MP4/MKV/WebM/AVI, HDR/SDR, сбой HW→SW, экспорт и повреждённые файлы. Отдельно прошёл smoke минифицированного release с GPU-эффектом, PNG и успешным сохранением через DocumentsUI. На телефон возвращена debug-сборка. Подробные результаты и ограничения — `stage5-image.md`.

Код разделов 5.1–5.3 завершён. Проверки звонков, физических аудиоустройств, реальной смены Hz и других моделей/версий Android остаются открытыми; этап 6 не начат.

## 15. Этап 6, блок 6.1 — аудио

Переиспользованы сервис Media3 и DataStore VAP; прочитан шаблон preferred language из `media/ui/StreamPlayerActivity.kt` Vetro. Vetro не менялся. Написаны `player/audio/*` и `AudioToolsDialog.kt`: выбор языка/дорожки в сервисе, внешний локальный источник через SAF + MergingMediaSource, PCM EQ/RMS/gain/balance/mono/delay, переключение pitch и preferred output. Интегрированы PlayerFactory, PlaybackService, PlayerScreen/Controls и настройки. Новых зависимостей нет; FFmpeg только готовит тестовые файлы на компьютере.

50 JVM-тестов прошли, включая 10 новых DSP-проверок. Debug/release и lint (0 ошибок) проходят. На Xiaomi 13/API 36 прошли восемь наборов, включая новые проверки сервиса и UI с реальными AAC-дорожками, MKA/FLAC и постоянным SAF grant. В минифицированном release проверены внешний источник, его восстановление после force-stop, усиление, сброс и возврат к embedded; затем на телефон возвращена debug-сборка. Результаты и ограничения — `stage6-audio.md`. Код 6.1 реализован; 6.1.12 частично проверен, физические Bluetooth/USB требуют отдельного оборудования. Субтитры 6.2–6.4 ещё не начинались.

## 16. Исправление этапа 4 — автоматическая видеотека

По уточнению владельца реализованы системное разрешение первого запуска, MediaStore по всем доступным shared volumes, частичный доступ, отзыв разрешения, ContentObserver и сортировка папок по количеству видео. SAF сохранён как дополнительный источник; строки MediaStore не пишутся в дисковый кэш. Новые DeviceVideoScanner/DeviceVideoIndex/VideoAccess интегрированы в LibraryViewModel и MainActivity. Проверены 58 JVM-тестов, debug/release/lint, нативный набор library и реальные системные диалоги на Xiaomi 13. Подробности и ограничения: `library-access.md`.

## 17. Восстановление сессии и текстовые субтитры 6.2

NowPlayingBar предлагает продолжить сохранённую сессию при запуске. Later сохраняет паузу и позицию, поворот не повторяет закрытое предложение. Проверены нативный набор resume и холодный запуск после force-stop.

Выполнен блок текстовых субтитров: `player/subtitles/*`, SubtitleToolsDialog, SubtitleOverlay, настройки по URI и интеграция с сервисом. Реальные проверки включают встроенные SRT, два языка, синхронизацию, SAF, поиск рядом с видео, изменение текста и экспорт. Детали — `stage6-subtitles.md`.

## 18. Нативный ASS 6.3

libass и зависимости собраны из закреплённых исходников для четырёх ABI; собственный JNI и lifecycle-aware overlay сохраняют исходное оформление. Добавлены автоматическое извлечение встроенного ASS, шрифты Matroska и инвалидация кэша. Нативные проверки на Xiaomi 13 подтвердили пиксели, анимацию, гарнитуру из вложения, два языка и автоматический переход к libass. Измеренный p95 простой сцены около 0,9–1 мс. Результаты и пределы — `stage6-ass.md`; зависимости и лицензии — `subtitle-native-build.md`. Блок 6.4 ещё не реализован; клиент OpenSubtitles Vetro прочитан для следующего переноса.

## 19. Онлайн-субтитры 6.4

Адаптированы OpenSubtitlesClient и ExternalSubtitleService Vetro: API, кэш, квоты, ранжирование; добавлены moviehash и текстовый предпросмотр. CredentialVault использует Android Keystore, пароль аккаунта не сохраняется. Vetro не менялся. Нативный набор online прошёл с подставным транспортом и настоящим Keystore; живой аккаунт остаётся непроверенным. Подробности — stage6-online.md. Код этапа 6 реализован, проверки внешнего оборудования и сервиса перечислены в plan.md.

## 20. Сетевые источники 7.1

Адаптированы WebDAV/AuthScope, recovery tuning и DLNA protocol/discovery Vetro, исходный проект не менялся. Добавлены HTTP/HLS/DASH, WebDAV-каталоги, read-only SMB/FTP/SFTP, encrypted connection store и ContentDirectory. MIME проходит через PlayableItem, Intents и сохранённые сессии. 95 JVM-тестов, debug/release и lint прошли; на Xiaomi 13 проверены реальный HTTP/HLS/DASH, extensionless HLS, 503 recovery, FTP/SFTP и UI WebDAV/DLNA с запуском видео. SMB и физический SSDP не проверены. Детали и ограничения — stage7-network.md. Далее 7.2 и 7.3; весь этап 7 не завершён.

## 21. Медиасерверы (Э7.2, 2026-10-21)

`PersonalMediaServerPlaybackSource` MIT Vetro адаптирован в `network/ServerClient.kt`: Jellyfin/Emby PlaybackInfo + direct stream. Добавлены Plex, просмотр библиотек/папок, вход/зашифрованные токены, отчёты прогресса, scoped MediaSource для нескольких аккаунтов и обновление сохранённых источников при prepare. Внешние Intent/контроллеры не задают доверенные идентификаторы отчётов. Архитектура остаётся в :app; тяжёлых серверных SDK нет.

100 JVM, debug/release, lint 0 ошибок. Xiaomi 13: контракты API, Keystore, native формы и progress lifecycle; отложенный источник действительно декодирует локальный MP4 со стартом 12 с, отменой и ошибкой auth. Регрессии network/core/ass PASS. Живые медиасерверы ещё не проверены. Подробности и ограничения — `stage7-servers.md`. Следующий пункт — 7.3.

## 22. Трансляция (Э7.3, 2026-10-21)

MIT Vetro RemoteModels, LanNetwork, DLNA/Google Cast adapters, remote Player и HTTP/HLS proxy адаптированы в `cast/`; исходный проект не менялся. RemoteRuntime владеет контроллером, а foreground service удерживает раздачу и уведомление. Возврат восстанавливает позицию того же видео, защищает новую очередь и освобождает сервис/прокси. Поддержаны совместимые текстовые субтитры и объявленные Cast аудиодорожки. Google SDK изолирован настоящим flavor cast; foss содержит DLNA и не содержит Google DEX.

113 JVM PASS, обе debug/release и lint без ошибок. На Xiaomi 13 обе сборки проходят настоящий HTTP/SOAP mock-рендерер, HLS-прокси с декодером/seek, ranges, субтитры, обычный UI, foreground lifecycle, notification stop и возврат на локальный декодер. Финальные минифицированные APK проверены отдельным Java runner. Физические Cast/DLNA и multicast не проверены; транскодер исследован, но не реализован. Детали — `stage7-cast.md`. Далее 8.1 — локальный Whisper.

## 23. Whisper (Э8.1, 2026-10-21)

Из MIT Vetro адаптированы AudioExtractor/LinearResampler, каталог WhisperModels и планирование чанков. whisper.cpp закреплён по commit/SHA-256, собирается для четырёх ABI без обязательных новых ISA. JNI получил abort, освобождение контекста и стандартный UTF-8; добавлены проверка/докачка моделей, bounded PCM/очередь, atomic chunk cache и AiProcessingService. Моделей и облачной отправки звука в APK нет. Субтитры подключаются через общий SubtitleStorage; экспорт использует существующий SRT/VTT редактор.

118 JVM, debug/release обоих flavors и lint без ошибок. Xiaomi 13: реальная Tiny (~1,9 с на 11 с речи), выбранная вторая аудиодорожка MP4, язык, таймкоды, cancel/retry, progress, модель SHA/resume/delete, кэш, SRT/VTT и foreground lifecycle после HOME PASS. В обеих минифицированных сборках Java runner действительно распознаёт аудио через JNI. Регрессии core/ass/casting PASS. Другие модели, языки, длинные видео и энергопотребление остаются неизмеренными. Live Subtitles исследованы; потоковый режим не реализован. Подробности — `stage8-whisper.md`. Далее 8.2.

## 24. Перевод субтитров (Э8.2, 2026-10-21)

В Vetro локальных NMT/перевода субтитров не было. Добавлены CPU CTranslate2/SentencePiece с pinned sources и двумя отдельными int8 EN/RU моделями; повторно использованы CredentialVault, PlatformHttp, SubtitleStorage, foreground runtime. Whisper и перевод делят проверку загрузки ModelFiles и ожидание освобождения ресурсов NativeWork. Модели не упаковываются, облачный текст отправляется только явной кнопкой со своим ключом. Перевод — отдельная дорожка с исходными timecodes, original+translation, глоссарием, cache/retry и экспортом.

123 JVM PASS, debug/release обоих flavors, lint 0 ошибок. Xiaomi: настоящие EN→RU/RU→EN, UTF-8, abort/retry, глоссарий, dual/cached track и обычные панели PASS; облако — строгий тестовый транспорт без платных запросов. Обе минифицированные сборки реально выполняют JNI Whisper и перевод. ELF/ZIP 16KB на 64-bit PASS, FOSS без Google SDK. Регрессии Whisper/core/ass PASS. Детали и пределы — stage8-translation.md. Следующий блок 8.3 — Smart Sync.

## 25. Smart Sync (Э8.3) и переработка интерфейса (2026-10-21)

Smart Sync написан в VAP (в Vetro есть лишь идейный аналог `BookAudioAligner`), подробности — `stage8-sync.md`. По прямому указанию владельца интерфейс панелей плеера, сети и трансляции переведён с Material 3 на шторки и элементы Vetro Collection: перенесены `SheetStyle`, `SheetGlass`, `IosBottomSheet` (свайп, ручка), `MotionBottomSheet`, `IosSwitch`, образцы строк/капсул `PlayerSheets` и поля `AnimatedOneUiTextField` — `ui/components/*`, описание и соответствие образцам — `ui-kit.md`. Collection не изменялась.
