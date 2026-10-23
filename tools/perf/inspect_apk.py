"""Какая сборка VAP установлена: по строкам в dex определяем этап оптимизаций и вариант (foss/cast).

Метки: строки меток Trace не обфусцируются R8. `VAP.fs.files` — есть метки этапа 4,
`VAP.prepareOpen` — есть этап 4б (подготовка открытия папки вне главного потока).
"""
import sys
import zipfile

MARKERS = [
    (b"VAP.prepareOpen", "этап 4б (prepareOpen, кэш размеров)"),
    (b"VAP.fs.files", "этап 4 (метки проекций)"),
    (b"com.google.android.gms.cast", "вариант cast (есть Google Cast)"),
]

def main(path):
    try:
        z = zipfile.ZipFile(path)
    except Exception as e:  # noqa: BLE001
        print(f"  не удалось открыть {path}: {e}")
        return
    dex = b"".join(z.read(n) for n in z.namelist() if n.startswith("classes") and n.endswith(".dex"))
    found = [label for needle, label in MARKERS if needle in dex]
    print("  найдено:", "; ".join(found) if found else "ни одной метки: сборка до этапов 4/4б")
    flavor = "cast" if any("cast" in f for f in found) else "foss"
    print(f"  вариант: {flavor}")
    new_enough = any("4б" in f for f in found)
    print("  оптимизации открытия папки:", "ЕСТЬ" if new_enough else "НЕТ (старая сборка)")
    prof = [n for n in z.namelist() if n.endswith("baseline.prof")]
    print("  baseline.prof:", ", ".join(f"{n} ({z.getinfo(n).file_size} Б)" for n in prof) or "нет")

if __name__ == "__main__":
    main(sys.argv[1])
