# VAP — аудит Vetro Collection (Этап 1)

Дата: 2026-10-20. Источник: `D:\AndroidStudioProjects\Vetro-collection` (ветка `main`, коммит `204c7dc`, v3.4.2-Beta). Исходник **не изменялся**: только чтение.

## 0. Как проводился аудит и что значат статусы

- Код читался через граф репозитория (сигнатуры, вызовы), `Grep` по паттернам и прямое чтение ключевых файлов (`PlayerScreen.kt`, `BooksHomeScreen.kt` и др.).
- **Ни одна функция не запускалась на устройстве.** Приложение не собиралось и не ставилось. По правилам проекта проверку на телефоне делает владелец.
- Поэтому «Готово» в матрице значит: **реализация найдена и прочитана в коде**. «Работает на устройстве» отдельно не утверждается; для каждой строки указан способ проверки.
- «Нет» значит: поиск по перечисленным паттернам ничего не нашёл. Паттерны указаны в разделе 6.

## 1. Исходный проект: стек

| Параметр | Значение | Где |
|---|---|---|
| `namespace` / `applicationId` | `com.phnem.vetro` | `app/build.gradle.kts:39,43` |
| minSdk / compileSdk | 26 / 36 | `app/build.gradle.kts:40,44` |
| Kotlin / AGP | 2.1.0 / 8.13.2 | `gradle/libs.versions.toml` |
| UI | Jetpack Compose (BOM), Material3, Navigation Compose | |
| DI | Koin 4.1.1 (`di/*.kt`) | |
| Плеер | Media3 **1.11.1**: exoplayer, hls, dash, ui, ui-compose, session, inspector, datasource-okhttp, workmanager, transformer | `libs.versions.toml:34,85-97` |
| Стекло | `io.github.kyant0:backdrop` 1.0.0 | `libs.versions.toml:30,102` |
| Изображения | Coil 3.3.0 | |
| Сеть | OkHttp 4.12.0, Ktor 3.0.3 | |
| Торренты | libtorrent4j 2.1.0-39 (arm64, x86_64) | |
| Распознавание речи | whisper.cpp сабмодулем (`app/src/main/cpp/whisper.cpp`), **только arm64-v8a** | `.gitmodules`, `app/build.gradle.kts:50-54` |
| Cast | flavor-разделение: `app/src/cast` (Google Cast) и `app/src/nocast` | причина — F-Droid |
| Лицензия | MIT (`LICENSE`) | |
| Объём | ~650 файлов `app/src/main`, 61 файл `core/network` | |

Модули Gradle: `:app`, `:core:network`, `:baselineprofile`, `build-logic`. Плеер **не выделен в модуль**: он живёт внутри `:app` пакетами `localplayer`, `media`, `audiobooks`.

## 2. Архитектура плеера: три независимых движка

В Vetro нет единого плеера. Есть три отдельных, у каждого свой `ExoPlayer`.

### 2.1. Локальный плеер (скачанные файлы)

- Вход: `localplayer/ui/DownloadedPlayerActivity.kt` (Activity, PiP, поворот, сохранение прогресса).
- Экран: `localplayer/ui/PlayerScreen.kt:86` `PlayerScreen(...)`.
- Движок: `ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))` (`PlayerScreen.kt:110-123`). Это голый ExoPlayer: без `AudioAttributes`, без настройки `RenderersFactory`, без `LoadControl`, без `TrackSelector`.
- Плейлист: все серии сезона лежат одним плейлистом `MediaItem.fromUri(documentUri)`. Переход между сериями штатный.
- Поток данных: файлы, скачанные `MediaDownloadWorker`. **Встроенного выбора файлов с устройства нет** (SAF-плеер удалён в v3.3.5).
- Контролы: `localplayer/ui/PlayerControls.kt` (`PlayerControlsOverlay`, ~1700 строк): сам оверлей, шкала `SeekBar`, жесты, меню, HUD громкости/яркости, блокировка.
- Жесты: `PlayerZoom.kt` (чистая логика: оси, зоны, пороги), `PlayerZoomGestures.kt` (щипок), `PlayerLevels.kt` (громкость через `AudioManager`, яркость через `window.attributes.screenBrightness`), `PlayerArcMenu.kt` (дуговое меню).
- Часы позиции: `PlaybackClock.kt` (позицию читает только шкала, хост не перекомпонуется).
- Пропуск заставок: `localplayer/domain/*` (`AniSkipSegmentProvider`, `ExternalSkipSegmentProvider`, `MediaSkipCoordinator`, `SkipSegmentResolver`) + `MediaSkipPlayback.kt`.
- Системная карточка: `media/ui/VideoSystemMedia.kt` `VideoMediaSession` (MediaSession внутри Activity, **не сервис**).

