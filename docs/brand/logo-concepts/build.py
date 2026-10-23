"""Сборка концептов знака VAP: SVG (знак, adaptive-слои, вордмарк) и лист сравнения.

Запуск из этой папки:  python build.py
Нужен shapely (pip install shapely). Пишет mark/, adaptive/, wordmark/, sheet.html.
"""
import html
import os
import sys

import geometry as G
from geometry import Layer, fit, letter_row, mono_geom, path_d, unary_union

HERE = os.path.dirname(os.path.abspath(__file__))
SCRATCH_HTML = os.environ.get("VAP_SHEET_ARTIFACT")  # необязательно: копия листа без doctype для Artifact

# ---------------------------------------------------------------- палитры (роли -> цвет, непрозрачность)
PAL = {
    "light": dict(bg="#EDE2D4", panel="#F4EFE3", ink=("#111111", 1), accent=("#E85002", 1), soft=("#C9AE90", 1),
                  paper=("#FDF1D8", 1), glass=("#FFFFFF", .55), edge=("#111111", .38), hi=("#FFFFFF", .75),
                  shadow=("#111111", .18)),
    "dark": dict(bg="#0B0B0B", panel="#000000", ink=("#D9C3AB", 1), accent=("#E85002", 1), soft=("#8C7B67", 1),
                 paper=("#F3E8D8", 1), glass=("#FFFFFF", .16), edge=("#FFFFFF", .40), hi=("#FFFFFF", .38),
                 shadow=("#000000", .60)),
    "brand": dict(bg="#E85002", panel="#E85002", ink=("#FDF1D8", 1), accent=("#111111", 1), soft=("#F5A877", 1),
                  paper=("#FDF1D8", 1), glass=("#FFFFFF", .26), edge=("#FFFFFF", .60), hi=("#FFFFFF", .50),
                  shadow=("#5A1F00", .35)),
}
THEMED = {"tl": ("#FFDCC7", "#3A1800"), "td": ("#40200E", "#FFD9C4")}  # фон, глиф (эмуляция themed icon)

