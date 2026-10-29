# Quick answers, calculator and graphing

Everything below runs on the device. The only network use is the optional exchange-rate download.

## Drawer search
| Type | Example | Notes |
|---|---|---|
| Arithmetic & functions | `12×3+4`, `sin(30°)`, `ln 2`, `5!`, `nCr(10,3)`, `√2`, `2^10`, `|−3|` | Radians by default; `°` for degrees. `12x3` is 12×3 |
| Calculator mode | `=pi`, `=42`, `=2026-10-28` | `=` forces an answer and hides app matches |
| Percent | `15% of 80` | |
| Units | `2 m3 to l`, `1 m² to sq ft`, `100 km/h to mph`, `1 kWh to MJ`, `9.81 m/s^2 to ft/s2`, `100 f to c`, `2 GB to MB` | Dimensional analysis: any combination of compatible units |
| Time | `time in tokyo` | Offline city list |
| Currency | `1$ to £`, `€20 in $`, `100 usd in toman` | Needs rates (below) |
| Graph | `sin(x)`, `x^2+y^2=9`, `graph` | Shows a mini graph; tap/Enter opens the grapher |
| Gemini | `?…` | Opens the composer |

Tap the answer (or press Enter) to copy it.

## Exchange rates
- Source: `https://open.er-api.com/v6/latest/USD` (ExchangeRate-API open access, no key, daily updates).
- Fetched only when a currency conversion is typed, typing has paused (450 ms), the setting is on, and the
  cached rates are older than 6 hours. Failed fetches back off for a minute.
- The request is a fixed URL. No amount, query, locale or identifier is sent.
- Rates are cached in app storage (`files/fx/latest.json`); *Privacy & data → Clear cached rates* deletes them.
- IRR/toman uses the official rate, which can differ a lot from the open market.

## Grapher
Lines are evaluated together, like Desmos:
- `a = 1`: a variable; a plain number becomes a **slider** (range grows to fit).
- `f(x) = x^2`: a function others can use (`y = f(x - a)`).
- `y = …`, `… = y`, a bare expression in `x`: a curve. `x = …` (in `y`): a sideways curve or vertical line.
- Any other equation in `x` and `y` (`x^2 + y^2 = r^2`, `sin(xy) = 0.5`): an implicit curve.
- `(2, 3)`: a point. A line without `x`/`y` shows its value.
- Unknown letters get *add slider* chips. Tap a row's colour dot to hide/show it.

Gestures: drag to pan, pinch to zoom, double-tap to zoom in, touch-and-hold to trace the nearest curve.
*Fit* resets the view. Expressions are saved as you type.

Under the hood: a recursive-descent parser builds an expression tree, which is compiled once into closures,
so each frame re-samples the visible window cheaply. Explicit curves break at asymptotes (midpoint test);
implicit curves use marching squares with a pole check. Nothing is ever executed as code.
