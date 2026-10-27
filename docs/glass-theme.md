# Glass theme

Opt-in (Settings → Waypoint → Appearance → Glass). Off by default.

| Surface | Look |
|---|---|
| Home app labels, screen time, hints | Frosted pills (radius 20dp): translucent fill, 1dp light hairline edge, soft top highlight. Text shadow removed so glyphs stay crisp |
| Clock + date + world clocks | One frosted block (radius 28dp) hugging its content, aligned with the home alignment setting |
| App drawer search | Frosted capsule field |
| Settings cards (upstream and Waypoint) | Frosted cards |
| Drawer / settings background | Real system blur of the wallpaper (36dp) on Android 12+, animated in 220 ms; none on Home |

Tones adapt to the theme: dark uses white at 12–20 % opacity on the blurred wallpaper; light uses white at
45–60 % with dark text.

## Cost
- All surfaces are `GradientDrawable` layers: no `RenderEffect`, no offscreen buffers, no per-frame work.
- The only blur is Android's cross-window blur (`Window.setBackgroundBlurRadius`) while the drawer or
  settings are visible. Android turns it off in battery saver, on low-end GPUs, and when the user disables
  "Allow window-level blurs"; Waypoint then shows the frosted surfaces without blur.
- Glass is applied once at view creation. Toggling it recreates the activity once.