### 2.2. Стриминговый плеер (сеть)

- Вход: `media/ui/StreamPlayerActivity.kt`, экран `StreamPlayerSurface.kt:73`.
- Движок: `media/player/StreamingPlaybackSession.kt` `StreamingPlaybackSessionFactory.createSession` — единственное место, где настроены `DefaultTrackSelector` (`StallAwareAdaptiveTrackSelection`), `DefaultLoadControl` внутри `AdaptiveBufferGuard`, OkHttp-источник, `VetroVideoCache`, `StreamingTransferMonitor`, `StreamRecoveryPolicy`, диагностика.
- Источники: `PlaybackSource` = `Local(uri)` | `Remote(VetroVideo)`; сетевые — `media/source/**` (WebDAV, Jellyfin/Emby, провайдеры кино/сериалов, аниме-хостеры).
- Субтитры: внешние треки через `MediaItem.SubtitleConfiguration` (`toSubtitleConfig`), меню `media/subtitles/SubtitleMenu.kt`, OpenSubtitles (`ExternalSubtitleService`, BYOK), Whisper.
- Трансляция: `media/remote/**` (менеджер, DLNA, Google Cast, прокси).

### 2.3. Плеер аудиокниг (сервис)

- `audiobooks/playback/AudiobookPlaybackService.kt` — `MediaLibraryService` (Android Auto, Bluetooth, уведомление).
- `AudiobookPlayerFactory` — единственный в проекте плеер с `AudioAttributes` + `setHandleAudioBecomingNoisy(true)` + `setWakeMode` + `LeveledSilenceChain` (пропуск тишины, Sonic).
- Торрент-источник: `audiobooks/torrent/TorrentDataSource.kt`, `SchemeRoutingDataSource.kt`.
- Таймер сна, умная перемотка, главы, субтитры по тексту книги.

### 2.4. Дублирование (важно для переноса)

| Что дублируется | Где |
|---|---|
| Классы `AudioTrackOption`, `SubtitleOption` и логика выбора дорожки | `PlayerScreen.kt:47-72, 403-433` и `StreamPlayerSurface.kt:383-504` (`streamAudioOptions`, `applyStreamAudioOverride`, `selectTextTrack…`) |
| Слушатель `Player.Listener` (isPlaying/state/tracks/videoSize) | оба экрана |
| `resizeMode = RESIZE_MODE_FIT`, `keepScreenOn = isPlaying` | `PlayerScreen.kt:338-341`, `StreamPlayerSurface.kt:283-285` |
| Поворот и PiP | `DownloadedPlayerActivity` и `StreamPlayerActivity` |
| Сохранение прогресса | `DownloadedPlayerActivity.persistCurrentProgress`, `media/progress/PlaybackProgressSaver.kt` |

Общее ядро уже частично вынесено: `PlayerControlsOverlay(player: Player, …)` принимает интерфейс `Player`, поэтому одни и те же кнопки управляют и ExoPlayer, и удалённым устройством (`RemoteSessionPlayer`).

### 2.5. Что в UI плеера уже общее

`PlayerControlsOverlay` зависит от доменных типов Vetro: `AudioTrackOption`, `SubtitleOption`, `SkipUndoOffer`, `RemoteButtonState`, `VideoFit`, `LocalPlayerLanguage`. Для VAP нужно развязать их от скипа аниме (AniSkip) и от `UiStrings`.

## 3. Что в плеере есть и чего нет (кратко; полная таблица в `feature-matrix.md`)

