# UI-ядро панелей VAP (по образцу Vetro Collection)

Дата: 2026-10-21. Решение владельца: дизайн VAP берётся напрямую из `D:\AndroidStudioProjects\Vetro-collection`, отличается только палитра (она живёт в VAP: `ui/theme/Colors.kt`). Collection не изменялась.

## Что было не так

Панели этапов 5–8 (настройки плеера, звук, видео, субтитры, Whisper, перевод, Smart Sync, OpenSubtitles, трансляция, сетевые источники, таймкод, режим кадра, ориентация) собирались из готового Material 3: `AlertDialog`, `Dialog + Surface`, `FilterChip`, `Switch`, `Slider`, `OutlinedTextField`, `RadioButton`, `TextButton`. Библиотека (этап 4) и экран плеера уже были в языке Collection.

## Образцы в Collection и что перенесено

| Образец (Collection) | В VAP | Примечание |
|---|---|---|
| `ui/shared/theme/SheetStyle.kt`, `SheetGlass.kt` | `ui/components/SheetChrome.kt` | парящая панель, нарисованная тень, кромка; без `SheetGate` и режима «назад как в extera» |
| `ui/shared/components/IosBottomSheet.kt` (`IosSheetSwipe`, `NoOverscroll`, `GrabberHandle`) | `ui/components/SheetChrome.kt` | свайп вниз и протяжка списка закрывают шторку |
| `ui/shared/components/MotionBottomSheet.kt` | `ui/components/MotionBottomSheet.kt` | пружины крупной поверхности; оболочка и содержимое — разные траектории |
| `audiobooks/ui/PlayerSheets.kt` (`PlayerSheetFrame`, `Pill`, `PrimaryCapsule`, `SettingRow`, `NavRow`, `ActionRow`) | `ui/components/VapPanel.kt` | плеер всегда тёмный, поэтому шторки плеера тёмные независимо от темы |
| `ui/shared/components/IosListComponents.kt` (`IosSwitch`) | `ui/components/VapPanel.kt` | эластичный переключатель 51×31 |
| `ui/shared/components/SharedComponents.kt` (`AnimatedOneUiTextField`) | `PanelTextField` | подпись над полем, рамка оранжевая в фокусе |
| `GlassRangeSlider` / капсульный трек | `PanelSlider` | трек-капсула, оранжевая заливка, бегунок-капсула |

## Состав `ui/components`

- `VapSheet(onDismiss, isDark, …)` — шторка в отдельном окне (всегда поверх док-панелей), закрывается скримом, «назад» и свайпом; `onClosing` вызывается сразу, `onDismiss` — после ухода.
- Панель: `PanelHeader` (заголовок + капсула «Закрыть»), `PanelTabs`, `PanelBody` (прокрутка), `PanelFooter` (кнопки на всю ширину).
- Элементы: `PanelRow`, `PanelCard`, `PanelSwitchRow`, `PanelSliderCard`/`PanelSlider`, `PanelTextField`, `PanelPill`/`PanelPillRow`, `PanelButton` (главная — белая/графитовая капсула, `danger` — отдельная красная пара), `PanelProgress`, `PanelBusy`, `PanelHint`, `PanelError`.
- Палитра `PanelColors` темозависима (`LocalPanelDark`): плеер — тёплый графит `#17130F` с белым текстом; библиотека — кремовая основа приложения с графитовым текстом, текст акцента на светлом `#8F2D00` (белое на `#E85002` даёт 3,76:1, мало для мелкого текста; правило из `Design.md` §3).

## Правила, которые соблюдены

- Выбранное отмечено не только цветом: белая «пилюля» и точка-индикатор у строк.
- Подписи полей всегда видимы (placeholder их не заменяет); у ползунков описание = подпись, значение доступно скринридеру.
- Вертикальный жест над ползунком прокручивает панель, ползунок реагирует на тап и горизонтальное перетаскивание.
- В альбомной ориентации списки (вкладка «Текст») прокручиваются целиком вместе с элементами управления; форма правки реплики держит кнопки в закреплённом футере; таймкод при открытой клавиатуре сворачивается в одну строку.

## Что осталось от Material 3

Только `Text`, `Icon` и `MaterialTheme` как хранилище цветов/типографики (Collection использует их так же). Системные выборы файлов остаются системными.

## Проверка

Xiaomi 13: визуальный осмотр шторок и инструментальные наборы; итоговый список пройденных наборов — в `plan.md`. Тесты, которые опирались на устройство M3-диалогов (жесты прокрутки, момент появления), обновлены под шторки; проверки не ослаблены.
