"""Экспорт выбранного концепта (04 V-Play) в ресурсы Android и Kotlin.

Запуск из этой папки:  python export_android.py
Пишет: res/drawable/ic_launcher_{foreground,monochrome,background}.xml, drawable-night/ic_launcher_background.xml,
res/values{,-night}/colors.xml (цвета иконки) и константы контуров в ui/brand/VapWordmark.kt.
"""
import os
import re

import build
import geometry as G
from shapely import affinity

APP = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", "app", "src", "main"))
RES = os.path.join(APP, "res")
KT = os.path.join(APP, "java", "com", "phnem", "vap", "ui", "brand", "VapWordmark.kt")


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def vec(w, h, vw, vh, body, extra=""):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"{extra}\n'
            f'    android:width="{w}dp"\n    android:height="{h}dp"\n    android:viewportWidth="{vw}"\n    android:viewportHeight="{vh}">\n{body}</vector>\n')


def vpath(color, d, indent="    "):
    return (f'{indent}<path\n{indent}    android:fillColor="{color}"\n{indent}    android:fillType="evenOdd"\n'
            f'{indent}    android:pathData="{d}" />\n')


c = next(c for c in build.CONCEPTS if c["id"] == "04")
layers, _ = G.fit(c["fn"]())
v, play = layers[0].g, layers[1].g
mono = G.mono_geom(layers)

write(f"{RES}/drawable/ic_launcher_foreground.xml",
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
      "<!-- Знак VAP «V-Play» (docs/brand/logo-concepts, вариант 04): буква V треугольником, внутри play. Цвета: values / values-night. -->\n"
      + vec(108, 108, 108, 108, vpath("@color/ic_launcher_mark", G.path_d(v)) + vpath("@color/ic_launcher_play", G.path_d(play)))[len('<?xml version="1.0" encoding="utf-8"?>\n'):])
write(f"{RES}/drawable/ic_launcher_monochrome.xml",
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
      "<!-- Тематическая иконка (Android 13+): тот же знак, play вырезан. -->\n"
      + vec(108, 108, 108, 108, vpath("#FF000000", G.path_d(mono)))[len('<?xml version="1.0" encoding="utf-8"?>\n'):])
write(f"{RES}/drawable/ic_launcher_background.xml", vec(108, 108, 108, 108, vpath("#FFEDE2D4", "M0,0h108v108h-108z")))
write(f"{RES}/drawable-night/ic_launcher_background.xml", vec(108, 108, 108, 108,
      '    <path android:pathData="M0,0h108v108h-108z">\n        <aapt:attr name="android:fillColor">\n'
      '            <gradient\n                android:type="linear"\n                android:startX="0"\n                android:startY="0"\n'
      '                android:endX="0"\n                android:endY="108"\n                android:startColor="#FF1C1C1C"\n'
      '                android:endColor="#FF000000" />\n        </aapt:attr>\n    </path>\n',
      '\n    xmlns:aapt="http://schemas.android.com/aapt"'))

for p, mark in (("values", "#FF111111"), ("values-night", "#FFD9C3AB")):
    path = f"{RES}/{p}/colors.xml"
    t = open(path, encoding="utf-8").read()
    t = re.sub(r"\s*<color name=\"(vap_launcher_background|ic_launcher_mark|ic_launcher_play)\">[^<]*</color>", "", t)
    t = t.replace("</resources>", f'    <!-- Иконка «V-Play»: слой foreground; фон см. drawable / drawable-night/ic_launcher_background.xml -->\n'
                  f'    <color name="ic_launcher_mark">{mark}</color>\n    <color name="ic_launcher_play">#FFE85002</color>\n</resources>')
    write(path, t)

for name in ("ic_launcher", "ic_launcher_round"):
    write(f"{RES}/mipmap-anydpi-v26/{name}.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <background android:drawable="@drawable/ic_launcher_background" />\n'
          '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
          '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')

# вордмарк: одноцветный, обрезан по контуру, начало координат в (0,0)
wm = build.build_wordmark("04", layers)
wmono = G.mono_geom(wm)
x0, y0, x1, y1 = wmono.bounds
wmono = affinity.translate(wmono, -x0, -y0)
W, H = round(x1 - x0, 2), round(y1 - y0, 2)
d = G.path_d(wmono)
chunks = [d[i:i + 100] for i in range(0, len(d), 100)]
const = " +\n".join(f'    "{ch}"' for ch in chunks)
KOTLIN = """package com.phnem.vap.ui.brand

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Знак «VAP» (решение владельца: вариант 04 «V-Play», docs/brand/logo-concepts): буква V треугольником
 * с вырезанным play и слово VAP рядом. Контур одноцветный, цвет задаёт вызывающий.
 *
 * Контур сгенерирован `docs/brand/logo-concepts/export_android.py` (не править руками). Играет роль
 * векторного ресурса: `PathParser` разбирает его один раз, при отрисовке он масштабируется по высоте.
 * Прорезей через `BlendMode.Clear` больше нет, поэтому отдельный слой и растеризация в картинку не нужны.
 *
 * Знак не локализуется и не зеркалится в RTL.
 */
@Composable
fun VapWordmark(color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .aspectRatio(WORDMARK_W / WORDMARK_H)
            .drawWithCache {
                val k = size.height / WORDMARK_H
                onDrawBehind { scale(k, k, pivot = Offset.Zero) { drawPath(wordmarkPath, color) } }
            },
    )
}

private val wordmarkPath: Path by lazy {
    PathParser().parsePathString(WORDMARK_PATH).toPath().apply { fillType = PathFillType.EvenOdd }
}

private const val WORDMARK_W = __W__f
private const val WORDMARK_H = __H__f

private const val WORDMARK_PATH =
__PATH__
"""
write(KT, KOTLIN.replace("__W__", str(W)).replace("__H__", str(H)).replace("__PATH__", const))
print("wordmark", W, H, len(d))
