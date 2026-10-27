# Glass theme

Opt-in (Settings → Waypoint → Appearance → Glass). A monochrome, dot-matrix look in the spirit of Nothing OS.

| Element | Look |
|---|---|
| Wallpaper | Blurred across the **whole** window (Home, drawer, settings), under a soft scrim. Android 12+ cross-window blur, 56dp radius |
| Clock | Dot-matrix face (Doto, OFL; static round-dot instance, Latin subset, 99 KB), 25 % larger |
| Date, captions, values | Monospaced caps with wide tracking; date carries a single red (#D71921) accent dot |
| App labels | Upstream type, no shadow (`GlassOverlay` removes text shadows app-wide) |
| World clock slider | Dot-matrix times, monospaced city captions, page dots with the active one in red |
| Drawer search | Hairline capsule |
| Settings & Waypoint pages | Dot-matrix page titles, red-dot caption headers, hairline translucent cards |

Fallback: when the system can't blur (Android ≤ 11, battery saver, "Allow window-level blurs" off,
some GPUs), the scrim gets denser (65 % black / 60 % white) so text stays readable. Waypoint listens for
blur availability changes and re-applies.

## Cost
- One compositor blur pass for the window. No `RenderEffect`, offscreen buffers or per-frame work.
- Drawables are plain `GradientDrawable`s; styling happens once at view creation and never changes layout.
- Monochrome and OLED black override Glass (they hide the wallpaper).
