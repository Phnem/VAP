# Этап 7.3 — трансляция

Дата: 2026-10-21. Основные контракты проверены на Xiaomi 13; пользователь подтвердил отсутствие живых NAS/медиасерверов/телевизора и выбрал тестовые серверы.

## Основа и сборки

Из MIT Vetro адаптированы RemoteModels, LanNetwork, RemotePlaybackManager, RemoteSessionPlayer, RemoteMediaPreparer, DLNA adapter/protocol, HLS/HTTP proxy, SRT↔VTT и GoogleCastAdapter. Источники Vetro не менялись. Уведомление, приложение-владелец сессии, чтение локальных URI и интерфейс VAP добавлены/переработаны.

Gradle distribution: `foss` (по умолчанию в Android Studio, DLNA, без Google SDK) и `cast` (DLNA + Google Cast SDK 22.3.1). Это реальные productFlavors; до этого документ миграции называл условные src/cast/src/nocast Vetro «flavor», хотя Vetro выбирал исходники по существованию папки.

Команды: `assembleFossDebug assembleFossDebugAndroidTest testFossDebugUnitTest`, `assembleFossRelease`, `assembleCastDebug assembleCastDebugAndroidTest assembleCastRelease`, `lintFossDebug lintCastDebug`. APK теперь находятся в `app/build/outputs/apk/{foss,cast}/{debug,release}/`. Runner принимает `-Distribution foss|cast` и `-Suite casting`.

## Поведение

Кнопка телевизора в доке плеера открывает поиск видеоприёмников. DLNA: SSDP на LAN-интерфейсах, ограниченные описания устройств, ConnectionManager/GetProtocolInfo и AVTransport/SOAP; можно ввести URL описания вручную. Google Cast: MediaRouter, Default Media Receiver, отдельные исходники и manifest metadata только cast-сборки. Устройство без Google Play services сохраняет DLNA.

После загрузки на приёмник телефон ставит локальное видео на паузу; приложение удерживает собственный контроллер независимо от Activity. Панель управляет play/pause, seek, громкостью, остановкой и возвратом. При возврате позиция приёмника применяется к тому же локальному видео. Если локальная очередь уже переключена на другое видео, старое видео не навязывается поверх новой очереди. Трансляция относится к одному текущему видео; автоматическая отправка следующего элемента очереди не реализована.

Выбранные импортированные/извлечённые субтитры нормализуются с задержкой/масштабом и передаются как SRT для DLNA или VTT для Cast, если адаптер допускает субтитры. Стили ASS на ТВ не сохраняются. Cast предоставляет выбор объявленных приёмником аудиодорожек, сохраняя выбранные текстовые дорожки; DLNA использует стандартную дорожку приёмника. Внешний MKA/аудиофайл требует ремультиплексирования и на ТВ отдельно не отправляется.

Foreground service с уведомлением удерживает прокси/опрос приёмника; сессия после уничтожения процесса самопроизвольно не запускается. На серверном видео progress reporter использует удалённый Player, локальный reporter на время трансляции подавляется.

## Доставка и ограничения

Открытые совместимые HTTP-ссылки могут отправляться напрямую. Локальные SAF/MediaStore/file и источники с авторизацией идут через прокси телефона. Локальные byte ranges, suffix ranges, HEAD и EOF проверяются; многодиапазонные запросы отвергаются. Неизвестная длина допускает последовательное чтение, но не Range. SMB/FTP/SFTP используют тот же read-only DataSource, что локальный плеер.

Прокси привязывается к выбранному LAN IPv4, а не ко всем интерфейсам. Только GET/HEAD/OPTIONS, случайный 128-битный токен, зарегистрированные ресурсы, срок жизни, ограниченные потоки/заголовки/плейлисты и закрытие сокетов после отключения. HTTP-клиент выбирается по ID серверного подключения, включая HLS-плейлисты и сегменты; авторизация сохраняет область origin/path. Секреты не передаются приёмнику.

HLS переписывает варианты, сегменты, ключи, init и альтернативные дорожки; для DLNA без HLS доступна последовательная склейка сегментов и AES-128. Это ремультиплексирование потока, не перекодирование. DASH с учётными заголовками и MKV на Cast отвергаются. Публичный DASH требует корректного CORS у источника.

Проверка форматов использует ConnectionManager и таблицу контейнеров; неизвестный набор кодеков не выдаётся за доказанную поддержку HEVC/VP9. У поколений Chromecast разные декодеры. Отказ устройства показывается как ошибка совместимости.