# ---------------------------------------------------------------- описания концептов
REC = "04"
CONCEPTS = [
    dict(id="01", slug="vap-row", fn=G.c01, family="Буквы", name="VAP-ряд",
         idea="Три блочные буквы в ряд, одна прорезь-«горизонт» проходит сквозь все буквы, в «P» вырезано солнце.",
         why="Прямое продолжение текущего знака v0 и референсов 1–3 из design-spec: блочные скруглённые модули, прорезь, круг в «P». Две краски. Солнце-точка как у Vetro.",
         risk="Самый широкий знак: в круг ⌀66 буквы выходят мелкими. На 24 px прорезь и солнце исчезают, остаётся «плотная плашка».",
         s=(2, 3, 3, 5, 4)),
    dict(id="02", slug="modules-2x2", fn=G.c02, family="Буквы", name="Модули 2×2",
         idea="Буквы V A / P и солнце в квадрате 2×2, как «JA / PAN» в референсе 1. Солнце в полоску из референса 4.",
         why="Квадратная раскладка занимает безопасный круг лучше ряда: буквы крупнее. Солнце в полоску — единственная геометрическая фигура, остальное буквы.",
         risk="Четыре элемента против двух у Vetro. Полосы солнца и прорези мельче 1 px на 24 px.",
         s=(3, 3, 3, 5, 4)),
    dict(id="03", slug="sun-p", fn=G.c03, family="Буквы", name="Солнце-P",
         idea="Ствол буквы «P» и круг-солнце рядом: буква не дорисована, недостающую часть заменяет солнце.",
         why="Тот же ход, что у Vetro (половина буквы + солнце, ic_launcher_foreground.xml), но на букве «P». Два объекта, два цвета, большой зазор — идеальный монохром.",
         risk="Читается как «P» только после объяснения; V и A в знаке нет. Из всех вариантов ближе всего к Vetro, сходство на полке launcher заметно.",
         s=(5, 5, 5, 3, 5)),
    dict(id="04", slug="v-play", fn=G.c04, family="Play", name="V-Play",
         idea="Буква V как мягкий треугольник вершиной вниз, внутри вырезан play. Одним жестом: буква и функция.",
         why="Один контур, один акцент. Вырез вместо второй формы даёт монохром без доработки. Крупные скругления и массивная форма держат 24 px. Связь с Vetro через «ink + accent».",
         risk="Треугольник с play легко спутать со стандартными видеоиконками. «A» и «P» в знаке не участвуют.",
         s=(5, 5, 4, 4, 5)),
    dict(id="05", slug="folder-play", fn=G.c05, family="Папка и листы", name="Папка-play",
         idea="Силуэт папки со вкладкой, из неё торчит лист, play вырезан сквозь всю папку.",
         why="Папка — главный объект VAP (карточка папки, сетка в design-spec §1.1). Лист из-за пластины повторяет «листы-бумагу» референса. Три цвета: ink, accent, soft.",
         risk="Похоже на значки файловых менеджеров. Вырез play на 24 px размером 1.5 px.",
         s=(3, 4, 4, 3, 4)),
    dict(id="06", slug="sheet-fan", fn=G.c06, family="Папка и листы", name="Веер листов",
         idea="Три листа веером из одной точки, на среднем вырезан play: файлы папки, разложенные как карточки.",
         why="Динамика, наклоны листов 26° как «повёрнутые листы» референса. Цвета листов различают слои.",
         risk="Три слоя; в монохроме слои держатся только на зазорах 2.6 dp, на 24 px они слипаются.",
         s=(3, 3, 3, 3, 3)),
    dict(id="07", slug="glass-plate", fn=G.c07, family="Материал и звук", name="Стеклянная плита",
         idea="Матовая плита с крупным скруглением, блик по диагонали, за плитой солнце просвечивает размытым пятном.",
         why="Единственный концепт, где знак — это материал самого интерфейса: плита с бликом и просвечивающим цветом, как передняя пластина папки.",
         risk="Нарушает правило «плоско, без эффектов»: полупрозрачность и блик не сохраняются в themed. Блик исчезает на 48 px. Надо проверять на разных обоях launcher.",
         s=(2, 2, 3, 2, 2)),
    dict(id="08", slug="eq-v", fn=G.c08, family="Материал и звук", name="Эквалайзер-V",
         idea="Пять столбцов-«капсул», верхний профиль образует V, центральный столбец — акцентный.",
         why="Звук — вторая половина VAP (аудиоинструменты, субтитры). Капсулы повторяют скругление чипов (радиус h/2). Монохром без вырезов.",
         risk="V читается слабо, скорее «аудиоиконка вообще». Без пояснения не говорит «VAP».",
         s=(4, 5, 3, 2, 5)),
    dict(id="09", slug="a-timeline", fn=G.c09, family="Play", name="A-таймлайн",
         idea="Тяжёлая «Λ», перекладина которой — полоса прогресса с ползунком.",
         why="Буква и функция проигрывателя сливаются так же, как у V-Play, но на «A». Прогресс-бар узнаваем на любом проигрывателе.",
         risk="В знаке нет V. Перекладина 7.5 dp на 24 px сливается с ножками, ползунок пропадает.",
         s=(3, 4, 3, 3, 4)),
    dict(id="10", slug="viewfinder", fn=G.c10, family="Play", name="Кадр",
         idea="Четыре угла видоискателя вокруг play: кадр, который воспроизводится.",
         why="Простейшая композиция: угловые скобки и один акцент. Хорошо делится в монохроме, широкая форма использует горизонталь.",
         risk="Похоже на «сканер», «скриншот» и «камеру». Без букв не узнать VAP.",
         s=(4, 5, 3, 3, 5)),
]
CRITERIA = ("24 px", "Монохром", "Стиль Vetro", "Узнаваемость VAP", "Простота внедрения")

PRINCIPLES = [
    ("Одна идея, один жест", "Знак Vetro — полуформа (левое плечо «V» одним слэбом) плюс солнце. Нет контуров-двойников, нет второй идеи (app/src/main/res/drawable/ic_launcher_foreground.xml)."),
    ("Солнце как единственная фигура", "Круг r=80 в системе знака (18.9 dp), диаметр 4/3 ширины плоской грани слэба (14.2 dp). Тот же круг — точка в «LIBRARY.» и в чипах баннера (images/promo/vetro-banner-16x9.png) и в design-spec VAP: «одна геометрическая фигура-солнце»."),
    ("Две краски и фон", "Светлая: #000000 + #C10801 на #EDE2D4. Тёмная: #D9C3AB + #E85002 на градиенте #1C1C1C→#000000 (res/values и values-night, colors.xml; drawable-night/ic_launcher_background.xml)."),
    ("Плоско, без эффектов", "В иконке нет теней, блика, обводки; единственный градиент — фон тёмной темы. Design.md запрещает цветной halo и жирную рамку: стекло живёт только в UI."),
    ("Крупно и мягко", "strokeLineJoin=round и штрих 40 при ширине слэба 80. Мелких деталей нет, каждая форма ≥ 1/10 знака."),
    ("Масштаб внутри безопасной зоны", "Знак 50×38 dp вписан в круг ⌀66: самая дальняя точка 29.5 dp. Занимает около 47% ширины холста 108 dp."),
    ("Монохром — те же формы", "ic_launcher_monochrome.xml повторяет геометрию, один цвет #000000. Слои разделены пустотой (≈9.7 dp между слэбом и солнцем), а не цветом. Тот же файл служит small icon уведомлений."),
    ("Цвета знака — ресурсы темы", "ink и accent берутся из @color/ic_launcher_mark и ic_launcher_sun, форма в обеих темах одна."),
    ("Связь со стеклом через радиусы и палитру", "Design.md: радиусы 2u / 3–4.5u / 6–7u, капсула h/2; стекло = tint + блик + контрсвет + тень. В иконке остаются радиусы и тёплая палитра."),
]


