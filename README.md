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

<div dir="rtl">

### نصب (فارسی)
۱. فایل [apk/Waypoint.apk](apk/Waypoint.apk) را روی گوشی دانلود و باز کنید و اجازهٔ نصب از این منبع را بدهید.
۲. دکمهٔ Home را بزنید و **Waypoint** را به‌عنوان لانچر پیش‌فرض انتخاب کنید.
۳. برای تنظیمات، روی صفحهٔ اصلی انگشت را نگه دارید. بخش **Waypoint** شامل ساعت جهانی، ژست‌ها، ظاهر (تم شیشه‌ای)، اعلان‌ها و دفتر تراکنش، خروجی CSV و حریم خصوصی است.
۴. **تم شیشه‌ای (الهام از Nothing):** تنظیمات ← Waypoint ← Appearance ← Glass. کل والپیپر پشت همهٔ صفحه‌ها بلور می‌شود، ساعت با فونت نقطه‌ای، متن‌های مونو و یک نقطهٔ قرمز. بلور واقعی نیاز به اندروید ۱۲ به بالا دارد.
۵. **چیدمان صفحهٔ اصلی:** اپ‌ها به‌طور پیش‌فرض سمت راست هستند. در Appearance با اسلایدر **Apps position** ارتفاع بخش اپ‌ها را از بالا تا پایین تنظیم کنید؛ جای آن ثابت می‌ماند.
۶. **ساعت جهانی:** وقتی بیش از یک شهر اضافه کنید، روی ساعت زیر تاریخ به چپ و راست بکشید تا بین شهرها جابه‌جا شوید.
۷. **پرسیدن (؟):** در جستجوی لیست اپ‌ها `؟` بزنید تا کادر بزرگ سؤال باز شود. در تنظیمات ← Assistant & chats انتخاب کنید سؤال به **اپ Gemini** برود یا به **API خودتان** (آدرس، مدل و کلید API؛ OpenAI، Gemini API، Claude، OpenRouter، Groq یا Ollama). در حالت API، گفتگو در اپ **Wayfinder** (در لیست اپ‌ها و دکمهٔ Wayfinder در گوشهٔ صفحهٔ اصلی) ذخیره می‌شود و می‌توانید ادامه‌اش بدهید.
۸. **جواب فوری و ماشین‌حساب:** در جستجو بنویسید: `12×3+4`، `sin(30°)`، `15% of 80`، `2 m3 to l`، `100 km/h to mph`، `time in tokyo` یا ارز مثل `1$ to £` و `100 usd in toman`. با `=` در ابتدا، حالت ماشین‌حساب اجباری می‌شود. جواب با لمس یا Enter کپی می‌شود.
۹. **نمودار (مثل Desmos):** در جستجو `=sin(x)` یا `x^2+y^2=r` بنویسید؛ نمودار کوچک نشان داده می‌شود (حروف ناشناخته مثل r اسلایدر می‌شوند) و با لمس، فقط همان معادله در صفحهٔ نمودار باز می‌شود. نامساوی‌ها (`y > x^2`)، نقاط تلاقی (نقطهٔ خاکستری؛ لمس = مختصات)، با لمس منحنی ریشه‌ها و ماکزیمم/مینیمم، چند معادله، اسلایدر، `f(x)=…`، زوم و کشیدن.
۱۰. **نرخ ارز آنلاین:** فقط وقتی تبدیل ارز تایپ کنید، نرخ‌های عمومی دلار از open.er-api.com دانلود می‌شود (حداکثر هر ۶ ساعت). چیزی از متن شما ارسال نمی‌شود. خاموش‌کردن در Privacy & data. نرخ ریال/تومان نرخ رسمی است، نه بازار آزاد.
۱۱. **Space (یادداشت صوتی، مثل Essential Space):** در Wayfinder ← Space دکمهٔ قرمز را بزنید (یا دکمهٔ Wayfinder را در صفحهٔ اصلی نگه دارید) تا ضبط شروع شود. صدا روی گوشی ذخیره می‌شود و **روی خود گوشی** (بدون اینترنت، با تشخیص گفتار داخلی اندروید، اندروید ۱۳ به بالا) به متن تبدیل می‌شود؛ زبان را یک بار در تنظیمات Wayfinder ← Space دانلود کنید. عنوان و خلاصهٔ هوشمند اختیاری است (فقط متن به API شما می‌رود).
۱۲. **About you:** در تنظیمات Wayfinder اطلاعاتی دربارهٔ خودتان بنویسید؛ دستیار فقط وقتی مرتبط باشد از آن استفاده می‌کند.