Есть (прочитано в коде):
- двойной тап: ±10 с (`SEEK_STEP_MS`), вперёд на 89 с (`SEEK_FORWARD_MS`, «пропуск опенинга»), серия тапов копит «пачку» (`SeekBurst`);
- горизонтальный свайп с предпросмотром времени (`SeekPreviewOverlay`);
- вертикальные зоны: громкость справа, яркость слева, мёртвая зона сверху 56 dp (`PlayerZoom.kt`, `PlayerLevels.kt`);
- удержание — ускорение ×2 (`HOLD_SPEED`);
- щипок переключает Fit/Crop;
- блокировка контролов (`locked`), PiP с кнопками (`PipActionsController`), ручной поворот;
- скорость 0.5–2× списком (`SPEED_OPTIONS`);
- аудиодорожки и встроенные субтитры из `Tracks`, запоминание выбора по языку;
- OpenSubtitles (BYOK), Whisper на устройстве + облако OpenRouter;
- WebDAV, Jellyfin, Emby как источники;
- DLNA и Google Cast с локальным прокси (`CastProxyServer`: Range, HLS-плейлисты, расшифровка сегментов, конвертация SRT↔VTT, `concat` с позиции).

Нет (по поиску):
- **библиотеки файлов вообще**: ни `MediaStore`, ни обхода SAF для видео (SAF-обход есть только у аудиокниг: `LocalFolderSource`);
- **открытия внешних видео**: в манифесте нет `intent-filter` `ACTION_VIEW` с `video/*`;
- **аудиофокуса и `setHandleAudioBecomingNoisy` у видеоплееров** (только у аудиокниг): звонок и наушники в видео не обрабатываются;
- **фонового воспроизведения видео** (`MediaSession` живёт внутри Activity);
- Android TV: нет `leanback`;
- эквалайзера, усиления громкости, баланса, Mono/Stereo, задержки аудио;
- стилизации субтитров: нет `CaptionStyle`/`SubtitleView`-настроек;
- ASS/libass, двойных субтитров, редактирования/экспорта субтитров;
- SMB, FTP, SFTP, DLNA как источник (только как рендерер);
- Plex;
- HDR и tone mapping, переключения частоты экрана;
- превью кадров на шкале, скриншота, GIF, экспорта фрагмента;
- OCR видео, перевода субтитров, Smart Sync, speech enhancement, Smart Chapters, AI Dubbing;
- системы расширений для плеера (в Vetro есть реестр хвостов `VETRO-TAIL`, см. `docs/agents/tails.md`, но не SDK).

## 4. Дизайн-система Vetro

### 4.1. Токены

| Что | Файл | Содержимое |
|---|---|---|
| Палитра | `ui/shared/theme/Colors.kt` | `BrandOrange #E85002`, `BrandOrangeBright #F16001`, `BrandDeepRed #C10801`, `BrandGreen #34C759`, `BrandSand #D9C3AB`, серые `#646464 / #A7A7A7 / #333333`. Тёмная: фон `#000000`, поверхность `#262626`. Светлая: фон `#F9F9F9`, поверхность `#FFFFFF` |
| Тема | `Theme.kt` (`isAppInDarkTheme` — 65 зависимых мест, главный хаб проекта), `AppThemed.kt`, `OverlayThemeTokens.kt` |
| Типографика | `Theme.kt:26` `SnProFamily = FontFamily.SansSerif` (**системный шрифт**: в комментарии «чтобы вернуть SN Pro — восстановить `FontFamily(Font(...))`»), `AsgrikeFamily` (`res/font/asgrike.otf`, декоративный), в `res/font` лежат `snpro_bold.ttf`, `snpro_mediumitalic*.ttf`, `snpro_lightitalic.ttf`, `noto_serif_variable.ttf` |
| Движение | `MotionTokens.kt` (310 строк): пружины `standard`, `heroCard`, `sheetPresent`, `largeSurfaceEnter/Exit`, `menuPop`, `dialogPop`, `press`, уведомления; твины `tweenFast/Standard/Emphasized`, `easeEnter/Exit`; математика `rubberBand`, `flingFinalPosition`, `willDismiss`; `ReducedMotion.kt` |
| iOS-слой | `IosDesign.kt`, `IosShapes.kt`, `IosScroll.kt`, `IosListComponents.kt`, `IosBottomSheet.kt`, `MotionBottomSheet.kt` |