# ---------------------------------------------------------------- SVG
def fill_attr(role, pal):
    hexv, op = pal[role]
    return f'fill="{hexv}"' + (f' fill-opacity="{op}"' if op < 1 else "")


def paths(layers, pal):
    return "\n".join(f'  <path {fill_attr(l.role, pal)} fill-rule="evenodd" d="{path_d(l.g)}"/>'
                     for l in layers if not l.g.is_empty)


def svg_doc(vb, body, title, desc):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" width="{vb.split()[2]}" height="{vb.split()[3]}">\n'
            f'  <title>{html.escape(title)}</title>\n  <desc>{html.escape(desc)}</desc>\n{body}\n</svg>\n')


def build_wordmark(cid, layers):
    """Лента знака с буквами: для 01 и 02 буквы сами и есть знак."""
    if cid == "01":
        parts = letter_row(30, True)
    elif cid == "02":
        parts = letter_row(30, True, tail_sun=True)
    else:
        x0, y0, x1, y1 = G.bounds_of(layers)
        s = 46 / (y1 - y0)
        mark = [Layer(l.role, G.affinity.scale(G.mv(l.g, -x0, -y0), s, s, origin=(0, 0)), l.mono) for l in layers]
        mw = (x1 - x0) * s
        parts = mark + letter_row(30, False, x0=mw + 15, y0=(46 - 30) / 2)
    x0, y0, x1, y1 = G.bounds_of(parts)
    s = min(268 / (x1 - x0), 68 / (y1 - y0))
    dx, dy = 140 - s * (x0 + x1) / 2, 40 - s * (y0 + y1) / 2
    return [Layer(l.role, G.affinity.translate(G.affinity.scale(l.g, s, s, origin=(0, 0)), dx, dy), l.mono) for l in parts]


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def build_all():
    for c in CONCEPTS:
        layers, scale = fit(c["fn"]())
        c["layers"], c["scale"] = layers, scale
        c["mono"] = mono_geom(layers)
        c["wm"] = build_wordmark(c["id"], layers)
        far = max(G.hypot(x - 54, y - 54) for l in layers for p in G.polys(l.g) for x, y in p.exterior.coords)
        assert far <= G.SAFE_R, (c["id"], far)
        base = f'{c["id"]}-{c["slug"]}'
        L = PAL["light"]
        title = f'VAP {c["id"]} · {c["name"]}'
        write(f"{HERE}/mark/{base}.svg", svg_doc(
            "0 0 108 108", f'  <rect width="108" height="108" fill="{L["bg"]}"/>\n{paths(layers, L)}',
            title, c["idea"] + " Светлая палитра, фон иконки включён."))
        write(f"{HERE}/adaptive/{base}-foreground.svg", svg_doc(
            "0 0 108 108", paths(layers, L), title + " (foreground)",
            "Слой foreground adaptive icon на сетке 108dp. Знак внутри круга ⌀66 (безопасная зона). Фон: " + L["bg"] + " / тёмная тема #0B0B0B."))
        write(f"{HERE}/adaptive/{base}-monochrome.svg", svg_doc(
            "0 0 108 108", f'  <path fill="#000000" fill-rule="evenodd" d="{path_d(c["mono"])}"/>', title + " (monochrome)",
            "Слой monochrome (Android 13+ themed icon, small icon уведомлений): одна заливка, слои разделены зазором."))
        write(f"{HERE}/wordmark/{base}-wordmark.svg", svg_doc(
            "0 0 280 80", paths(c["wm"], L), title + " (wordmark)",
            "Горизонтальный знак с буквами VAP, переведёнными в контуры. Без шрифтов."))


