#!/bin/bash
# Замер плавности открытия папки на реальном телефоне.
#
# ТОЛЬКО ЧТЕНИЕ: скрипт ничего не устанавливает, не нажимает на экран и не пишет на телефон. Папку открываете
# и закрываете вы сами. Единственное «изменение» — `dumpsys gfxinfo <пакет> reset`: он обнуляет счётчики
# кадров этого приложения в памяти (данные приложения не затрагиваются). Любая команда adb вне белого
# списка ниже отклоняется.
#
# Использование:  bash tools/perf/measure-device.sh [серийный_номер] [секунд] [метка]
#   по умолчанию: R5CY82VT33X 30 run          (YES=1 пропускает вопрос «готов ли телефон»)
set -u
SERIAL="${1:-R5CY82VT33X}"
DURATION="${2:-30}"
LABEL="${3:-run}"
PKG=com.phnem.vap
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out/${LABEL}-$(date +%Y%m%d-%H%M%S)"
export MSYS_NO_PATHCONV=1
export PYTHONIOENCODING=utf-8 PYTHONUTF8=1
mkdir -p "$OUT"
# python под Windows не понимает пути вида /d/...: переводим в смешанный (D:/...).
wp() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

adbr() {
  case "$*" in
    "get-state"|"shell getprop "*|"shell dumpsys "*|"shell pidof "*|"shell atrace "*|"shell pm path "*) ;;
    "pull "*".apk "*) ;;
    *) echo "ОТКАЗ: команда не из списка только-чтения: adb $*" >&2; exit 99 ;;
  esac
  adb -s "$SERIAL" "$@"
}

if ! adb devices | awk 'NR>1 && $2=="device"{print $1}' | grep -qx "$SERIAL"; then
  echo "Телефон $SERIAL не найден среди подключённых (adb devices):"; adb devices
  echo "Подключите его по USB, включите «Отладка по USB», разрешите этот компьютер и запустите скрипт снова."
  exit 2
fi

{
  echo "== устройство =="
  echo "serial: $SERIAL"
  echo "модель: $(adbr shell getprop ro.product.model | tr -d '\r')"
  echo "android: $(adbr shell getprop ro.build.version.release | tr -d '\r') (sdk $(adbr shell getprop ro.build.version.sdk | tr -d '\r'))"
  echo "дисплей (частота сейчас; у адаптивных экранов меняется по ходу работы):"
  adbr shell dumpsys display | tr -d '\r' | grep -oE "renderFrameRate [0-9.]+|mActiveModeId=[0-9]+" | sort -u | sed 's/^/  /' 
  echo "батарея:"; adbr shell dumpsys battery | tr -d '\r' | grep -iE "temperature|level|status" | sed 's/^/  /'
  echo "== приложение =="
  adbr shell dumpsys package $PKG | tr -d '\r' | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime" | head -5 | sed 's/^/  /'
  echo "на переднем плане:"; adbr shell dumpsys activity activities | tr -d '\r' | grep -m1 -E "ResumedActivity" | sed 's/^/  /'
} | tee "$OUT/info.txt"

APK_PATH="$(adbr shell pm path $PKG | tr -d '\r' | head -1 | sed 's/^package://')"
if [ -n "$APK_PATH" ]; then
  adbr pull "$APK_PATH" "$(wp "$OUT")/installed.apk" >/dev/null 2>&1
  { echo "== какая сборка установлена =="; python "$(wp "$HERE")/inspect_apk.py" "$(wp "$OUT")/installed.apk"; } | tee -a "$OUT/info.txt"
  # Подпись: если совпадает с нашей сборкой, обновление поверх (`adb install -r`) не стирает данные приложения.
  APKSIGNER="$(ls "${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}"/build-tools/*/apksigner.bat 2>/dev/null | tail -1)"
  LOCAL_APK="$HERE/../../app/build/outputs/apk/foss/release/app-foss-release.apk"
  if [ -n "$APKSIGNER" ] && [ -f "$LOCAL_APK" ]; then
    cert() { cmd.exe /c "$(cygpath -w "$APKSIGNER")" verify --print-certs "$(cygpath -w "$1")" 2>/dev/null | grep -m1 "SHA-256" | sed 's/.*: //' | tr -d '
'; }
    CI="$(cert "$OUT/installed.apk")"; CL="$(cert "$LOCAL_APK")"
    {
      echo "  подпись установленной: ${CI:-не прочитана}"
      echo "  подпись новой сборки:  ${CL:-не прочитана}"
      if [ -n "$CI" ] && [ "$CI" = "$CL" ]; then echo "  подписи СОВПАДАЮТ: обновление поверх (adb install -r) сохранит данные";
      else echo "  подписи РАЗНЫЕ или не прочитаны: НЕ ставьте поверх без резервной копии (при несовпадении adb install -r просто откажет, а удаление старой версии сотрёт данные)"; fi
    } | tee -a "$OUT/info.txt"
  fi
fi

if ! grep -q "$PKG" <(adbr shell dumpsys activity activities | tr -d '\r' | grep -m1 -E "ResumedActivity"); then
  echo; echo "ВНИМАНИЕ: VAP не на переднем плане. Откройте его на главном экране библиотеки (список папок)."
fi
if [ "${YES:-}" != "1" ]; then
  read -r -p "Телефон разблокирован, VAP на главном экране библиотеки, ничего не играет? Нажмите Enter: "
fi

adbr shell pidof $PKG | tr -d '\r' > "$OUT/pid.txt"
adbr shell dumpsys gfxinfo $PKG reset >/dev/null
adbr shell "atrace -t $DURATION -b 16384 gfx view -a $PKG" > "$OUT/trace.txt" 2>/dev/null &
ATRACE=$!
printf '\a'
echo
echo ">>> СТАРТ. Следующие $DURATION с: откройте и закройте папку 6 раз ВРУЧНУЮ."
echo ">>> После каждого открытия и закрытия подождите ~2 секунды. Не листайте, не трогайте док."
for ((i=DURATION; i>0; i-=5)); do echo "    осталось ~${i} с"; sleep 5; done
wait $ATRACE
printf '\a'
echo ">>> СТОП. Собираю результаты."

adbr shell dumpsys gfxinfo $PKG | tr -d '\r' > "$OUT/gfxinfo.txt"
adbr shell dumpsys gfxinfo $PKG framestats | tr -d '\r' > "$OUT/framestats.txt"
python "$(wp "$HERE")/analyze.py" "$(wp "$OUT")" | tee "$OUT/summary.txt"
echo; echo "Пришлите файл: $OUT/summary.txt"
