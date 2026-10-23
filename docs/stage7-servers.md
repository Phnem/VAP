# Этап 7.2 — Jellyfin, Emby, Plex

Дата: 2026-10-21. Код VAP; живые аккаунты и серверы пока не проверены.

## Реализация

- Сетевые источники → Jellyfin / Emby / Plex. Вход Jellyfin/Emby по имени и паролю либо токену; Plex по токену. Для прокси можно задать базовый путь сервера. Пароль входа Jellyfin/Emby после получения токена не сохраняется.
- Просмотр видеобиблиотек, сериалов, сезонов и видео; страницы API по 200 элементов, общий предел 5000, UI по 100 элементов. На Plex переходы используют возвращённые сервером ключи, включая относительные pivot-ключи.
- Адаптеры получают актуальные медиассылки, MIME и позицию просмотра. Jellyfin/Emby выбирают доступный direct stream либо предоставленную сервером ссылку транскодирования; Plex открывает первый Media/Part. Это не полноценное согласование всех профилей устройств и не собственный транскодер.
- Прогресс: Playing / Progress / Stopped Jellyfin/Emby; timeline Plex. Начало, пауза, перемотка, периодическое обновление каждые 10 секунд и завершение. Ошибка синхронизации не прерывает просмотр; при недоступном сервере обновление повторяется при следующем событии. Офлайн-очереди долговременной синхронизации нет.
- После восстановления сохранённой очереди `ServerMediaSource` получает свежую ссылку и идентификатор серверной сессии при подготовке каждого видео. Запрос не запускается до продолжения просмотра. Сохранённая локальная позиция используется Media3 независимо от обновлённой ссылки. Отмена подготовки отменяет корутину; сетевые вызовы ограничены таймаутами.

## Учётные данные

`NetworkConnectionStore` сохраняет конфигурации в Android Keystore AES-GCM. Токены не попадают в URI, MediaItem, очередь или Intent: там только идентификатор подключения/видео/сессии. Плеер создаёт фабрику HTTP отдельно для выбранного подключения, в том числе для сегментов HLS/DASH. Заголовки разрешены только в пределах origin и базового пути; авторизованные перенаправления запрещены. Выданные сервером query-токены удаляются и заменяются заголовком; внешние хосты и неизвестные встраивания токена отвергаются. Внешние Intent/MediaController не могут подставить доверенную ссылку отчётов прогресса.

## Файлы

- `network/ServerClient.kt`, `ServerPlaybackRef.kt`, `ServerMediaSource.kt`, `ServerProgress.kt`.
- `network/NetworkConnection.kt`, `NetworkConnectionStore.kt`, `NetworkHttp.kt`, `NetworkPlayback.kt`, `NetworkSourcesDialog.kt`.
- `player/model/PlayableItem.kt`, `PlaybackConnection.kt`, `PlayerActivity.kt`, `PlaybackService.kt`, `library/TreeStore.kt`.
- EN/RU строки, `ServerScopeTest.kt`, `ServerDeviceTest.kt`, runner `servers`.

Из MIT Vetro адаптирован `PersonalMediaServerPlaybackSource`: получение PlaybackInfo, direct stream и ограничение области авторизации. Просмотр библиотек, Plex, синхронизация прогресса и обновление сохранённого источника добавлены в VAP. Vetro не изменялся; код SDK сторонних клиентов не копировался.

## Проверки

Первый прогон на Xiaomi 13: PASS — контракты Jellyfin/Emby/Plex через внедрённый BoundedHttp, зашифрованный токен, жизненный цикл отчётов и реальные формы подключения. Это имитация ответов API, а не результат работы живого сервера. Финальные проверки: debug + AndroidTest + minified unsigned release собраны; 100 JVM-тестов, 0 падений; lint: 0 errors, 64 warnings, 1 hint. На телефоне PASS `servers` (включая фактическое декодирование локального MP4 от обновлённого отложенного источника, старт на 12 с, отмену и отказ авторизации), `network` (реальные HTTP/HLS/DASH, seek, reconnect, DAV/DLNA UI), `core`, `ass` (1080p p95 903 мкс в тестовой сцене). Формы Jellyfin/Plex проверены также по скриншотам. Runner `all` включает `servers`.

Логи: `app/build/device-test/server-final-build.log`, `server-regression-{servers,network,core,ass}.log`. Тесты сервера не доказывают декодирование через живой Jellyfin/Emby/Plex: API имитирован, а проверка отложенного источника использует локальный MP4.

## Ограничения

Нужны живые Jellyfin/Emby/Plex разных версий, большие библиотеки, TLS и reverse proxy. Внешние CDN/RequiredHttpHeaders серверных источников, multi-part Plex, выбор разных серверных вариантов качества/дорожек, PIN/plex.tv OAuth и расширенная обработка транскодирования не реализованы. Токен Plex вводится пользователем. Совместимость конкретного кодека зависит от Media3/устройства и предлагаемых сервером источников.

## Основные API-источники

- [Plex Media Server API](https://developer.plex.tv/pms/) — JSON, ключи навигации и заголовок токена.
- [Emby AuthenticateByName](https://dev.emby.media/reference/RestAPI/UserService/postUsersAuthenticatebyname.html), [Playback check-ins](https://dev.emby.media/doc/restapi/Playback-Check-ins.html).
- [Jellyfin authentication](https://kotlin-sdk.jellyfin.org/guide/authentication.html), [Jellyfin API](https://typescript-sdk.jellyfin.org/).
- [Media3 CompositeMediaSource](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/CompositeMediaSource.java) — жизненный цикл отложенного источника.