# ---------------------------------------------------------------- лист сравнения
def role_css():
    out = []
    for name, p in PAL.items():
        decl = [f"--bg-i:{p['bg']}", f"--panel:{p['panel']}"]
        for role in ("ink", "accent", "soft", "paper", "glass", "edge", "hi", "shadow"):
            hexv, op = p[role]
            var = "accent-i" if role == "accent" else role
            if op < 1:
                r, g, b = int(hexv[1:3], 16), int(hexv[3:5], 16), int(hexv[5:7], 16)
                decl.append(f"--{var}:rgba({r},{g},{b},{op})")
            else:
                decl.append(f"--{var}:{hexv}")
        decl.append(f"--glyph:{p['ink'][0]}")
        out.append(f".cw-{name}{{{';'.join(decl)}}}")
    for k, (bg, fg) in THEMED.items():
        out.append(f".cw-{k}{{--tbg:{bg};--glyph:{fg};--panel:{bg}}}")
    return "\n".join(out)


def symbols():
    out = []
    for c in CONCEPTS:
        i = c["id"]
        body = "".join(f'<path class="r-{l.role}" fill-rule="evenodd" d="{path_d(l.g)}"/>' for l in c["layers"] if not l.g.is_empty)
        out.append(f'<symbol id="c{i}" viewBox="0 0 108 108">{body}</symbol>')
        out.append(f'<symbol id="m{i}" viewBox="0 0 108 108"><path class="gl" fill-rule="evenodd" d="{path_d(c["mono"])}"/></symbol>')
        wb = "".join(f'<path class="r-{l.role}" fill-rule="evenodd" d="{path_d(l.g)}"/>' for l in c["wm"] if not l.g.is_empty)
        out.append(f'<symbol id="w{i}" viewBox="0 0 280 80">{wb}</symbol>')
    out.append('<symbol id="guides" viewBox="0 0 108 108"><rect x="0.25" y="0.25" width="107.5" height="107.5"/>'
               '<rect x="18" y="18" width="72" height="72"/><circle cx="54" cy="54" r="33"/></symbol>')
    return "".join(out)


def tile(cid, cw, size, mono=False, guides=False):
    sym = f"m{cid}" if mono else f"c{cid}"
    g = '<use href="#guides" class="guides"/>' if guides else ""
    return (f'<div class="tile cw-{cw}" style="--s:{size}px"><svg viewBox="0 0 108 108" aria-hidden="true">'
            f'<use href="#{sym}"/>{g}</svg></div>')


def dots(n):
    return f'<span class="dots" role="img" aria-label="{n} из 5">' + "".join(
        f'<i class="{"on" if k < n else ""}"></i>' for k in range(5)) + "</span>"


def column(i, cw_big, cw_small, label, mono=False):
    return (f'<figure class="col"><div class="big">{tile(i, cw_big, 192, mono, guides=True)}</div>'
            f'<div class="small">{tile(i, cw_small, 96, mono)}{tile(i, cw_small, 48, mono)}{tile(i, cw_small, 24, mono)}</div>'
            f'<figcaption>{label}</figcaption></figure>')


def wordmark_panel(i, cw):
    return (f'<div class="wm cw-{cw}"><svg viewBox="0 0 280 80" aria-hidden="true"><use href="#w{i}"/></svg>'
            f'<svg class="wm-s" viewBox="0 0 280 80" aria-hidden="true"><use href="#w{i}"/></svg></div>')


def card(c):
    i = c["id"]
    rec = '<span class="badge">Рекомендация автора</span>' if i == REC else ""
    return f'''<article class="card{" is-rec" if i == REC else ""}" id="v{i}">
  <header class="card-head">
    <div class="num">{i}</div>
    <div class="titles"><span class="fam">{c["family"]}</span><h3>{c["name"]}</h3></div>
    {rec}
  </header>
  <p class="idea">{c["idea"]}</p>
  <div class="why"><p><b>Почему так.</b> {c["why"]}</p><p><b>Риск.</b> {c["risk"]}</p></div>
  <div class="cols">
    {column(i, "light", "light", "Светлая тема")}
    {column(i, "dark", "dark", "Тёмная тема")}
    {column(i, "brand", "brand", "Фирменный оранжевый")}
    {column(i, "tl", "td", "Монохром (themed): светлая / тёмная", True)}
  </div>
  <div class="wms">{wordmark_panel(i, "light")}{wordmark_panel(i, "dark")}{wordmark_panel(i, "brand")}</div>
</article>'''


