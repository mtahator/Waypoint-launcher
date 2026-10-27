# Dependencies added by Waypoint

| Dependency | Scope | Purpose | APK impact | License | Why the platform isn't enough |
|---|---|---|---|---|---|
| `com.android.tools:desugar_jdk_libs:2.1.5` | coreLibraryDesugaring | `java.time` on API 24–25 | small (R8 keeps only used classes) | GPL-2.0 w/ Classpath Exception | `java.time` only exists natively from API 26 (pre-approved in spec 8.1) |
| `androidx.profileinstaller:profileinstaller:1.4.1` | implementation | Applies the Baseline Profile on sideloaded installs (AOT-compiled startup path) | ~30 KB | Apache-2.0 | Pre-approved (spec 8.1); the platform only installs profiles for Play installs |
| Doto font (`res/font/doto_dot.ttf`) | asset | Dot-matrix clock in the Glass theme | 99 KB (static instance ROND=100 wght=700, Latin subset made with fonttools) | SIL OFL 1.1 (`docs/font-license-doto-OFL.txt`) | No dot-matrix system font exists |

## Not added (deliberately)
- **Room / KSP**: spec 8.1 pre-approves Room. Waypoint uses the platform `SQLiteOpenHelper` instead for
  the single ledger table: zero APK cost, no annotation processing, no generated code on the start
  path. The schema is versioned (`LedgerDatabase.VERSION`) with an
  `onUpgrade` hook. **Needs human sign-off** as a deviation from the "Room" architecture decision; if
  Room is required, the repositories are the only classes that change.
- No Compose, Hilt, Gson/Moshi, analytics, or network libraries.
