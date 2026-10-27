# Battery test protocol

1. Full charge, reboot, set Waypoint as Home, airplane mode off, screen-off timeout 30 s.
2. Run each scenario for 8 h screen off, then `adb bugreport` and load into Battery Historian:
   a) no rules, capture off; b) 3 time rules; c) notification capture on with 1 source app.
3. Also record `adb shell dumpsys alarm | grep -A3 app.waypoint`, `dumpsys jobscheduler | grep waypoint`.
4. Expected: (a) zero alarms/jobs/wakelocks from Waypoint features; (b) exactly one pending alarm;
   (c) listener bound, no wakelocks.

Results: _not run yet; requires a physical device._
