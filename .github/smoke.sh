#!/bin/bash
# Opens Sundial on the emulator and records what happens.
PKG=com.wellsey.sundial
OUT=smoke
mkdir -p $OUT
adb install -r app/build/outputs/apk/debug/app-debug.apk

dismiss_anr() {
  # The emulator's own launcher sometimes shows "isn't responding"; tap Wait so it doesn't cover the app
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  if grep -q "isn't responding" "$OUT/ui.xml" 2>/dev/null; then tap_text Wait; sleep 1; fi
}
shot() { sleep "${2:-4}"; dismiss_anr; adb exec-out screencap -p > "$OUT/$1.png"; }
crashed() { adb logcat -d -b crash > "$OUT/crash.txt"; [ -s "$OUT/crash.txt" ]; }
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  b=$(grep -o "\(text\|content-desc\)=\"$1\"[^>]*bounds=\"[^\"]*\"" "$OUT/ui.xml" | head -1 | sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2 \3 \4/')
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

# Sample events, added through Sundial's own "add to calendar" screen (also tests saving)
NOW=$(date +%s)
DAY0=$(( NOW - NOW % 86400 ))   # today 00:00 UTC (the emulator clock is UTC)
H=3600
add() { # title start_sec end_sec [allDay] [location]
  adb shell am start -W -a android.intent.action.INSERT -t vnd.android.cursor.dir/event -n $PKG/.EventActivity \
    --es title "'$1'" --el beginTime $(( (DAY0 + $2) * 1000 )) --el endTime $(( (DAY0 + $3) * 1000 )) \
    --ez allDay ${4:-false} --es eventLocation "'${5:-}'" >/dev/null
  sleep 2; dismiss_anr; tap_text Save; sleep 1
}
add "Team standup" $((9*H)) $((9*H+1800))
add "Dentist" $((11*H)) $((12*H+1800)) false "High St Dental"
add "Call with Sam" $((11*H+1800)) $((12*H))
add "Gym" $((18*H)) $((19*H))
add "Lunch with Priya" $((86400+13*H)) $((86400+14*H)) false "Dishoom"
add "Bank holiday" $((2*86400)) $((3*86400)) true
add "Trip to Lisbon" $((3*86400)) $((6*86400)) true
add "Dinner" $((-86400+19*H)) $((-86400+21*H))
shot 1b-after-adding 2

# 2. Launch with access
adb shell am start -W -n $PKG/.MainActivity
shot 2-month 5
tap_text Week;  shot 3-week
tap_text Day;   shot 4-day
tap_text List;  shot 5-list
tap_text Month; shot 6-month-again
tap_text Search; sleep 2
adb shell input text "Dent"; shot 7-search
adb shell input keyevent KEYCODE_BACK; adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -W -n $PKG/.EventActivity; shot 8-new-event
adb shell input keyevent KEYCODE_BACK; sleep 1
tap_text Settings; shot 9-settings
adb shell input keyevent KEYCODE_BACK
adb shell "cmd uimode night yes"
adb shell am start -W -n $PKG/.MainActivity; shot 10-dark 5

adb logcat -d > $OUT/logcat.txt
if crashed; then echo "=== CRASH ==="; cat $OUT/crash.txt; else echo "No crashes"; fi
exit 0
