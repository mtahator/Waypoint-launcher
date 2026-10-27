# Performance baseline

**Status: not measured yet.** The build environment used to create this fork has no device or emulator,
so no Macrobenchmark numbers exist. Nothing below is invented.

| Metric (mid-range reference, release) | Upstream | Waypoint | Budget |
|---|---|---|---|
| Cold start TTID | — | — | ≤ upstream +5 %, target ≤ 350 ms |
| Hot start (press Home) | — | — | ≤ upstream +5 % |
| Janky frames (drawer open/scroll/return) | — | — | < 1 % |
| Idle PSS | — | — | ≤ upstream +10 % |
| Release APK size | 1.93 MB (Waypoint identity build `00e1779`) | see `apk/BUILD_INFO.txt` | ≤ upstream +1.5 MB |

## Measured by design (code-level guarantees)
- `Application.onCreate`: StrictMode (debug only) + a lazy container. No I/O, no DB, no jobs.
- Home path reads only SharedPreferences (already loaded by upstream). Neither database is opened unless an
  automation/ledger screen is opened or an enabled rule/capture needs it.
- No timers or polling. World clocks are `TextClock`s; offset labels refresh on `TIME_TICK` only between
  `onStart` and `onStop`.
- At most one alarm for all time rules; the boot receiver is component-disabled without time rules.
- Notification listener: one set lookup rejects other apps; it unbinds itself when nothing needs it.
- Glass: plain `GradientDrawable`s; the only blur is the system's cross-window blur while drawer/settings
  are visible (Android disables it in battery saver).

## To do
Add `:benchmark` (Macrobenchmark) and `:baselineprofile` modules and run them on a reference device.