Дизайн-спека проекта: `Design.md` и `vetro_echoic_ios_motion_guidebook.md` в корне Vetro.

### 4.2. Стекло: два разных материала

1. **«Жидкое» стекло** — `ui/shared/AdaptiveGlassProfile.kt`: `adaptiveGlassBackdrop(backdrop, shape, effects)` поверх библиотеки `kyant0:backdrop` (линза, рефракция, глубина). `GlassPreset` задаёт `fullBlur / reducedBlur / lensRefraction / lensDepth`. **Оптимизация:** при скролле блюр падает за 150 мс и возвращается за 340 мс (`GLASS_REDUCE_DURATION_MS`, `GLASS_RESTORE_DURATION_MS`, `LocalAdaptiveGlassScrollInProgress`). Включено/выключено через `LocalAdaptiveGlassEnabled`.
2. **Матовое стекло** — `ui/shared/FrostedGlass.kt`: `Modifier.frostedGlass(backdrop, shape, material)`. `FrostedMaterial` = блюр + насыщенность + контраст + подложка (`tint`) + зерно (`noiseAlpha` 0.01–0.03) + кант 1 px + верхняя подсветка + тень + запасная заливка, если системного блюра нет (`SystemBlurAvailable`). Готовые материалы: `notification`, `stackedNotification`, `playerControl`, `dock`. Зерно детерминировано (`Random(0x5E7B0)`, плитка 128×128).
3. Вспомогательное: `GlassBackdropRecovery.kt`, `PinnableBackdrop.kt`, `FadedBlur.kt`, `SheetGlass.kt`, `OverlayGlassPrimitives.kt`, `LiquidGlassTrack.kt`, `GlassIconButton.kt`, `GlassMenuHeader.kt`.

Известная ловушка (память проекта): нельзя структурно добавлять/убирать узел-модификатор над `layerBackdrop`, иначе стекло плоско рисуется. Включать/выключать свойствами; двигать контент через `.offset {}`, а не `graphicsLayer`.

### 4.3. Анимация открытия и закрытия полок аудиокниг

Файлы: `audiobooks/ui/home/BooksHomeScreen.kt` (класс `ShelfMorph`, строки 357–428; `FlyingCovers` с 484) и `audiobooks/ui/home/ShelfParts.kt` (`FanShelfCard`, строка 136).

Как устроено:
- Полка = карточка со скруглением 24 dp (`FanShelfCard`): подпись сверху, название по центру, **веер из трёх обложек** высотой 118 dp, нижний край обрезает клип карточки. Каждая обложка стоит по позе `FanPoses` (смещение `dx/dy` + `rotationZ`).
- Морф — это **общий прогресс `Animatable(0f→1f)`** и две таблицы прямоугольников `from` (обложки в веере) и `to` (обложки в ряду страницы полки). Три обложки (`FLYING = 3`) рисуются отдельным слоем `FlyingCovers` поверх дома и страницы; размер финальный, путь и поворот идут как transform, чтобы картинка не перемеряла.
- `expand`: запомнить `from` и `card`, открыть страницу, **подождать два кадра** (`withFrameNanos` ×2: композиция и раскладка), считать `to`, анимировать `progress` до 1 пружиной `MotionTokens.largeSurfaceEnter()`. При `reducedMotion` — `snapTo`.
- `collapse`: `from`/`to` пересчитываются, прогресс идёт в 0 пружиной `largeSurfaceExit()`, потом `open = null`.
- `restore`: открыть без анимации (возврат со страницы книги).
- Координаты хранятся в `HashMap<String, LayoutCoordinates>` через `onPlaced`, **не в состоянии**: скролл дома не превращается в поток записей в снапшот («positions on demand»). Прямоугольник берётся в координатах экрана, не окна, и необрезанный (`rectOf`), иначе в конце закрытия был рывок.
- Порядок наложения веерный с первого кадра (края под серединой); нижний край карточки работает как «передняя кромка полки»: линия среза каждой обложки непрерывно съезжает к клипу карточки, в последнем кадре совпадает с ним пиксель в пиксель.
- `hidesFan` и `hidesRow` прячут настоящие обложки на время полёта (`alpha = 0`), чтобы не было дубля.

