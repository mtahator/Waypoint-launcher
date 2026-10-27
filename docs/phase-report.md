# Implementation report (phases 0–5, first pass)

## Scope implemented
- Phase 0: fork, identity, CI with APK publishing, StrictMode (debug), lazy `AppContainer`, dispatchers,
  upstream notes, toolchain record. **Not done:** `:benchmark` / `:baselineprofile` modules and baseline numbers (no device available).
- Phase 1: world clock (domain, prefs, city asset, settings, home rows, panel), core library desugaring.
- Phase 2: `HomeGestureCoordinator` with insets-aware corner zone, gesture settings with "Try it",
  monochrome / OLED / glass, system grayscale guidance.
- Phase 3: automation domain, validator, evaluator, engine, JSON mapper, SQLite storage with pruning,
  single-alarm scheduler, boot receiver toggling, executor, list / builder / details UI.
- Phase 4: notification access onboarding + capture service, generic parser, ledger DB with conservative
  dedup, ledger UI with editing, privacy screen, notification trigger/conditions + SaveTransaction.
- Phase 5: CSV export (preview, columns, SAF save, share, temp cleanup) and ADB-granted grayscale.

## Tests
- 62 JVM unit tests (world clock, gestures, validator, conditions, time scheduling incl. DST, engine,
  JSON mapper, parser, money, dedup, CSV). All green locally and in CI (`testDebugUnitTest`).
- Not yet: Robolectric/instrumented integration tests, SQLite migration tests, logcat privacy test.

## CI
- Every push: unit tests → R8 release build → lint → APK committed to `apk/` → emulator smoke test
  (API 34) that walks Home, drawer, settings, every Waypoint screen and the glass theme, fails on any
  crash, and commits screenshots to `docs/screenshots/`.
- Release APK: 2.24 MB (upstream-identity build: 1.93 MB; budget +1.5 MB). Startup Baseline Profile shipped.

## Benchmarks
None run (see `perf-baseline.md`). Startup-path guarantees are by construction and documented there.

## Known limitations / TODO(verify-on-device)
- Gesture matrix, TalkBack/RTL/font-scale checks, Restricted-settings flow, Android 15 redaction.
- Revolut package id and real notification fixtures.
- Left/right swipes still open apps only (upstream); mapping them to any `GestureAction` is not done.
- Room replaced by platform SQLite (see `dependencies.md`): needs sign-off.
- Wallpaper worker constraints (UNMETERED, battery-not-low) not yet added.
