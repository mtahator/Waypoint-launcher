#!/usr/bin/env bash
# Emulator smoke test: install, set as Home, walk the main screens, fail on any crash.
set -u
APK=${1:-apk/Waypoint.apk}
OUT=${2:-smoke}
PKG=app.waypoint.launcher
mkdir -p "$OUT"
shot() { sleep 2; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
tap() { python3 scripts/ui.py tap "$@" || echo "warn: could not tap $*"; }
home() { adb shell input keyevent KEYCODE_HOME; sleep 2; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1.5; }
read -r W H < <(python3 scripts/ui.py size)

adb install -r "$APK"
adb logcat -c
adb shell cmd package set-home-activity "$PKG/app.olauncher.MainActivity" || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
home; sleep 3
shot 01-home

# Drawer
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; shot 02-drawer; home

# Settings via long-press, then Waypoint screens
open_settings() { home; adb shell input swipe $((W/2)) $((H/2)) $((W/2)) $((H/2)) 1600; sleep 2; }
open_settings; shot 03-settings
tap "World clock"; shot 04-world-clock
tap "Add city"; tap "Search a city"; adb shell input text "Tokyo"; sleep 2; tap "Tokyo" exact; sleep 1
tap "Add city"; tap "Search a city"; adb shell input text "Tehran"; sleep 2; tap "Tehran" exact; sleep 1
shot 05-world-clock-cities
home; shot 06-home-world-clock

open_settings; tap "Gestures"; shot 07-gestures
open_settings; tap "Automations"; shot 08-automations
tap "New rule"; shot 09-rule-builder
open_settings; tap "Notifications & ledger"; shot 10-notifications
open_settings; tap "Export CSV"; shot 11-export
open_settings; tap "Privacy & data"; shot 12-privacy

# Glass theme
open_settings; tap "Appearance"; shot 13-appearance
tap "Glass" exact; sleep 3
home; shot 14-home-glass
adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; shot 15-drawer-glass; home
open_settings; shot 16-settings-glass
home

# Corner swipe (top-end) toggles launcher monochrome
adb shell input swipe $((W-90)) 260 $((W-260)) 600 250; shot 17-corner-swipe-monochrome
adb shell input swipe $((W-90)) 260 $((W-260)) 600 250; sleep 1

# Crash check
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|AndroidRuntime: FATAL|ANR in $PKG" "$OUT/logcat.txt"; then
  grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 > "$OUT/crash.txt"
  cat "$OUT/crash.txt"
  exit 1
fi
echo "smoke ok"
