# Сторонние компоненты

Лицензия MIT в корне относится к собственному коду VAP. Сторонние компоненты сохраняют свои лицензии и уведомления; полные тексты, включаемые в APK, доступны в [app/src/main/assets/licenses](app/src/main/assets/licenses/).

| Компонент | Лицензия / уведомление |
| --- | --- |
| Части Vetro Collection | MIT; исходные copyright и тексты сохранены в `*-NOTICE.txt` |
| AndroidX, Jetpack Compose, Media3 | Apache-2.0; лицензии также распространяются в метаданных зависимостей |
| SQLDelight | Apache-2.0 |
| libass 0.17.5 | ISC — `libass-ISC.txt` |
| FreeType 2.15.0 | FreeType License — `freetype-FTL.txt`, `freetype-LICENSE.txt` |
| HarfBuzz 13.0.1 | Применимые уведомления в `harfbuzz-COPYING.txt` |
| FriBidi 1.0.17 | LGPL-2.1-or-later, отдельная динамическая `libfribidi.so`; `fribidi-LGPL.txt`, `fribidi-AUTHORS.txt` |
| whisper.cpp / ggml | MIT — `whisper-NOTICE.txt` |
| CTranslate2, SentencePiece и их зависимости | Лицензии в `translation-NOTICE.txt` и исходниках закреплённых ревизий |
| OkHttp | Apache-2.0 — `okhttp-Apache-2.0.txt` |
| SMBJ, SSHJ, Apache Commons Net | Уведомления `smbj-NOTICE.txt`, `sshj-Apache-2.0.txt`, `commons-net-*` |
| Bouncy Castle, SLF4J, MBassador, ASN.1 | Соответствующие тексты в `assets/licenses/` |
| Google Cast SDK — только `cast` | Отдельные условия Google; [уведомление в cast assets](app/src/cast/assets/licenses/Google-Cast-NOTICE.txt) |

Приложение использует системный шрифт Android. Загружаемые модели не входят в APK и имеют собственные условия, указанные в каталогах моделей и отчётах [Whisper](docs/stage8-whisper.md) и [перевода](docs/stage8-translation.md).

## Исходники нативных зависимостей

Для релиза `v0.1-Stable` вместе с APK публикуется архив `VAP-v0.1-Stable-native-sources.zip`: закреплённые исходные архивы нативных зависимостей, wrap-файлы и сборочные скрипты. Исходники самого приложения доступны в Git по тегу релиза и стандартными архивами Source code на GitHub.

Локальные изменения upstream воспроизводятся в `tools/build-subtitle-native.py` и `tools/prepare-translation-source.py`, параметры JNI — в `app/src/main/cpp/CMakeLists.txt`. Инструкции: [нативная сборка](docs/subtitle-native-build.md), [сборка APK](README.md#сборка-из-исходников). Динамические библиотеки можно заменить, пересобрав и переподписав APK своим ключом; приватный ключ подписи VAP не распространяется.
