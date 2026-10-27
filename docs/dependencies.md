# Dependencies added by Waypoint

| Dependency | Scope | Purpose | APK impact | License | Why the platform isn't enough |
|---|---|---|---|---|---|
| `com.android.tools:desugar_jdk_libs:2.1.5` | coreLibraryDesugaring | `java.time` on API 24–25 | small (R8 keeps only used classes) | GPL-2.0 w/ Classpath Exception | `java.time` only exists natively from API 26 (pre-approved in spec 8.1) |
| `org.json:json:20240303` | test only | Real `org.json` for JVM tests of the rule mapper | none (not packaged) | Public domain (JSON License text) | `android.jar` ships stubs that return defaults in unit tests |

## Not added (deliberately)
- **Room / KSP**: spec 8.1 pre-approves Room. Waypoint uses the platform `SQLiteOpenHelper` instead for
  two small tables per database: zero APK cost, no annotation processing, no generated code on the start
  path. The schema is versioned (`AutomationDatabase.VERSION`, `LedgerDatabase.VERSION`) with an
  `onUpgrade` hook. **Needs human sign-off** as a deviation from the "Room" architecture decision; if
  Room is required, the repositories are the only classes that change.
- No Compose, Hilt, Gson/Moshi, analytics, or network libraries.