Перенос на папки: механика **универсальна** (источник и цель — два набора прямоугольников + прогресс). Привязка к аудиокнигам только в типах `OpenShelf`, `CachedBook`, `BookCover`. Для VAP: `FanShelfCard` → папка со «стопкой файлов» (см. референс Этапа 2), `FLYING` = число видимых файлов в стопке. Подробности в `design-spec.md`.

### 4.4. Системные панели, edge-to-edge

- `enableEdgeToEdge()` вызывается в `MainActivity` (с параметрами), `StreamPlayerActivity`, `MangaReaderActivity`. `DownloadedPlayerActivity`: через `ImmersivePlayerWindow.kt` (не проверено, см. матрицу).
- Вырез: `displayCutoutPadding` в `RemoteDevicesPanel`, `WindowInsets.displayCutout` в читалке манги. В экране плеера явной работы с вырезом **не найдено** — проверить на устройстве с вырезом.

## 5. Потенциальные ошибки и технический долг (найдено при чтении)

1. **Нет аудиофокуса и обработки «наушники выдернули» в видеоплеерах.** При звонке видео продолжит играть. Исправляется `setAudioAttributes(attrs, true)` + `setHandleAudioBecomingNoisy(true)` при создании плеера.
2. **Два плеера собираются по-разному.** Настройки буфера, трек-селектор, кэш, восстановление есть только у стримингового; локальный — «голый». В VAP нужна одна фабрика.
3. **Дублирование дорожек/слушателей** (см. 2.4): после переноса объединить в один `PlayerState`.
4. **Хаб `isAppInDarkTheme` (65 зависимостей)** и 255-параметровый предел `UiStrings` (см. память проекта): в VAP строки делать отдельными модулями, не раздувать один класс.
5. **`SnProFamily = FontFamily.SansSerif`** при наличии файлов SN Pro в `res/font`: имя вводит в заблуждение. В VAP использовать честное имя.
6. **Whisper только arm64-v8a** (`abiFilters`), на остальных ABI меню честно отключено. Для VAP: оставить так же, не обещать x86.
7. **Строки захардкожены в плеерских файлах** (`"Lock"`, `"PiP"`, `"Fit"/"Fill"`, `"Файл повреждён или имеет неподдерживаемый формат"` в `PlayerScreen.kt:198`). Для RTL и локализации нужен вынос.
8. **Интерфейс `PlayerScreen` завязан на аниме**: `malId`, `anilistId`, `autoSkipEnabled`, `FranchiseEpisodeMapper`. В VAP эти параметры не нужны.
9. **Cast за флагом сборки** (`src/cast` / `src/nocast`): без него `play-services-cast` блокирует F-Droid. VAP должен унаследовать это разделение, если планируется F-Droid.
10. Комментарий в `PlayerScreen.kt:83` («Играет строго локальные `content://`… Сетевого стриминга нет») **устарел**: отражает состояние до объединения с `StreamPlayer`.

## 6. Паттерны, по которым искалось «Нет»

`MediaStore`, `ContentResolver.query`, `ACTION_VIEW`, `AudioFocus`, `setAudioAttributes` (вне аудиокниг), `setHandleAudioBecomingNoisy`, `LoudnessEnhancer`, `Equalizer`, `ChannelMixing`, `CaptionStyle`, `SubtitleView`, `libass`, `ass4`, `jcifs`, `smbj`, `sshj`, `FTPClient`, `SFTP`, `Plex`, `plex`, `leanback`, `tone.?map`, `HDR`, `preferredRefreshRate`, `setFrameRate`, `PixelCopy`, `GifEncoder`, `TextRecognizer`, `mlkit`, `SeekParameters`, `PreviewLoader`, `setExtensionRendererMode`, `setEnableDecoderFallback`, `ContentDirectory`. Поиск шёл по `app/src/main`, `core/network`, `gradle/libs.versions.toml`.

## 7. Что требует решения владельца (открытые вопросы)

См. конец `migration-map.md`, раздел «Развилки».
