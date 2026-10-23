# Техническая документация VAP

Пользовательское описание, установка и текущие команды сборки находятся в [основном README](../README.md). История публичных релизов — в [CHANGELOG](../CHANGELOG.md).

Документы ниже содержат проектные решения и отчёты отдельных этапов. Даты и результаты относятся к указанному в каждом отчёте состоянию кода и устройству; ранние статусы переноса и тестов не являются сводкой текущего релиза.

| Раздел | Документы |
| --- | --- |
| План и происхождение кода | [План](plan.md), [аудит](audit.md), [карта переноса](migration-map.md), [матрица функций](feature-matrix.md) |
| Интерфейс | [Дизайн](design-spec.md), [UI kit](ui-kit.md), [брендинг](brand/logo-concepts/README.md) |
| Библиотека | [Доступ к видео и папкам](library-access.md) |
| Плеер | [Воспроизведение](stage5-playback.md), [управление](stage5-controls.md), [отображение](stage5-display.md), [изображение](stage5-image.md) |
| Звук и субтитры | [Аудио](stage6-audio.md), [субтитры](stage6-subtitles.md), [ASS](stage6-ass.md), [онлайн-поиск](stage6-online.md) |
| Нативная сборка | [libass / FreeType / HarfBuzz / FriBidi](subtitle-native-build.md) |
| Сеть и трансляция | [Сеть](stage7-network.md), [серверы](stage7-servers.md), [DLNA и Cast](stage7-cast.md) |
| ИИ-инструменты | [Whisper](stage8-whisper.md), [перевод](stage8-translation.md), [Smart Sync](stage8-sync.md) |
| Производительность | [План измерений](perf-plan.md), [инструменты](../tools/perf/README.md) |
