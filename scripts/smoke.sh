#!/usr/bin/env bash
# Emulator smoke test: install, set as Home, walk the main screens, fail on any crash.
set -u
APK=${1:-apk/Waypoint.apk}
OUT=${2:-smoke}
PKG=app.waypoint.launcher
mkdir -p "$OUT"
shot() { sleep 2; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
tap() { python3 scripts/ui.py tap "$@" || { echo "warn: could not tap $*"; return 1; }; }
home() { adb shell input keyevent KEYCODE_HOME; sleep 2; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1.5; }
read -r W H < <(python3 scripts/ui.py size)
open_settings() { home; home; adb shell input swipe $((W/2)) $((H/2)) $((W/2)) $((H/2)) 1600; sleep 2; }
add_city() {
  tap "Add city"; sleep 1
  adb shell input text "$1"; sleep 2
  tap "$1" exact || back   # result row; close the dialog if nothing matched
  sleep 1
}

adb install -r "$APK"
adb logcat -c
adb shell cmd package set-home-activity "$PKG/app.olauncher.MainActivity" || true
home; sleep 4; home; sleep 2
shot 01-home

adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; shot 02-drawer; home

open_settings; shot 03-settings-first-open
# First settings open shows upstream's welcome/About dialog: dismiss it
back; open_settings; shot 03-settings
tap "World clock"; add_city Tokyo; add_city Tehran; add_city London
shot 04-world-clock-settings
home; shot 05-home-world-clock
# Swipe the world clock slider to the next city
SY=$(python3 - <<'PY'
import subprocess,re,xml.etree.ElementTree as ET
subprocess.run(["adb","shell","uiautomator","dump","/sdcard/ui.xml"],capture_output=True)
x=subprocess.run(["adb","shell","cat","/sdcard/ui.xml"],capture_output=True,text=True).stdout
r=ET.fromstring(x[x.index("<"):])
for n in r.iter("node"):
    if n.get("resource-id","").endswith("worldClockPager"):
        b=list(map(int,re.findall(r"\d+",n.get("bounds")))); print((b[1]+b[3])//2); break
PY
)
if [ -n "$SY" ]; then adb shell input swipe $((W*3/4)) "$SY" $((W/5)) "$SY" 250; fi
shot 06-home-world-clock-swiped
tap "London"; shot 07-world-clock-panel; home

open_settings; tap "Gestures"; shot 08-gestures
tap "Enabled" exact
open_settings; tap "Notifications & ledger"; shot 09-notifications
open_settings; tap "Export CSV"; shot 10-export
open_settings; tap "Privacy & data"; shot 11-privacy

# Glass theme
open_settings; tap "Appearance"; shot 12-appearance
tap "Reeded glass over"; sleep 3
open_settings; tap "Appearance"; shot 12b-appearance-glass
tap "Monochrome glass"; sleep 3
home; shot 13b-home-glass-mono
open_settings; tap "Appearance"; tap "Monochrome glass"; sleep 3
home; shot 13-home-glass
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; shot 14-drawer-glass
# Typed search text follows its own direction (Latin from the left); "?" turns it into an Ask Gemini command
adb shell input text "e"; sleep 1; shot 14b-drawer-search-typing
adb shell input keyevent 67; adb shell input text "12x3+4"; sleep 1; shot 14d-drawer-calculator
adb shell input keyevent 67 67 67 67 67 67; adb shell input text "5%skm%sto%smi"; sleep 1; shot 14e-drawer-units
clear_search() { adb shell input keyevent $(printf '67 %.0s' $(seq 1 30)); }
clear_search; adb shell "input text '=sin(pi/6)+5!'"; sleep 1; shot 14f-drawer-forced-calc
clear_search; adb shell "input text '2%sm3%sto%sl'"; sleep 1; shot 14g-drawer-units-power
clear_search; adb shell "input text '100%susd%sto%seur'"; sleep 6; shot 14h-drawer-currency
clear_search; adb shell "input text 'x^2+y^2=9'"; sleep 1; shot 14i-drawer-mini-graph
clear_search; adb shell "input text '=x^2+y^2=r'"; sleep 1; shot 14j-drawer-graph-slider
clear_search; adb shell "input text '=sin(x)'"; sleep 1; adb shell input keyevent 66; sleep 3; shot 15a-graph-page
adb shell input keyevent 4; sleep 1
clear_search; adb shell "input text '=y>x^2-2'"; sleep 1; adb shell input keyevent 66; sleep 3
tap "+ Add expression"; sleep 1; adb shell "input text 'y=2-x'"; sleep 1; shot 15c-graph-typing
adb shell input keyevent 4; sleep 2; shot 15d-graph-inequality-intersections
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
open_settings; tap "Graphing calculator"; sleep 2
adb shell input swipe $((W/2)) $((H/4)) $((W/3)) $((H/3)) 300; sleep 1; shot 15b-graph-panned
home
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; sleep 1
# Assistant settings and the Chat app
open_settings; tap "Wayfinder (assistant"; sleep 1; shot 16a-assistant-settings
tap "Your API (chat app)"; sleep 1; shot 16b-assistant-api
adb shell am start -n $PKG/app.olauncher.features.assistant.ui.ChatActivity; sleep 3; shot 16c-chat-list
tap "+ New"; sleep 2; adb shell "input text 'Hello'"; sleep 1; shot 16d-chat-new
# a reminder request is done on the phone (no model, no search) and confirmed in the chat
for i in 1 2 3 4 5; do adb shell input keyevent 67; done
adb shell "input text 'Set%sreminder%sfor%stonight%sthat%sI%smust%sdo%sthe%sdishes'"; sleep 1
tap "Send"; sleep 3; shot 16d2-chat-reminder
adb shell input keyevent 4; sleep 1
# Space: record a few seconds (mic permission pre-granted), stop, see the note
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell am start -n $PKG/app.olauncher.features.assistant.ui.ChatActivity --ez space true; sleep 2; shot 16e-space
adb shell am start -n $PKG/app.olauncher.features.assistant.ui.ChatActivity --ez record true; sleep 4; shot 16f-space-recording
adb shell am start -n $PKG/app.olauncher.features.assistant.ui.ChatActivity --ez space true; sleep 2
tap "Stop" || adb shell input keyevent 4; sleep 3; shot 16g-space-note
# Write a note with a reminder, add a to-do and a goal
tap "Write"; sleep 2; adb shell "input text 'Remind%sme%sto%scall%smom%stomorrow%sat%s5pm'"; sleep 1; shot 16h-space-write
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 3; shot 16i-space-organised
tap "Add"; sleep 1; tap "Goal"; sleep 1; adb shell "input text 'Exercise'"; tap "Okay" exact; sleep 2
tap "To-do"; sleep 2; shot 16j-space-todo
home
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; sleep 1
# "?" opens the Gemini composer (multi-line)
adb shell input text "?How%sdoes%sreeded%sglass%swork"; sleep 1
adb shell input keyevent 66; adb shell input text "Explain%sin%sa%sshort%sparagraph."; sleep 1; shot 14c-drawer-ask-gemini
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4
home
open_settings; shot 15-settings-glass
tap "World clock"; shot 16-world-clock-glass
home

# Corner swipe (top-end, below the status bar) toggles launcher monochrome, then back
adb shell input swipe $((W-90)) 330 $((W-280)) 700 250; shot 17-corner-swipe-monochrome
adb shell input swipe $((W-90)) 330 $((W-280)) 700 250; sleep 1
# A pull from the very top edge must NOT toggle monochrome
adb shell input swipe $((W-60)) 5 $((W-60)) 700 250; sleep 2; adb shell cmd statusbar collapse; home; shot 18-after-top-pull

# Glass off again: Home, the drawer, Wayfinder and Space must show the plain wallpaper look
open_settings; tap "Appearance"; tap "Reeded glass over"; sleep 3
home; sleep 2; shot 19-home-glass-off
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; sleep 2; shot 19b-drawer-glass-off
adb shell input keyevent 4; sleep 1; home; sleep 1
adb shell am start -n $PKG/app.olauncher.features.assistant.ui.ChatActivity; sleep 3; shot 19c-chat-glass-off
adb shell am start -n $PKG/app.olauncher.features.assistant.ui.ChatActivity --ez space true; sleep 2; shot 19d-space-glass-off
home; sleep 1; adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; sleep 2; shot 19e-drawer-after-wayfinder

adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt"; then
  grep -A40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -100 > "$OUT/crash.txt"
  cat "$OUT/crash.txt"
  exit 1
fi
echo "smoke ok"