Исследование транскодирования: Media3 Transformer уже используется для экспорта в Э5 и может кодировать H.264/AAC через MediaCodec. Полная фоновая конверсия большого видео перед трансляцией требует отдельного задания, свободного места, отмены и предварительного профиля приёмника; потокового универсального кодера/FFmpeg в базовый APK не добавлено. Транскодер для трансляции не реализован, поэтому неподдерживаемый кодек требует другого источника/серверного транскодирования. Эту границу не следует считать универсальной совместимостью с ТВ.

## Проверки

Xiaomi 13: PASS собственный тестовый HTTP/SOAP DLNA-рендерер на loopback, реальный LocalCastFiles/Media3 DataSource, Range и suffix/ошибки, текстовые субтитры с поправкой времени, play/pause/seek/volume/stop, закрытие прокси и фактический локальный декодер с возвратом на 23 с. Реальная кнопка/панель найдены через accessibility, снят скриншот. Это не физический телевизор и не SSDP multicast. HLS-прокси фактически декодирует тестовое HLS-видео и перематывается на 15 с в обеих debug-сборках. Через обычный экран, общий RemoteRuntime, настоящий foreground service и Compose-панель тестового подключения нажаты play/pause/return; ответы идут по SOAP, возврат включает реальный локальный декодер. Панель с подключением и обычный выбор устройств просмотрены по скриншотам. Проверены реальный foreground-статус сервиса, его освобождение при возврате и действие остановки из уведомления. Для имитации LAN только адрес интерфейса и наличие LAN подменены на loopback; транспорт, сервер, раздача, контроллер, UI и сервис настоящие. Кнопка возврата закреплена вне прокрутки в горизонтальном режиме. CPU wake lock держится только для прокси, максимум 6 часов, и освобождается сервисом. Длительный screen-off/Doze на физической LAN ещё не измерен.

113 JVM: PASS (11 перенесённых протокольных тестов Vetro и 2 range-теста сверх 100 предыдущих). Debug обеих distribution собирается. Lint: foss 0 errors / 71 warnings / 1 hint, cast 0 errors / 70 warnings / 1 hint. Release обеих distribution собран; smoke подписывался только стандартным debug-ключом, а не пользовательским ключом выпуска. Отдельный Java instrumentation runner проверил непосредственно минифицированные APK: metadata, reflection OptionsProvider и создание CastOptions в cast, отсутствие провайдера/Google notice в foss. Проверка DEX также не нашла Google SDK в foss.

Повторные native `core`, `servers`, `ass` прошли. Устранена гонка ожидания диалога Continue watching в UI-тесте servers. Во время release-проверки обнаружен и исправлен застывающий заголовок PlayerScreen после смены видео: снимок обновляется после регистрации слушателя и в onEvents по окончательному состоянию контроллера. Тест проверяет заголовки при смене file → HLS → file. Финальные release-сборки с исправлением заголовков, закреплённой кнопкой возврата и завершением фонового сервиса прошли; обе повторно установлены и проверены Java smoke runner на телефоне. FOSS: 21 261 861 байт, Cast: 22 134 070 байт (unsigned APK). Итоговые 113 JVM без ошибок; lint 0 ошибок, 71/70 предупреждений. Лог сборки — `stage73-final-build.log`.

Логи: `cast-final-debug-build.log`, `cast-{foss,cast}-device.log`, `cast-ui-device.log`, `cast-title-{casting,servers,ass}.log`, `cast-service2-{foss,cast}.log`, `cast-release-{foss,cast}-smoke.log` в `app/build/device-test`. Для отдельного Java smoke runner сборка AndroidTest с `-PvapSmokeRunner=true`; после smoke обычный runner восстанавливается обычной сборкой AndroidTest. Этот runner не ссылается ни на классы приложения, ни на Kotlin stdlib, переименованные R8.

## API и лицензии

- [Google Cast Android integration](https://developers.google.com/cast/docs/android_sender/integrate).
- [Google Cast 22.3.1 release notes](https://developers.google.com/cast/docs/release-notes), [SDK terms](https://developers.google.com/cast/docs/terms/).
- [Supported media / различия поколений](https://developers.google.com/cast/docs/media).
- [RemoteMediaClient track selection](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/media/RemoteMediaClient).

MIT Vetro сохранён в `assets/licenses/remote-NOTICE.txt`; Google notice — только в `src/cast/assets`. Публикация/принятие магазином и физическая совместимость ещё не подтверждены.
