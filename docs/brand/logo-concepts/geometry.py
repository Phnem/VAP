"""Геометрия концептов знака VAP: общая сетка 108x108 (adaptive icon), контуры через shapely."""
from dataclasses import dataclass, replace
from math import hypot

from shapely import affinity
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import unary_union

GRID = 108
CENTER = 54.0
SAFE_R = 33.0       # безопасная зона adaptive icon: круг диаметром 66dp
FIT_R = 31.5        # знак вписывается в круг чуть меньше безопасной зоны (у Vetro 29.5: буквам нужно больше места)
Q = 8               # сегментов на четверть окружности
MONO_GAP = 2.6      # зазор между слоями в монохроме


@dataclass(frozen=True)
class Layer:
    role: str       # ink | accent | soft | paper | glass | edge | hi | shadow
    g: object
    mono: str = "draw"   # draw | cut (вырез из нижних слоёв) | merge (рисуется без зазора) | skip


def circ(cx, cy, r):
    return Point(cx, cy).buffer(r, quad_segs=Q)


def stroke(pts, w, cap="round"):
    return LineString(pts).buffer(w / 2, cap_style=cap, join_style="round", quad_segs=Q)


def rrect(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    return box(x + r, y + r, x + w - r, y + h - r).buffer(r, quad_segs=Q)


def soft(g, r):
    """Скругляет выпуклые углы радиусом r."""
    return g.buffer(-r, join_style="round", quad_segs=Q).buffer(r, join_style="round", quad_segs=Q)


def tri(pts, r):
    return soft(Polygon(pts), r)


def rot(g, a, o=(0, 0)):
    return affinity.rotate(g, a, origin=o)


def mv(g, dx, dy):
    return affinity.translate(g, dx, dy)


def polys(g):
    if g.is_empty:
        return []
    if g.geom_type == "Polygon":
        return [g]
    if hasattr(g, "geoms"):
        return [p for x in g.geoms for p in polys(x)]
    return []


def _n(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def path_d(g):
    out = []
    for p in polys(g.simplify(0.02)):
        for ring in [p.exterior, *p.interiors]:
            c = list(ring.coords)[:-1]
            out.append("M" + " L".join(f"{_n(x)},{_n(y)}" for x, y in c) + "Z")
    return " ".join(out)


def fit(layers, R=FIT_R):
    """Центрирует по bbox и масштабирует так, чтобы самая дальняя точка лежала на круге R."""
    allg = unary_union([l.g for l in layers])
    x0, y0, x1, y1 = allg.bounds
    dx, dy = CENTER - (x0 + x1) / 2, CENTER - (y0 + y1) / 2
    moved = [replace(l, g=mv(l.g, dx, dy)) for l in layers]
    far = max(hypot(x - CENTER, y - CENTER)
              for l in moved for p in polys(l.g) for x, y in p.exterior.coords)
    s = R / far
    out = [replace(l, g=affinity.scale(l.g, s, s, origin=(CENTER, CENTER))) for l in moved]
    return out, s


def mono_geom(layers, gap=MONO_GAP):
    """Одноцветная версия: верхние слои отступают от нижних на gap, cut вырезает без отступа."""
    out, above = [], None
    for l in reversed(layers):
        if l.mono == "skip":
            continue
        if l.mono == "cut":
            above = l.g if above is None else above.union(l.g)
            continue
        g = l.g if above is None else l.g.difference(above)
        out.append(g)
        if l.mono == "merge":
            continue
        grown = l.g.buffer(gap, quad_segs=Q)
        above = grown if above is None else above.union(grown)
    return unary_union(out)


def bounds_of(layers):
    return unary_union([l.g for l in layers]).bounds


# ---------------------------------------------------------------- буквы

def L_V(w, h, t, rad):
    a, c = t * 1.05, t * 0.5
    s = (w / 2 - c) / h
    yi = (w / 2 - a) / s
    return soft(Polygon([(0, 0), (a, 0), (w / 2, yi), (w - a, 0), (w, 0), (w / 2 + c, h), (w / 2 - c, h)]), rad)


def L_A(w, h, t, rad):
    a, c = t * 1.05, t * 0.45
    s = (w / 2 - c) / h
    yi = h - (w / 2 - a) / s
    return soft(Polygon([(w / 2 - c, 0), (w / 2 + c, 0), (w, h), (w - a, h), (w / 2, yi), (a, h), (0, h)]), rad)


def L_P(w, h, t, rad):
    """Возвращает (тело, (центр и радиус выреза-солнца))."""
    bh = h * 0.62
    body = unary_union([rrect(0, 0, t, h, rad), rrect(0, 0, w, bh, bh * 0.46)])
    body = body.buffer(0.7, quad_segs=Q).buffer(-0.7, quad_segs=Q)
    hr = min(bh * 0.28, (w - t) * 0.38)
    cx, cy = (t + w) / 2, bh / 2
    return body.difference(circ(cx, cy, hr)), (cx, cy, hr)


def letter_row(h, block, x0=0.0, y0=0.0, tail_sun=False):
    """V A P в ряд. block=True: общая горизонтальная прорезь сквозь все буквы."""
    t, g = 0.27 * h, 0.12 * h
    rad = 0.06 * h if block else 0.11 * h
    wv, wa, wp = 0.80 * h, 0.84 * h, 0.74 * h
    V = mv(L_V(wv, h, t, rad), x0, y0)
    xa = x0 + wv + g
    A = mv(L_A(wa, h, t, rad), xa, y0)
    xp = xa + wa + g
    P, (cx, cy, hr) = L_P(wp, h, t, rad)
    P = mv(P, xp, y0)
    ink = unary_union([V, A, P])
    end = xp + wp
    if block:
        sl = 0.07 * h
        yy = y0 + 0.46 * h
        ink = ink.difference(box(x0 - 1, yy - sl / 2, end + 1, yy + sl / 2))
    layers = [Layer("ink", ink), Layer("accent", circ(xp + cx, y0 + cy, hr * 0.7))]
    if tail_sun:
        r = h * 0.46
        sun = circ(end + g * 1.6 + r, y0 + h / 2, r)
        if block:
            for k in (0.52, 0.72):
                sun = sun.difference(box(end, y0 + k * h - 0.035 * h, end + 4 * h, y0 + k * h + 0.035 * h))
        layers.append(Layer("accent", sun))
    return layers


# ---------------------------------------------------------------- концепты

def c01():
    return letter_row(24, True)


def c02():
    s, gp = 23.0, 4.0
    t = 0.27 * s
    rad = 0.06 * s
    V = L_V(s, s, t, rad)
    A = mv(L_A(s, s, t, rad), s + gp, 0)
    Pb, (cx, cy, hr) = L_P(0.8 * s, s, t, rad)
    px = (s - 0.8 * s) / 2
    P = mv(Pb, px, s + gp)
    sl = 0.07 * s
    ink = unary_union([V, A, P])
    for row in (0, s + gp):
        yy = row + 0.46 * s
        ink = ink.difference(box(-1, yy - sl / 2, 2 * s + gp + 1, yy + sl / 2))
    sun = circ(s + gp + s / 2, s + gp + s / 2, s * 0.5)
    for k in (0.52, 0.74):
        yy = s + gp + k * s
        sun = sun.difference(box(s + gp - 1, yy - sl / 2, 2 * s + gp + 1, yy + sl / 2))
    return [Layer("ink", ink), Layer("accent", circ(px + cx, s + gp + cy, hr * 0.7)), Layer("accent", sun)]


def c03():
    stem = rrect(0, 0, 12, 54, 6)
    sun = circ(12 + 3.5 + 15, 15, 15)
    return [Layer("ink", stem), Layer("accent", sun)]


def c04():
    v = tri([(0, 0), (52, 0), (26, 46)], 8)
    play = tri([(20.5, 6), (20.5, 26.5), (36, 16.2)], 2.2)
    return [Layer("ink", v), Layer("accent", play, "cut")]


def c05():
    play = tri([(22.5, 26.5), (22.5, 41), (35, 33.8)], 1.8)
    back = unary_union([rrect(0, 8, 56, 40, 7), rrect(0, 0, 24, 16, 5)]).difference(play)
    paper = rot(rrect(22, 1, 30, 32, 4), 7, (37, 17)).difference(play)
    front = rrect(0, 19, 56, 29, 7).difference(play)
    return [Layer("ink", back), Layer("soft", paper), Layer("accent", front)]


def c06():
    def sheet(a):
        return rot(rrect(-13, -38, 26, 38, 5), a, (0, 0))
    play = tri([(-5, -27), (-5, -10), (8, -18.5)], 2.0)
    mid = sheet(0).difference(play)
    return [Layer("ink", sheet(-26)), Layer("soft", sheet(26)), Layer("accent", mid),
            Layer("paper", play, "cut")]


def c07():
    plate = rrect(0, 10, 46, 46, 12)
    sun = circ(40, 12, 15)
    shadow = mv(plate, 1.2, 2.6)
    edge = plate.difference(plate.buffer(-1.3, quad_segs=Q))
    inner = plate.buffer(-2.4, quad_segs=Q)
    wide = Polygon([(-2, 40), (14, 10), (23, 10), (7, 40)]).intersection(inner)
    thin = Polygon([(12, 40), (26, 10), (29, 10), (15, 40)]).intersection(inner)
    return [Layer("shadow", shadow, "skip"), Layer("accent", sun), Layer("glass", plate),
            Layer("edge", edge, "skip"), Layer("hi", unary_union([wide, thin]), "cut")]


def c08():
    w, gp = 7.0, 3.5
    hs = [42, 30, 18, 30, 42]
    layers = []
    for i, h in enumerate(hs):
        x = i * (w + gp)
        layers.append(Layer("accent" if i == 2 else "ink", rrect(x, 42 - h, w, h, w / 2)))
    return layers


def c09():
    w, h, t = 44.0, 48.0, 9.0
    a = stroke([(0, h + 4), (w / 2, -2), (w, h + 4)], t, "flat").intersection(box(-10, -2, w + 10, h))
    a = soft(a, 1.6)
    by, bh = 30.0, 7.5
    seg = a.convex_hull.intersection(box(-5, by, w + 5, by + bh)).difference(a)
    sx0, _, sx1, _ = seg.bounds
    kx = sx0 + 0.40 * (sx1 - sx0)
    prog = seg.intersection(box(-5, by - 1, kx, by + bh + 1))
    track = seg.difference(prog)
    knob = circ(kx, by + bh / 2, 5.2)
    return [Layer("ink", a), Layer("soft", track, "merge"), Layer("accent", prog, "merge"), Layer("ink", knob)]


def c10():
    outer, inner = rrect(0, 0, 56, 40, 9), rrect(7, 7, 42, 26, 3.5)
    ring = outer.difference(inner)
    corners = unary_union([box(-1, -1, 17, 17), box(39, -1, 57, 17), box(-1, 23, 17, 41), box(39, 23, 57, 41)])
    br = soft(ring.intersection(corners), 2.4)
    play = tri([(22.5, 11.5), (22.5, 28.5), (36.5, 20)], 2.0)
    return [Layer("ink", br), Layer("accent", play)]
