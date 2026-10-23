# VAP — Vetro Android Player

**Плеер из Vetro Collection — теперь как отдельное Android-приложение в семействе Vetro.**

[![Release](https://img.shields.io/github/v/release/Phnem/VAP?label=release)](https://github.com/Phnem/VAP/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/Phnem/VAP/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

VAP (Vetro Android Player) развивает видеоплеер Vetro Collection в самостоятельном приложении: собственная библиотека видео, локальное и сетевое воспроизведение, работа с субтитрами и ИИ-инструменты. Он сохраняет характер семейства Vetro: матовые стеклянные панели, анимации, светлую и тёмную темы. Интерфейс доступен на русском и английском языках.

## Скачать

Текущая версия — **v0.1-Stable**. Подписанные APK доступны в [GitHub Releases](https://github.com/Phnem/VAP/releases/latest).

| APK | Для чего |
| --- | --- |
| **VAP-v0.1-Stable-foss.apk** | Основная сборка без Google Cast SDK. Локальное видео, сеть, DLNA и ИИ-инструменты. |
| **VAP-v0.1-Stable-cast.apk** | Те же возможности плюс Google Cast. Включает Google Cast SDK. |

Оба APK универсальные: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`. Требуется **Android 8.0 / API 26 или новее**. Варианты используют один package ID `com.phnem.vap` и устанавливаются как одно приложение; одновременно держать их нельзя. При обновлении поверх APK с той же подписью данные сохраняются.

Скачайте APK, разрешите установку из выбранного браузера или файлового менеджера и откройте файл. При первом запуске выберите язык, тему и доступ к видео. Дополнительные папки можно подключить через системный выбор каталога.

## Возможности

- **Библиотека:** поиск доступных видео через MediaStore, дополнительные SAF-папки, дерево каталогов, сетка и список, превью, поиск, сортировка и фильтры.
- **Организация просмотра:** избранное, скрытые папки, история, продолжение с сохранённой позиции, плейлисты и очередь.
- **Плеер:** AndroidX Media3 / ExoPlayer, управление жестами, скорость, масштаб и пропорции кадра, выбор дорожек, фоновое воспроизведение и «картинка в картинке».
- **Звук и изображение:** настройки обработки, внешние аудиодорожки, синхронизация звука, снимки и инструменты экспорта фрагментов.
- **Субтитры:** встроенные и внешние дорожки, SRT/VTT и стилизованные ASS/SSA через libass, две дорожки одновременно, задержка, редактирование текста и экспорт.
- **Сеть:** HTTP(S), HLS, DASH, SMB, WebDAV, FTP/FTPS и SFTP; подключение Jellyfin, Emby, Plex и просмотр DLNA-источников.
- **Трансляция:** DLNA; Google Cast дополнительно в варианте `cast`.
- **Whisper:** создание субтитров на устройстве после загрузки модели; обработка конечного видео фрагментами, кэширование и отмена.
- **Перевод:** локальный EN → RU и RU → EN через CTranslate2; опциональный облачный перевод через настраиваемый совместимый API с собственным ключом.
- **Smart Sync:** оценка сдвига и равномерного дрейфа субтитров по распознанной речи.

## Что учитывать

Поддержка контейнеров, кодеков, HDR, звуковых форматов и экспорта зависит от Android, декодеров устройства и источника. Универсальное транскодирование для телевизоров не реализовано; совместимость с конкретным DLNA/Cast-приёмником требует проверки.

Модели Whisper и локального перевода **не входят в APK**: они скачиваются отдельно из интерфейса. После загрузки локальное распознавание и перевод работают без сети. Потоковые live-субтитры не реализованы. Smart Sync рассчитан на субтитры на языке речи; для сильно перемонтированного видео и отдельных вставок результат может быть недостоверным. Облачный перевод отправляет текст выбранному пользователем сервису.

Для поиска через OpenSubtitles и облачных функций нужны собственные учётные данные. Приложение не поставляет фильмы, серверы или подписки на сторонние сервисы.

## Сборка из исходников

### Окружение

- JDK **21**.
- Android SDK Platform **36**, Build Tools **35.0.0** (выбор по умолчанию AGP 8.13), NDK **27.0.12077973**, CMake **3.22.1**.
- Python **3.12+**, доступный командой `python`, с поддержкой `venv` и `pip`.
- Компилятор C для хостовых генераторов FriBidi: MSVC Build Tools в Windows или системный toolchain в Linux.
- Интернет для первоначальной загрузки Gradle, зависимостей и закреплённых архивов нативных библиотек.

Gradle **8.13** поставляется через wrapper. Укажите SDK в `local.properties` (`sdk.dir=...`); для Python-скриптов при нестандартном расположении SDK задайте также `ANDROID_HOME` или `ANDROID_SDK_ROOT`. Установите NDK и CMake через SDK Manager Android Studio.

```sh
git clone https://github.com/Phnem/VAP.git
cd VAP
./gradlew :app:assembleFossDebug
```

В PowerShell используйте `.\gradlew.bat` вместо `./gradlew`. Debug APK: `app/build/outputs/apk/foss/debug/app-foss-debug.apk`. Для варианта с Google Cast используйте задачу `:app:assembleCastDebug`.

Сборка автоматически готовит libass, FreeType, HarfBuzz, FriBidi, whisper.cpp, CTranslate2 и SentencePiece. Исходники и хэши закреплены в скриптах `tools/prepare-*-source.py` и `tools/native/wraps/`. Подробнее: [нативный backend субтитров](docs/subtitle-native-build.md).

### Подписанный релиз

Скопируйте `keystore.properties.example` в **неотслеживаемый** `keystore.properties` и укажите существующий ключ подписи. Файл с паролями и ключ не должны попадать в Git.

```sh
./gradlew :app:assembleFossRelease :app:assembleCastRelease
```

Результаты находятся в `app/build/outputs/apk/{foss,cast}/release/`. Без локального `keystore.properties` release APK остаётся неподписанным и не устанавливается на Android. R8 и сокращение ресурсов включены в release. Для обновлений используйте тот же ключ и увеличивайте `versionCode`.

После проверки подписи командой Android SDK `apksigner verify` выполните `python tools/package-release.py`: скрипт проверит версию и ABI, подготовит именованные APK, архив исходников нативных зависимостей и `SHA256SUMS.txt` в `dist/<версия>/`.

### Проверки

```sh
./gradlew :app:testFossReleaseUnitTest :app:testCastReleaseUnitTest
./gradlew :app:lintFossRelease :app:lintCastRelease
```

Инструментальные тесты и подготовка медиафикстур находятся в `app/src/androidTest/` и `tools/`. Скрипт `tools/test-playback-device.ps1` устанавливает debug APK: используйте тестовое устройство с подходящей подписью, чтобы не удалять данные рабочей установки.

## Устройство проекта

| Каталог | Содержимое |
| --- | --- |
| `app/src/main/java/com/phnem/vap/library/` | Сканирование, проекции библиотеки и миниатюры |
| `app/src/main/java/com/phnem/vap/player/` | Воспроизведение, звук, изображение и субтитры |
| `app/src/main/java/com/phnem/vap/network/` | Сетевые протоколы, серверы и хранение подключений |
| `app/src/main/java/com/phnem/vap/cast/` | Удалённое воспроизведение и DLNA |
| `app/src/main/java/com/phnem/vap/ai/` | Модели, Whisper, перевод и Smart Sync |
| `app/src/main/java/com/phnem/vap/ui/` | Compose-интерфейс, темы и анимации |
| `app/src/main/cpp/` | JNI и конфигурация нативной сборки |
| `app/src/cast/`, `app/src/nocast/` | Отличия вариантов с Google Cast и без него |
| `docs/`, `tools/` | Технические отчёты, инструкции, сборка и проверки |

## Документация и участие

- [Навигация по технической документации](docs/README.md)
- [История версий](CHANGELOG.md)
- [Как предложить изменение или сообщить об ошибке](CONTRIBUTING.md)
- [Ошибки и предложения](https://github.com/Phnem/VAP/issues)

## Лицензия

Собственный код VAP распространяется под [MIT](LICENSE). В проекте сохранены уведомления для частей Vetro Collection и сторонних зависимостей. Их лицензии перечислены в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); полные тексты находятся в assets APK. Google Cast SDK входит только в вариант `cast` и имеет отдельные условия использования.
