#!/bin/bash
# Opens Sundial on the emulator and records what happens.
PKG=com.wellsey.sundial
OUT=smoke
mkdir -p $OUT
adb install -r app/build/outputs/apk/debug/app-debug.apk

shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; }
crashed() { adb logcat -d -b crash > "$OUT/crash.txt"; [ -s "$OUT/crash.txt" ]; }
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  b=$(grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" "$OUT/ui.xml" | head -1 | sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2 \3 \4/')
  if [ -n "$b" ]; then set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); else echo "no '$1' on screen"; fi
}

adb logcat -c
adb logcat -b crash -c

# 1. First launch, before calendar access is granted
adb shell am start -W -n $PKG/.MainActivity
shot 1-first-launch 5
adb shell input keyevent KEYCODE_BACK
adb shell am force-stop $PKG

# A test calendar with a few events, so screens aren't empty
adb shell pm grant $PKG android.permission.READ_CALENDAR
adb shell pm grant $PKG android.permission.WRITE_CALENDAR
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS

# 2. Launch with access
adb shell am start -W -n $PKG/.MainActivity
shot 2-month 5
tap_text Week;  shot 3-week
tap_text Day;   shot 4-day
tap_text List;  shot 5-list
tap_text Month; shot 6-month-again
adb shell am start -W -n $PKG/.SearchActivity
adb shell input text "test"; shot 7-search
adb shell am start -W -n $PKG/.EventActivity; shot 8-new-event
adb shell am start -W -n $PKG/.SettingsActivity; shot 9-settings
adb shell "cmd uimode night yes"
adb shell am start -W -n $PKG/.MainActivity; shot 10-dark 5

adb logcat -d > $OUT/logcat.txt
if crashed; then echo "=== CRASH ==="; cat $OUT/crash.txt; else echo "No crashes"; fi
exit 0
