"""Снимки VAP для сайта Vetro Studio: телефон -> 720x1560 WebP под рамку-мокап. Инструкция: docs/site-screenshots.md.

    python tools/site-shots.py begin                       # экран 1080x2340 (6:13), демо-статусбар 9:41
    python tools/site-shots.py shot vap-player             # снимок -> captures/site/raw/vap-player.png + captures/site/vap-player.webp
    python tools/site-shots.py end                         # вернуть размер экрана и статусбар (безопасно вызывать всегда)
    python tools/site-shots.py convert raw.png vap-player  # только конвертация готового PNG
    python tools/site-shots.py check                       # размеры, имена и вес файлов в captures/site

Нужен Pillow (pip install pillow). Телефон по умолчанию тот же, что у остальных tools/*.ps1 (3871a9d6).
"""
import argparse
import io
import os
import re
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

W, H = 720, 1560                    # то, что ждут <img width height> на сайте; пропорция 6:13 = aspect-ratio .phone-screen
EXPECTED = ("vap-player", "vap-library", "vap-subtitles", "vap-ai")   # имена, которые сайт подхватывает сам
MAX_KB = 350
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "captures", "site")

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("cmd", choices=["begin", "shot", "end", "convert", "check"])
ap.add_argument("args", nargs="*")
ap.add_argument("--serial", default="3871a9d6")
ap.add_argument("--adb", default=os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"))
ap.add_argument("--out", default=OUT)
ap.add_argument("--quality", type=int, default=88)
ap.add_argument("--fix-status", type=int, default=0, metavar="PX",
                help="высота статусбара в px оригинала (Xiaomi 13: 105): закрасить его и нарисовать 9:41 + батарею, "
                     "если оболочка телефона игнорирует демо-режим")
a = ap.parse_args()


def adb(*cmd, binary=False):
    r = subprocess.run([a.adb, "-s", a.serial, *cmd], capture_output=True)
    if r.returncode != 0:
        sys.exit(f"adb {' '.join(cmd)} failed: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def demo(*extra):
    adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *extra)


def valid_name(n):
    if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", n):
        sys.exit(f"bad name '{n}': lowercase letters, digits and dashes only (e.g. vap-player)")
    return n


def fix_status(img, px):
    """Статусбар -> чистая полоса того же цвета (копия строки под ним) с часами 9:41 и батареей, как на макетах сайта."""
    w, h = img.size
    below = img.crop((0, px + 2, w, px + 3))
    img.paste(below.resize((w, px)), (0, 0))
    avg = sum(img.crop((0, 0, w, px)).resize((1, 1)).getpixel((0, 0))) / 3
    ink = (255, 255, 255) if avg < 140 else (20, 20, 20)
    d = ImageDraw.Draw(img)
    font = ImageFont.load_default(size=round(px * 0.44))
    d.text((round(w * 0.065), px * 0.5), "9:41", fill=ink, font=font, anchor="lm")
    bw, bh = round(px * 0.5), round(px * 0.24)           # батарея 100 %: контур, заливка, «носик»
    x, y = round(w * 0.935) - bw, round(px * 0.5 - bh / 2)
    d.rounded_rectangle((x, y, x + bw, y + bh), radius=bh // 4, outline=ink, width=max(2, bh // 9))
    d.rounded_rectangle((x + 4, y + 4, x + bw - 4, y + bh - 4), radius=bh // 6, fill=ink)
    d.rectangle((x + bw + 2, y + bh // 3, x + bw + 5, y + bh * 2 // 3), fill=ink)
    return img


def convert(img, name):
    """Любой PNG телефона -> точные 6:13 (лишнее срезается снизу: сверху статусбар) -> 720x1560 WebP."""
    img = img.convert("RGB")
    if a.fix_status:
        img = fix_status(img, a.fix_status)
    w, h = img.size
    want_h = round(w * H / W)
    if h > want_h:
        img = img.crop((0, 0, w, want_h))
    elif h < want_h:
        want_w = round(h * W / H)
        left = (w - want_w) // 2
        img = img.crop((left, 0, left + want_w, h))
    img = img.resize((W, H), Image.LANCZOS)
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, valid_name(name) + ".webp")
    img.save(path, "WEBP", quality=a.quality, method=6)
    kb = os.path.getsize(path) / 1024
    print(f"{path}  {W}x{H}  {kb:.0f} KB" + ("  <- тяжелее нормы, понизьте --quality" if kb > MAX_KB else ""))


if a.cmd == "begin":
    size = re.search(r"Physical size: (\d+)x(\d+)", adb("shell", "wm", "size"))
    if not size:
        sys.exit("cannot read the screen size")
    w, h = map(int, size.groups())
    target = round(w * H / W)
    if h != target:
        adb("shell", "wm", "size", f"{w}x{target}")
    print(f"screen {w}x{h} -> {w}x{target} (back with: end)")
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    demo("-e", "command", "enter")
    demo("-e", "command", "clock", "-e", "hhmm", "0941")
    demo("-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false")
    demo("-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
    demo("-e", "command", "network", "-e", "mobile", "show", "-e", "datatype", "none", "-e", "level", "4", "-e", "fully", "true")
    demo("-e", "command", "notifications", "-e", "visible", "false")
    print("demo status bar sent (9:41, full battery, Wi-Fi). Some skins ignore it: look at the first shot.")
    print("The activity restarts after the size change: open the screen you want AFTER this step.")
elif a.cmd == "shot":
    name = valid_name(a.args[0] if a.args else sys.exit("usage: shot <name>"))
    png = adb("exec-out", "screencap", "-p", binary=True)
    raw = os.path.join(a.out, "raw")
    os.makedirs(raw, exist_ok=True)
    with open(os.path.join(raw, name + ".png"), "wb") as f:
        f.write(png)
    convert(Image.open(io.BytesIO(png)), name)
elif a.cmd == "end":
    adb("shell", "wm", "size", "reset")
    demo("-e", "command", "exit")
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "0")
    print("screen size and status bar restored")
elif a.cmd == "convert":
    if len(a.args) != 2:
        sys.exit("usage: convert <file.png> <name>")
    convert(Image.open(a.args[0]), a.args[1])
else:  # check
    bad = 0
    present = set()
    for fn in sorted(os.listdir(a.out)) if os.path.isdir(a.out) else []:
        if not fn.endswith(".webp"):
            continue
        p = os.path.join(a.out, fn)
        size = Image.open(p).size
        kb = os.path.getsize(p) / 1024
        ok = size == (W, H) and kb <= MAX_KB
        bad += not ok
        present.add(fn[:-5])
        print(("ok   " if ok else "BAD  ") + f"{fn}  {size[0]}x{size[1]}  {kb:.0f} KB")
    for n in EXPECTED:
        if n not in present:
            print(f"--   {n}.webp not taken yet (the site keeps its placeholder)")
    sys.exit(1 if bad else 0)