def sheet_html(wrap):
    css = open(f"{HERE}/sheet.css", encoding="utf-8").read().replace("/*ROLES*/", role_css())
    js = open(f"{HERE}/sheet.js", encoding="utf-8").read()
    rows = "".join(
        f'<tr><th scope="row"><a href="#v{c["id"]}"><b>{c["id"]}</b> {c["name"]}</a></th>' +
        "".join(f"<td>{dots(v)}</td>" for v in c["s"]) + "</tr>" for c in CONCEPTS)
    head = "".join(f"<th>{h}</th>" for h in CRITERIA)
    pr = "".join(f'<li><b>{t}</b><span>{d}</span></li>' for t, d in PRINCIPLES)
    rec = next(c for c in CONCEPTS if c["id"] == REC)
    body = f'''<svg class="defs" width="0" height="0" aria-hidden="true"><defs>{symbols()}</defs></svg>
<main class="page">
  <header class="top">
    <p class="eyebrow">VAP · знак и иконка</p>
    <h1>Десять способов нарисовать VAP</h1>
    <p class="lead">Каждый вариант собран по правилам знака Vetro: одна идея, две краски и фон, крупные скруглённые формы, монохром без потери смысла. Выбирает владелец. В приложение до ответа ничего не внедряется.</p>
    <div class="controls" role="group" aria-label="Вид листа">
      <span>Маска иконки</span>
      <button type="button" data-mask="28%" aria-pressed="true">Скруглённый квадрат</button>
      <button type="button" data-mask="50%" aria-pressed="false">Круг</button>
      <button type="button" data-mask="8%" aria-pressed="false">Квадрат</button>
      <label class="chk"><input type="checkbox" id="guides-toggle"> Сетка 108 dp и безопасная зона ⌀66</label>
    </div>
  </header>
  <aside class="rec" aria-label="Рекомендация">
    <div class="rec-tile">{tile(rec["id"], "light", 96)}{tile(rec["id"], "dark", 96)}</div>
    <div><h2>Рекомендую {rec["id"]} · {rec["name"]}</h2>
    <p>RECTEXT</p></div>
  </aside>
  <section aria-labelledby="pr-h"><h2 id="pr-h">Принципы знака Vetro, по которым сделаны варианты</h2><ol class="principles">{pr}</ol></section>
  <section aria-labelledby="cmp-h"><h2 id="cmp-h">Сравнение</h2>
    <div class="table-wrap"><table class="cmp"><thead><tr><th>Вариант</th>{head}</tr></thead><tbody>{rows}</tbody></table></div>
    <p class="note">Оценки автора, субъективные: 5 — лучше всего. «Простота внедрения» — сколько работы в VectorDrawable и ресурсах.</p></section>
  <section aria-labelledby="all-h"><h2 id="all-h">Варианты</h2>
  <div class="cards">{"".join(card(c) for c in CONCEPTS)}</div></section>
  <footer class="foot"><p>Ответьте номером варианта. SVG, слои adaptive и вордмарки лежат в <code>docs/brand/logo-concepts/</code>. После выбора: заменить <code>ui/brand/VapWordmark.kt</code>, иконку launcher и проверить на устройстве.</p></footer>
</main>
<script>{js}</script>'''
    body = body.replace("RECTEXT", REC_TEXT)
    style = f"<style>{css}</style>"
    meta = '<meta name="claude:viewport-fit-sides" content="cover">'
    title = "<title>Знак VAP</title>"
    fonts = ('<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Unbounded:wght@500;700'
             '&family=Onest:wght@400;500;600&display=swap">')
    if wrap:
        return (f'<!doctype html>\n<html lang="ru"><head><meta charset="utf-8">'
                f'<meta name="viewport" content="width=device-width,initial-scale=1">{title}{meta}{fonts}{style}</head>'
                f'<body>\n{body}\n</body></html>\n')
    return f"{title}\n{meta}\n{fonts}\n{style}\n{body}\n"


REC_TEXT = ("Одна форма и один вырез: читается на 24 px, в монохроме не теряет смысла, повторяет приём Vetro "
            "(знак + один акцент), но не копирует его. Запасные: 03 «Солнце-P» (ближе всего к духу Vetro) "
            "и 05 «Папка-play» (самый «про приложение»).")

if __name__ == "__main__":
    build_all()
    write(f"{HERE}/sheet.html", sheet_html(True))
    if SCRATCH_HTML:
        write(SCRATCH_HTML, sheet_html(False))
    for c in CONCEPTS:
        print(c["id"], c["name"], "scale", round(c["scale"], 2))
