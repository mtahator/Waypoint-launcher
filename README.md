# Waypoint

A fast, text-only, distraction-free Android launcher with a near-zero idle battery footprint.
Waypoint is based on [Olauncher](https://github.com/tanujnotes/Olauncher) by tanujnotes and is
licensed under **GPL-3.0**.

## Download

**[apk/Waypoint.apk](apk/Waypoint.apk)**: built by CI from this branch (see `apk/BUILD_INFO.txt` for the
commit and SHA-256). Open the file on your phone, allow installing from this source, then press Home and
choose Waypoint.

> The APK is signed with a public *sideload* key so updates install over each other. It is not a
> Play Store build. Waypoint installs side by side with Olauncher and does not import its settings.

## What's new compared to Olauncher

Everything is **off or idle until you use it**. A feature you never enable creates no objects, opens
no database, registers no receivers and schedules no work.

| Feature | Where |
|---|---|
| **Glass theme**: frosted panels behind home text, the search field and settings cards; real wallpaper blur behind the drawer and settings on Android 12+ | Settings → Waypoint → Appearance |
| **World clock**: up to 3 cities under the date (DST-correct, `+1 day` offsets), full panel, offline city search | Settings → Waypoint → World clock |
| **Corner swipe**: from the top corner, swipe down/inward (default: toggle monochrome). Never starts in the system back/notification gesture areas | Settings → Waypoint → Gestures |
| **Launcher monochrome & OLED black**: instant, no recreation, no GPU filters | Appearance |
| **System grayscale**: guidance to Android's own options, plus an optional ADB-granted toggle | Appearance |
| **Automations (If → Then)**: triggers (corner swipe, Home opened, monochrome changed, time, notification) + conditions + up to 5 whitelisted actions, run test, history | Settings → Waypoint → Automations |
| **Notification capture → local ledger**: only from apps you pick; parsed fields only, never raw text | Notifications & ledger |
| **CSV export**: preview, column choice, masked preview, save via the system file picker or share | Export CSV |
| **Privacy & data**: counts of what's stored and one-tap deletion | Privacy & data |

Upstream behaviour is preserved: swipe up for apps, swipe down for notifications, swipe left/right for
apps, long-press for settings, double-tap to lock, 8 home apps, hidden apps, Private Space, and so on.

## Honest limitations
- **Time rules run *around* their time** (inexact alarms) unless you mark a rule *precise* and allow
  exact alarms. Some manufacturers delay background work further. See [dontkillmyapp.com](https://dontkillmyapp.com).
- **System grayscale** can't be switched by a normal app. The advanced toggle needs a one-time
  `adb shell pm grant app.waypoint.launcher android.permission.WRITE_SECURE_SETTINGS` and may not work on every device.
- **Sideloaded installs on Android 13+** can't grant notification access until you allow it in
  App info → ⋮ → *Allow restricted settings*.
- **Android 15+** may hide sensitive notification content (e.g. codes) from listeners; such records are marked *Needs review*.
- Notification formats change without notice: anything ambiguous is marked *Needs review*, never guessed.
- Glass blur needs Android 12+ and a device with cross-window blur enabled (off in battery saver). Older
  devices get the frosted surfaces without blur.
- Performance benchmarks have not been run on a device yet (see `docs/perf-baseline.md`).

## Build

```bash
./gradlew testDebugUnitTest assembleRelease
```

See [docs/toolchain.md](docs/toolchain.md). The Kotlin namespace stays `app.olauncher` so upstream changes
can still be merged.

## Docs
[Upstream notes](docs/upstream-notes.md) · [Dependencies](docs/dependencies.md) · [CSV format](docs/csv-format.md) ·
[Performance](docs/perf-baseline.md) · [Battery test](docs/battery-test.md) · [Device tests](docs/device-tests.md) ·
[Privacy](PRIVACY.md) · [Changelog](CHANGELOG.md)

## License
GPL-3.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Waypoint is based on Olauncher by tanujnotes.
