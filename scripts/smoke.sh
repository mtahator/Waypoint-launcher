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
tap "Your whole wallpaper"; sleep 3
home; shot 13-home-glass
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; shot 14-drawer-glass; home
open_settings; shot 15-settings-glass
tap "World clock"; shot 16-world-clock-glass
home

# Corner swipe (top-end, below the status bar) toggles launcher monochrome, then back
adb shell input swipe $((W-90)) 330 $((W-280)) 700 250; shot 17-corner-swipe-monochrome
adb shell input swipe $((W-90)) 330 $((W-280)) 700 250; sleep 1
# A pull from the very top edge must NOT toggle monochrome
adb shell input swipe $((W-60)) 5 $((W-60)) 700 250; sleep 2; adb shell cmd statusbar collapse; home; shot 18-after-top-pull

adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt"; then
  grep -A40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -100 > "$OUT/crash.txt"
  cat "$OUT/crash.txt"
  exit 1
fi
echo "smoke ok"
