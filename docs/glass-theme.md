# Glass theme

Opt-in (Settings → Waypoint → Appearance → Glass). A monochrome, dot-matrix look in the spirit of Nothing OS.

| Element | Look |
|---|---|
| Background (default) | **Reeded glass**: vertical flutes refract a backdrop — the built-in *Aurora* (orange/red with blue arcs) or a photo you pick. Optional monochrome glass; narrow / normal / wide reeds |
| Background (option) | Blurred system wallpaper across the whole window, under a soft scrim (Android 12+ cross-window blur, 56dp radius) |
| Home clock & date | Round-dot matrix (5×7 grid, dots nearly touching) drawn by `DotMatrixView`; the date falls back to text for scripts the grid doesn't cover |
| Other clocks | Dot-matrix face (Doto, OFL; static round-dot instance, Latin subset, 99 KB) |
| Date, captions, values | Monospaced caps with wide tracking; date carries a single red (#D71921) accent dot |
| App labels | Upstream type, no shadow (`GlassOverlay` removes text shadows app-wide) |
| World clock slider | Dot-matrix times, monospaced city captions, page dots with the active one in red |
| Drawer search | Hairline capsule |
| Settings & Waypoint pages | Dot-matrix page titles, red-dot caption headers, hairline translucent cards |

Fallback: when the system can't blur (Android ≤ 11, battery saver, "Allow window-level blurs" off,
some GPUs), the scrim gets denser (65 % black / 60 % white) so text stays readable. Waypoint listens for
blur availability changes and re-applies.

## Why a rendered backdrop
Android 13+ doesn't let a launcher read the wallpaper's pixels, so a refraction effect can't be applied to
it. Waypoint renders the reeded glass itself (`ReededGlass`: soft blur, per-reed refraction with a
parabolic lift, edge highlights and shadows, grain) once at half resolution off the main thread, caches it
in `files/glass/`, and only re-renders when a setting, the photo or the light/dark theme changes. The photo
comes from the system photo picker (no storage permission) and is copied locally, downscaled.

## Cost
- One compositor blur pass for the window. No `RenderEffect`, offscreen buffers or per-frame work.
- Drawables are plain `GradientDrawable`s; styling happens once at view creation and never changes layout.
- Monochrome and OLED black override Glass (they hide the wallpaper).
