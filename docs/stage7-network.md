# Сетевые источники — блок 7.1

Состояние на 2026-10-21. Реализованы HTTP/HTTPS, HLS, DASH, WebDAV, SMB2/3, FTP, SFTP и DLNA/UPnP ContentDirectory. Основные проверки выполнены на Xiaomi 13 / Android 16. SMB с настоящим сервером и multicast-обнаружение физических DLNA/NAS ещё не проверены; весь этап 7 не завершён.

## Использование

В библиотеке нажать заголовок → «Сетевые источники». Можно добавить, изменить, удалить и повторно открыть подключение. Список и учётные данные целиком зашифрованы через CredentialVault/Android Keystore. Режимы:

- HTTP/HTTPS: URL файла или потока. Для адреса без расширения можно явно выбрать HLS или DASH. MIME сохраняется в сессии и передаётся в PlayerActivity.
- WebDAV: URL корневой папки, необязательные Basic credentials. Обзор через PROPFIND Depth:1; переходы остаются в корне. Поддерживаются NAS с WebDAV.
- SMB: `smb://host/SHARE/folder/`, пользователь (включая `DOMAIN\user`) и пароль. Только SMB2/3, NTLM, обязательная подпись; SMB1, Kerberos, DFS и поиск сетевых шар не реализованы. Адрес и имя шары указываются вручную. Работа с сервером остаётся непроверенной.
- FTP: `ftp://host:port/folder/`; passive/EPSV, binary, MLSD с LIST fallback, seek через REST. Без пользователя — anonymous. FTP не шифрует передачу; интерфейс сообщает это перед сохранением.
- SFTP: `sftp://host:port/folder/`, пользователь/пароль и SHA256 fingerprint сервера. Отпечаток следует получить по доверенному каналу. Произвольный ключ не принимается; вход по ключевому файлу пока не реализован.
- DLNA: поиск MediaServer в Wi-Fi/Ethernet или ручной URL XML-описания. SOAP Browse поддерживает каталоги, страницы и MIME ресурсов, включая URL без расширения. До 5 000 элементов с явным сообщением об ограничении. Это источник, управление внешним рендерером относится к 7.3.

Три профиля буфера: 3–10, 15–30 и 60–90 секунд. Настройки действуют во время просмотра; верхний предел буферной памяти — min(64 MiB, 1/8 heap), минимум 4 MiB. Локальные источники используют обычную политику Media3. После краткого сетевого отказа сохраняются позиция и намерение play/pause; автоматические попытки ограничены. Ошибки авторизации, отпечатка SSH и постоянные HTTP-ошибки не повторяются автоматически.

## Перенос и архитектура

Из Vetro прочитаны и адаптированы WebDavPlaybackSource, PlaybackAuthScope, StreamRecoveryPolicy/StreamingPlayerTuning, DlnaProtocol и discovery из DlnaAdapter. Vetro не менялся. В исходнике не было SMB/FTP/SFTP и полноценного ContentDirectory-браузера — они реализованы в VAP.

Основные файлы: `network/NetworkConnection*`, `NetworkHttp`, `NetworkDataSource`, `RemoteFiles`, `RemoteAddress`, `NetworkPlayback`, `NetworkFailures`, `WebDavBrowser`, `SafeXml`, `UpnpProtocol`, `DlnaBrowser`, `NetworkSourcesDialog`. Интеграция: PlaybackService, PlayerFactory, PlayableItem/PlaybackConnection, PlayerActivity, TreeStore и меню библиотеки. EN/RU.

HTTP учётные данные ограничены origin и корнем, удаляются вне области; authenticated redirects отклоняются. SFTP использует отдельное имя bundled BC provider (`VAP-BC`), сохраняя системные TLS providers Android. SMB явно ограничен NTLM; optional desktop GSS и MBassador EL не используются (объяснения в ProGuard). Нет обхода проверки TLS-сертификатов.

Зависимости: существующие Media3 1.11.1 + HLS/DASH/OkHttp, OkHttp 4.12.0, SMBJ 0.13.0, SSHJ 0.41.0, Commons Net 3.13.0, Bouncy Castle 1.84 и транзитивные ASN-One/MBassador/SLF4J. Лицензии и атрибуции добавлены в `app/src/main/assets/licenses`.

## Проверки

- Debug, minified release, lint (0 ошибок), 95 JVM-тестов прошли. 17 новых JVM-тестов: границы credentials, XML/XXE, каталоги, URI, SSH pin, отсутствие повторов авторизации и UPnP/DIDL/SOAP.
- `network` на Xiaomi 13 прошёл: реальный HTTP-сервер внутри теста, MP4/HLS/DASH, HLS без расширения, декодирование на видимой поверхности, seek, Range, три буфера, 503 → восстановление, шифрование подключений, отказ authenticated redirect и отсутствие credential leakage. Через UI открыты WebDAV и DLNA, из DLNA запущено настоящее видео. UPnP сервер — тестовый, не физический NAS.
- `protocols`: настоящие loopback FTP (pyftpdlib) и SFTP (Paramiko) на компьютере через ADB reverse. Проверены список, random reads, реальное воспроизведение/seek и неверный SSH pin. SMBClient конструируется на Android; SMB-сервера для сквозного теста нет.
- После интеграции прошли `subtitles`, `ass`, `online`, `core`. ASS p95 простой сцены около 1 ms; OpenSubtitles проверен контрактом, без живого аккаунта.
- Снимки `network-webdav.png`, `network-dlna.png` просмотрены. Логи и снимки — в игнорируемой `app/build/device-test`.
- Минифицированный release подписан стандартным debug-ключом только для smoke-проверки: зашифрованные подключения прочитаны, через UI открыт SFTP-каталог и воспроизведено видео. Снимок `network-release-sftp.png` просмотрен. Для дополнительного HTTP/HTTPS smoke автоматическая проверка инструментов отклонила команды без подробного объяснения; результат HTTPS не заявляется. На телефон возвращена debug-сборка.

Повтор: `tools/prepare-network-fixtures.ps1`, затем `tools/test-playback-device.ps1 -Suite network`. FTP/SFTP: создать игнорируемый venv `app/build/network-test-tools`, установить `paramiko==4.0.0 pyftpdlib==2.1.0`, запустить `tools/test-file-protocols-device.ps1`. Серверы привязаны к loopback, имеют только чтение; созданные ADB reverse удаляются после теста. Телефон пробуждается перед набором, системный таймаут не меняется.

Остаются: реальные SMB/NAS, физический SSDP, другие ОС/устройства, длительные обрывы и нагрузка. Jellyfin/Emby/Plex (7.2) и Cast/DLNA renderer (7.3) ещё не реализованы в VAP. Живой HTTPS-источник отдельно на телефоне пока не проверялся; TLS обеспечивает стандартный OkHttp.
