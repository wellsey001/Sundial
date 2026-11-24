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

# Test calendar + events, inserted as the app (it's a debug build, so run-as works)
NOW=$(date +%s)
DAY0=$(( NOW - NOW % 86400 ))   # today 00:00 UTC (emulator clock is UTC)
ms() { echo $(( ($DAY0 + $1) * 1000 )); }
CAL_URI="content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=Test&account_type=LOCAL"
as_app() { adb shell run-as $PKG "$@" 2>&1 || adb shell "$@" 2>&1; }
as_app content insert --uri "$CAL_URI" --bind account_name:s:Test --bind account_type:s:LOCAL --bind name:s:test \
  --bind calendar_displayName:s:Personal --bind calendar_color:i:-11549705 --bind calendar_access_level:i:700 \
  --bind ownerAccount:s:Test --bind visible:i:1 --bind sync_events:i:1 --bind calendar_timezone:s:UTC
CAL=$(as_app content query --uri content://com.android.calendar/calendars --projection _id | grep -o '_id=[0-9]*' | tail -1 | cut -d= -f2)
echo "calendar id: $CAL"
ev() { # title start_sec end_sec [allDay] [location] [color]
  as_app content insert --uri content://com.android.calendar/events --bind calendar_id:i:$CAL --bind title:s:"$1" \
    --bind dtstart:l:$(ms $2) --bind dtend:l:$(ms $3) --bind eventTimezone:s:UTC --bind allDay:i:${4:-0} \
    --bind eventLocation:s:"${5:-}" ${6:+--bind eventColor:i:$6}
}
H=3600
ev "Team standup" $((9*H)) $((9*H+1800))
ev "Dentist" $((11*H)) $((12*H+1800)) 0 "High St Dental"
ev "Call with Sam" $((11*H+1800)) $((12*H)) 0 "" -1092784
ev "Gym" $((18*H)) $((19*H)) 0 "" -14575885
ev "Lunch with Priya" $((86400+13*H)) $((86400+14*H)) 0 "Dishoom" -754176
ev "Bank holiday" $((2*86400)) $((3*86400)) 1
ev "Trip to Lisbon" $((3*86400)) $((6*86400)) 1 "" -6543440
ev "Dinner" $((-86400+19*H)) $((-86400+21*H))
as_app content query --uri content://com.android.calendar/events --projection title | head

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
