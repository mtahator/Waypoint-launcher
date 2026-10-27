# Changelog

## 1.1.0 (2026-10-27)
### Changed
- **Glass is now reeded glass** (Nothing style): a fluted-glass backdrop rendered over the built-in
  *Aurora* gradient or your own photo, optional monochrome glass, three reed widths; the blurred system
  wallpaper stays as an option. Home shows a round-dot matrix clock and a dotted date (`TUE, 27 OCT`).
- **Glass theme redesigned** in the spirit of Nothing OS: whole-screen wallpaper blur on every screen, dot-matrix
  clock (Doto, OFL), monospaced captions, a single red accent, hairline cards; no more pills behind text.
- **World clock is a slider**: swipe between cities under the date; page dots; fixed height.
- **Home apps no longer move**: the world clock slot has a fixed height and is only rebuilt when it changes.
- **Home layout**: apps are right-aligned by default, and a new *Apps position* slider (Appearance) places
  the app list anywhere from top to bottom, resolved in one layout pass so it never shifts.
- Corner swipe is now opt-in and can never start in the status-bar / notification pull area.

### Fixed
- Wallpaper could disappear: pulling down notifications from the top-right corner (with the status bar hidden)
  was read as a corner swipe and turned on monochrome. A one-time migration turns monochrome off again.
- CI: APK publishing no longer fails when an earlier run already pushed.

### Removed
- The automation feature (rules, scheduler, boot receiver) and its permissions
  (`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`).

## 1.0.0 (2026-10-27)
First Waypoint release, forked from Olauncher v6.10.27.

### Added
- Waypoint identity: `app.waypoint.launcher`, new monochrome adaptive/themed icon, NOTICE and About attribution.
- Glass theme (frosted surfaces; wallpaper blur behind drawer/settings on Android 12+).
- World clock: home rows (max 3), panel, settings with offline search of 360 cities and all IANA zones.
- Corner swipe gesture with configurable corner, action and sensitivity, plus a "Try it" area.
- Launcher monochrome, OLED black, system grayscale guidance and ADB-granted advanced toggle.
- Notification capture for selected apps, transaction ledger with editing, CSV export, privacy screen.
- CI that builds, tests and publishes `apk/Waypoint.apk`.

### Changed
- Olauncher store-promotion prompts and links removed/hidden.
- Ledger excluded from backups.