</div>

## What's new compared to Olauncher

Everything is **off or idle until you use it**. A feature you never enable creates no objects, opens
no database, registers no receivers and schedules no work.

| Feature | Where |
|---|---|
| **Glass theme** (Nothing-inspired): the whole wallpaper blurred behind every screen, dot-matrix clock, monospaced captions, one red accent, hairline glass cards and search capsule | Settings → Waypoint → Appearance |
| **World clock slider**: cities under the date; swipe sideways between them (page dots), DST-correct `+1 day` offsets, full panel, offline city search | Settings → Waypoint → World clock |
| **Corner swipe** (opt-in): from just below the top corner, swipe down/inward (default: toggle monochrome). A pull from the very top edge always opens notifications | Settings → Waypoint → Gestures |
| **Ask** with `?` in the drawer search: a large multi-line composer. Questions go to the **Gemini app**, or to **your own API** (OpenAI-compatible or Anthropic: OpenAI, Gemini API, Claude, OpenRouter, Groq, Ollama…) | App drawer search; Settings → Assistant & chats |
| **Wayfinder** app (in your app list, and a Wayfinder button on Home): chats with your API (streamed, saved, continue/rename/delete, Markdown, images, *About you*) and **Space** voice notes (record → on-device transcription → optional AI title + summary; like Essential Space) | App list → Wayfinder; Home corner |
| **Quick answers** (Spotlight-style): `12×3+4`, `sin(30°)`, `ln 2`, `5!`, `15% of 80`, units with powers and compounds (`2 m3 to l`, `1 m² to sq ft`, `100 km/h to mph`, `1 kWh to MJ`), `time in tokyo`, currencies (`1$ to £`, `100 usd in toman`). Start with `=` for calculator mode. Tap or Enter copies | App drawer search |
| **Graphing calculator** (Desmos-style): many expressions, `y = …`, `f(x) = …`, `x = …`, implicit equations like `x^2 + y^2 = 9`, points `(2, 3)`, sliders (`a = 1`) with one-tap *add slider* for unknown letters; pan, pinch-zoom, double-tap, hold to trace. Typing `sin(x)` in search shows a mini graph | Drawer search, or Settings → Graphing calculator |
| **Home layout**: right-aligned by default; *Apps position* slider moves the app list from top to bottom | Appearance |
| **Launcher monochrome & OLED black**: instant, no recreation, no GPU filters | Appearance |
| **System grayscale**: guidance to Android's own options, plus an optional ADB-granted toggle | Appearance |
| **Notification capture → local ledger**: only from apps you pick; parsed fields only, never raw text | Notifications & ledger |
| **CSV export**: preview, column choice, masked preview, save via the system file picker or share | Export CSV |
| **Privacy & data**: counts of what's stored and one-tap deletion | Privacy & data |

Upstream behaviour is preserved: swipe up for apps, swipe down for notifications, swipe left/right for
apps, long-press for settings, double-tap to lock, 8 home apps, hidden apps, Private Space, and so on.

## Honest limitations
- **System grayscale** can't be switched by a normal app. The advanced toggle needs a one-time
  `adb shell pm grant app.waypoint.launcher android.permission.WRITE_SECURE_SETTINGS` and may not work on every device.
- **Sideloaded installs on Android 13+** can't grant notification access until you allow it in
  App info → ⋮ → *Allow restricted settings*.
- **Android 15+** may hide sensitive notification content (e.g. codes) from listeners; such records are marked *Needs review*.
- Notification formats change without notice: anything ambiguous is marked *Needs review*, never guessed.
- Glass blur needs Android 12+ with cross-window blur available (Android turns it off in battery saver
  and on some GPUs). Without it, Waypoint uses a denser tint so text stays readable.
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
[Quick answers & grapher](docs/quick-answers.md) · [Wayfinder](docs/assistant.md) · [Privacy](PRIVACY.md) · [Changelog](CHANGELOG.md)

## License
GPL-3.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Waypoint is based on Olauncher by tanujnotes.
