#!/bin/bash
# usage: scen.sh <out-trace> <restart:0|1> <passes: "d u d"> <secs> [folder]
export ANDROID_SERIAL=3871a9d6 MSYS_NO_PATHCONV=1
out=$1; restart=$2; passes=$3; secs=${4:-70}; folder=${5:-VAP-Perf-Huge}
adb shell input keyevent KEYCODE_WAKEUP
if [ "$restart" = 1 ]; then adb shell am force-stop com.phnem.vap; fi
adb shell am start -n com.phnem.vap/.MainActivity >/dev/null; sleep 7
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
b=$(adb exec-out cat /sdcard/ui.xml | grep -o "content-desc=\"$folder\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
[ -z "$b" ] && { echo "folder not found"; exit 1; }
x=$(echo $b | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \3/' | awk '{print int(($1+$2)/2)}')
y=$(echo $b | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2 \4/' | awk '{print int(($1+$2)/2)}')
echo "tap $x $y"
adb shell "atrace -t $secs -b 32768 gfx view -a com.phnem.vap" > "$out" &
AP=$!
sleep 3
adb shell input tap $x $y; sleep 3
for p in $passes; do
  for i in $(seq 1 22); do
    if [ $p = d ]; then adb shell input swipe 540 2000 540 500 90; else adb shell input swipe 540 500 540 2000 90; fi
    sleep 0.4
  done
  sleep 1
done
adb shell pidof com.phnem.vap
wait $AP
