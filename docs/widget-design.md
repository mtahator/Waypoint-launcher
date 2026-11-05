# Waypoint widget design standard

Every Home widget is built from this standard. The code mirrors it in
`features/widgets/ui/WidgetDesign.kt`; change a value there and here together.

## 1. One stack, pages, cells

- Home has **one** widget stack under the clock. Swipe sideways through its **pages** (round and round);
  a row of dots under the stack shows where you are.
- A page holds **one widget** (full width) or **two widgets** (two squares side by side). The user picks per page.
- Music joins as the first page by itself while an app has a media session (playing or paused) and leaves
  when the session ends.
- Pinch the stack (two fingers together) to hide it for a while: a short line stays in its place; tap
  the line to bring the widgets back. Remembered, and nothing is refreshed while hidden. The corner
  swipe can do the same (Gestures → Show / hide widgets).
- Fixed height for every page: **176 dp**, so the stack never jumps and never reaches the app list.
  Gap between two cells: **10 dp**. Side margin of the stack: **16 dp**.

## 2. Material: frosted glass plaque

A widget is a slab of frosted glass laid on the backdrop. Layers, bottom to top:

| Layer | Value |
| --- | --- |
| Backdrop seen through the glass | the real backdrop under the card, blurred (≈48 dp), fixed to the screen so it stays put while the card slides |
| Tint | dark glass: black 26 % · light glass: white 40 % (picked per card from what is behind it) |
| Grain | fine monochrome noise, 3.5 % |
| Sheen | top-to-middle white gradient, 9 % → 0 |
| Edge | 1 px hairline, white 24 % at the top fading to 6 % at the bottom (dark ink cards: black 10 %) |
| Corners | 26 dp |

No drop shadows. Where Waypoint can't see the pixels behind it (the system wallpaper on Android 13+), the
glass keeps tint, grain, sheen and edge and the tint is 10 % denser.

## 3. Ink: text that reads on anything

- Every text, icon and dot element picks **its own** colour from what is directly behind it: white ink on a
  dark area, near-black ink (#141414) on a light area, by whichever gives the higher WCAG contrast.
  A screen that is half black and half white gets white text on one half and black on the other.
- Inside a card, "behind" means the blurred backdrop at that spot *plus* the card's tint.
- Levels keep their hierarchy whatever the ink: primary 100 %, secondary 66 %, tertiary 42 %, hairlines 16 %.
- Accent: one red, **#D71921**, for "now", the live dot, record, overdue and the active page dot. Event and
  calendar colours stay as they are.
- Settings → Widgets → Text colour: Auto (default), White, Black.

## 4. Type

| Role | Face | Size |
| --- | --- | --- |
| Hero number (temperature, date, count) | dot matrix (5×7 dots) | cell 5–7 dp |
| Title | sans, medium | 15 sp (square 14) |
| Body | sans | 13 sp |
| Caption / labels | monospace caps, tracking 0.14 | 10.5 sp |

Titles are one line (two in a square when that is all there is), then ellipsis.

## 5. Layout inside a cell

- Padding 16 dp (square 14 dp). Header row on top: caption + optional right value, 18 dp tall.
- Content scrolls **inside** the widget: lists scroll freely; Weather turns **pages vertically** (snap).
  A thin vertical indicator on the right edge shows the page / scroll position; the bottom edge fades out
  when there is more to see.
- Scroll positions are kept while Home is open; a minute refresh never jumps you back to the top.
- Touch targets ≥ 40 dp. Tap opens the full app screen; long-press anywhere arranges the stack.

## 6. Elements

| Element | Use |
| --- | --- |
| Dot-matrix numerals | temperature, day of month, counts, percentages |
| Dot bars | hourly temperature and rain chance: each hour is a column of dots |
| Dot meter | a row of 10 dots, lit by value (humidity, UV, rain, progress) |
| Range bar | a week's lows/highs: a dotted track with a solid segment from min to max, today marked |
| Ring | habit progress for the period, tap to check in |
| Progress bar | project progress with the even-pace tick |
| Day strip | seven days, a dot under each day that has events, today in red |
| Event row | colour bar, title, time; the live event gets a red "now" dot |
| Check circle | tick a to-do from the widget |
| Chips | hairline capsules for actions (Write, To-do) |

## 7. The widgets

- **Calendar** (all calendars on the phone, 14 days). Wide: dot-matrix day, weekday, day strip on the left;
  the agenda on the right, grouped Today / Tomorrow / weekday, scrolling. Square: date header + agenda.
  Calendars that aren't syncing are flagged with a tap-to-fix line.
- **To-dos**: overdue, today, upcoming and undated, in that order, scrolling; tick to complete.
  Wide adds a dot-matrix count of today's to-dos.
- **Goals**: habits with rings and their week as dots, projects with bars; scrolling.
- **Weather** pages: 1 · Now (dot-matrix temperature, condition, high/low, feels like) · 2 · Next hours
  (dot bars for temperature and rain) · 3 · Week (range bars) · 4 · Details (humidity, wind, UV, sunrise,
  sunset as dot meters).
- **Capture**: big red record button, Write and To-do chips, recent captures scrolling.
- **Music**: the cover, frosted, fills the card; title, artist, live progress, controls. Kept while paused.
- **App widgets**: shown inside the same glass with their real size.

## 8. Cost

- Backdrop sampled once per change of backdrop, at 1/8 resolution, pre-blurred once; drawing a card is one
  bitmap draw.
- Ink is recomputed after layout or data changes only (debounced), never per frame.
- Data refresh: once a minute while Home is visible, only widgets whose data changed are redrawn.
  Weather at most every 30 minutes. Nothing runs while Home is hidden.
