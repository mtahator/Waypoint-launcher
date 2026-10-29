# Changelog

## 1.1.0 (2026-10-27)
### Added
- **Assistant modes** for `?`: the Gemini app, or your own API (OpenAI-compatible or Anthropic; presets for
  OpenAI, Gemini API, Claude, OpenRouter, Groq, Ollama). API key encrypted with the Android Keystore.
- **Chat** app (its own entry in the app list): streamed replies saved as conversations you can continue,
  rename or delete; Markdown, stop, regenerate/retry, copy. Stored on the phone, excluded from backup.
- **Grapher**: inequalities with shading (`y > x^2`, `x <= 2`, `x^2+y^2 < 9`; strict ones dashed);
  intersection dots (tap for coordinates); tap a curve for its roots, maxima/minima and y-intercept.
- Search graphs accept unknown letters as sliders (`x^2+y^2=r` → `r = 1`).
- **Graphing calculator** (Desmos-style): multiple expressions, sliders, `f(x)` definitions, `y =`, `x =`,
  implicit equations (marching squares), points; pan, pinch-zoom, double-tap zoom, hold to trace. Mini
  graph in the drawer search for `sin(x)` and friends. Settings → Graphing calculator.
- **Math engine** for quick answers: trig/hyperbolic/inverse functions, `log`, `ln`, `√`, `!`, `°`,
  `nCr`, `mod`, `|x|`, implicit multiplication (`2x`, `3sin x`), Persian digits. `=` prefix forces
  calculator mode.
- **Units with dimensions**: powers and compounds (`m3`, `m^3`, `m³`, `sq ft`, `km/h`, `m/s^2`), plus energy,
  power, pressure, force, frequency, angle, time and data units.
- **Currency conversion** (`1$ to £`, `100 usd in toman`): public USD rates from open.er-api.com, fetched
  only when a currency conversion is typed and the cache is older than 6 hours; nothing typed is sent.
  Toggle and cache clearing in Privacy & data.
- **Ask Gemini** from the drawer search: type `?` (or `؟`) to open a multi-line composer for whole
  paragraphs; *Send* hands it to the Gemini app (share intent), or Gemini on the web if the app is missing.
- **Quick answers** in the drawer search, offline: arithmetic (`12×3+4`, `√2`, `2^10`), `15% of 80`, unit
  conversion (length, mass, volume, speed, temperature, data) and `time in <city>`. Tap or Enter copies.
### Changed
- Opening a graph from the search shows only that equation (a fresh sheet), and the plane shrinks while
  typing so the edited line stays above the keyboard (edge-to-edge keyboard insets).
- Fixed: the mini graph in the search didn't reappear after returning from the grapher.
- Drawer search: text starts at the left (Persian from the right) with a visible blinking cursor, and a
  hint that lists the shortcuts.
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
