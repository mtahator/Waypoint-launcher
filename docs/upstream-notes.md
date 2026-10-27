# Upstream behaviour to preserve (Olauncher v6.10.27, `cc6b728`)

Read before touching `HomeFragment`, `MainViewModel`, `Prefs`, `fragment_home.xml`.

## Home (`HomeFragment`, `fragment_home.xml`)
- Root `mainLayout` (FrameLayout) uses `OnSwipeTouchListener`: swipe **up** → app drawer, swipe
  **down** → notification shade, swipe **left/right** → configured app (defaults: camera / dialer),
  **long-press** → Settings, **double-tap** → lock (when enabled), single tap → message check.
- Home app labels (1–8) use `ViewSwipeTouchListener`: tap launches, long-press picks the app, swipes
  behave as on the root.
- Clock tap → alarm/clock app (long-press to choose), date tap → calendar (long-press to choose).
  Date line shows battery % when the status bar is hidden.
- Screen-time label (Android 10+, with usage access).
- Alignment (start/center/end, optional bottom) applies to apps and date/time.

## Settings / storage
- All settings are in SharedPreferences file `app.olauncher` (`Prefs`). Waypoint adds keys prefixed
  `WP_` to the same file (`core/WaypointPrefs.kt`); none of the upstream keys change meaning.

## Background work
- `WallpaperWorker` (WorkManager, periodic) only when the user turns on Daily wallpaper. Upstream already
  defaults it to **off** (`DAILY_WALLPAPER` default `false`). **Deviation:** when on, the worker now
  requires `NetworkType.UNMETERED` and `requiresBatteryNotLow()` (`MainViewModel.setWallpaperWorker`).
- `MainActivity` restarts itself every 4 h on start and checks the theme; preserved.

## Permissions (upstream)
`SET_WALLPAPER`, `EXPAND_STATUS_BAR`, `QUERY_ALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`, `INTERNET`
(wallpapers only), `SET_ALARM`, `PACKAGE_USAGE_STATS`, `ACCESS_HIDDEN_PROFILES`, plus the device-admin
receiver and an accessibility service used **only** for upstream's double-tap-to-lock.

## Deliberate deviations in Waypoint
| Change | Why |
|---|---|
| `applicationId` `app.waypoint.launcher`, label "Waypoint", new icon | Product identity (spec 0.3) |
| Review / rate / share / wallpaper prompts no longer shown; "More features", "Rate", "X/Twitter" rows hidden; footer credits Olauncher | They promote Olauncher's own store listing |
| About dialog shows Waypoint text + Olauncher attribution | GPL attribution |
| Home touch listener wrapped by `HomeGestureCoordinator` | Corner swipe; upstream listener still receives every event unless a swipe that *started in the corner zone* moves downward/inward, which then gets `ACTION_CANCEL` |
| `homeAppsLayout` top padding grows when world clocks are shown | Never let clocks overlap the app list |
| `backToHomeScreen()` skipped while `ExternalFlow.active` | SAF picker / system settings round trips must return to the same screen |
| Settings gets a "Waypoint" card | Entry points to all new screens |
