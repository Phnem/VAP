"""Сводка замера: gfxinfo + трасса atrace (папка с файлами, которые собрал measure-device.sh).

    python tools/perf/analyze.py tools/perf/out/<прогон> [--interval 8.33]

Что считает:
- gfxinfo: число кадров, доля «медленных», перцентили кадра и GPU;
- переходы: кадры RenderThread (DrawFrames) группируются по паузам > 0,25 с; для каждой группы — число кадров,
  длительность, частота. «Кадров на переход» и частота в переходе — главная метрика сравнения сборок;
- RenderThread: перцентили, число кадров дольше интервала дисплея (8,33 мс для 120 Гц) и дольше 16,7 мс;
- главный поток: Choreographer#doFrame перцентили и число кадров дольше 16,7 мс;
- загрузки текстур в GPU (`Texture upload`) — норма после оптимизаций: единицы;
- тяжёлые кадры: число проходов рендера (`OpsTask::onExecute`) >= 8 — окна размытия стекла.
Метрики, которых нет в трассе (другой графический бэкенд), просто пропускаются.
"""
import argparse
import collections
import os
import re
import sys

HDR = re.compile(r'^\s*(.+?)-(\d+)\s+\(\s*(\d+|-+)\).*?\s(\d+\.\d+):\s+tracing_mark_write:\s+(.*)$')


def pct(sorted_values, q):
    return sorted_values[min(len(sorted_values) - 1, int(len(sorted_values) * q))] if sorted_values else float("nan")


def gfxinfo(path):
    if not os.path.isfile(path):
        return
    text = open(path, errors="ignore").read()
    print("== gfxinfo ==")

    def one(pattern):
        m = re.search(pattern, text)
        return m.group(1) if m else "?"

    print(f"  кадров: {one(r'Total frames rendered: (\d+)')}; janky: {one(r'Janky frames: (\d+ \([\d.]+%\))')}; "
          f"janky (legacy): {one(r'Janky frames \(legacy\): (\d+ \([\d.]+%\))')}")
    print("  кадр:  p50 {} p90 {} p95 {} p99 {}".format(*[one(rf'{q}th percentile: (\d+ms)') for q in (50, 90, 95, 99)]))
    print("  GPU:   p50 {} p90 {} p95 {} p99 {}".format(*[one(rf'{q}th gpu percentile: (\d+ms)') for q in (50, 90, 95, 99)]))
    for line in text.splitlines():
        if line.startswith("Number "):
            print("  " + line.strip())


def trace(out, interval):
    path = os.path.join(out, "trace.txt")
    if not os.path.isfile(path) or os.path.getsize(path) < 2000:
        print("== трасса == недоступна (atrace не отработал): есть только gfxinfo")
        return
    lines = open(path, errors="ignore").read().splitlines()
    pid = None
    pid_file = os.path.join(out, "pid.txt")
    if os.path.isfile(pid_file):
        pid = open(pid_file).read().split()[0:1]
        pid = pid[0] if pid else None
    if not pid:
        counts = collections.Counter(re.findall(r'B\|(\d+)\|DrawFrames', "\n".join(lines)))
        pid = counts.most_common(1)[0][0] if counts else None
    if not pid:
        print("== трасса == в ней нет кадров приложения")
        return
    stacks = collections.defaultdict(list)
    slices = collections.defaultdict(list)
    comm = {}
    for line in lines:
        m = HDR.match(line)
        if not m:
            continue
        c, tid, _, ts, msg = m.groups()
        ts = float(ts)
        if msg.startswith("B|"):
            parts = msg.split("|", 3)
            if parts[1] != pid:
                continue
            name = parts[2] if len(parts) > 2 else ""
            stacks[tid].append((name, ts, len(stacks[tid])))
            comm[tid] = c
        elif msg.startswith("E") and stacks[tid]:
            name, t0, depth = stacks[tid].pop()
            slices[tid].append((t0, ts, name, depth))
    render = [t for t, c in comm.items() if c.startswith("RenderThread")]
    if not render:
        print("== трасса == RenderThread приложения не найден (pid мог смениться)")
        return
    rt = slices[render[0]]
    frames = sorted(s for s in rt if s[2].startswith("DrawFrames"))
    print("== трасса ==")
    if not frames:
        print("  нет кадров DrawFrames")
        return
    uploads = sum(1 for s in rt if s[2].startswith("Texture upload"))
    rows = []
    for f in frames:
        inner = [s for s in rt if s[0] >= f[0] and s[1] <= f[1] and s[3] > f[3]]
        ops = sum(1 for s in inner if "OpsTask::onExecute" in s[2])
        rows.append(((f[1] - f[0]) * 1000, ops, f[0], f[1]))
    durations = sorted(r[0] for r in rows)
    # переходы
    clusters, cur = [], [rows[0]]
    for r in rows[1:]:
        if r[2] - cur[-1][3] > 0.25:
            clusters.append(cur)
            cur = [r]
        else:
            cur.append(r)
    clusters.append(cur)
    clusters = [c for c in clusters if len(c) >= 6]
    if clusters:
        n = len(clusters)
        mean_frames = sum(len(c) for c in clusters) / n
        spans = [(c[-1][3] - c[0][2]) * 1000 for c in clusters]
        fps = [len(c) / (s / 1000) for c, s in zip(clusters, spans) if s > 0]
        print(f"  переходов (группы кадров): {n}; кадров на переход: {mean_frames:.1f}; "
              f"длительность: {sum(spans) / n:.0f} мс; частота в переходе: {sum(fps) / len(fps):.0f} кадров/с")
    print(f"  RenderThread DrawFrames n={len(rows)}: p50 {pct(durations, .5):.1f} p90 {pct(durations, .9):.1f} "
          f"p99 {pct(durations, .99):.1f} max {durations[-1]:.1f} мс")
    over = sum(1 for d in durations if d > interval)
    over2 = sum(1 for d in durations if d > 16.7)
    print(f"  дольше {interval:.1f} мс: {over} ({100 * over / len(durations):.0f} %); дольше 16,7 мс: {over2}")
    heavy = [r for r in rows if r[1] >= 8]
    if any(r[1] for r in rows):
        light = [r for r in rows if r[1] < 8]
        print(f"  тяжёлых кадров (>=8 проходов рендера): {len(heavy)}"
              + (f", в среднем {sum(r[0] for r in heavy) / len(heavy):.1f} мс" if heavy else "")
              + f"; лёгких: {len(light)}")
    print(f"  загрузок текстур в GPU (Texture upload): {uploads}")
    main_do = sorted((s[1] - s[0]) * 1000 for s in slices[pid] if s[2].startswith("Choreographer#doFrame")) if pid in slices else []
    if main_do:
        print(f"  главный поток doFrame n={len(main_do)}: p50 {pct(main_do, .5):.1f} p90 {pct(main_do, .9):.1f} "
              f"max {main_do[-1]:.1f} мс; дольше 16,7 мс: {sum(1 for d in main_do if d > 16.7)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--interval", type=float, default=8.33)
    args = ap.parse_args()
    info = os.path.join(args.out, "info.txt")
    if os.path.isfile(info):
        print(open(info, errors="ignore").read().rstrip())
        print()
    gfxinfo(os.path.join(args.out, "gfxinfo.txt"))
    trace(args.out, args.interval)


if __name__ == "__main__":
    sys.exit(main())
