# Toolchain

Recorded from the upstream baseline (Olauncher `cc6b728`, v6.10.27) and kept unchanged in Waypoint.

| Tool | Version | Source |
|---|---|---|
| Gradle (wrapper) | 8.11.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 8.9.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.1.20 | `gradle/libs.versions.toml` |
| compileSdk / targetSdk | 36 / 36 | `app/build.gradle` |
| minSdk | 24 (unchanged; `java.time` via core library desugaring) | `app/build.gradle` |
| Java / JVM target | 17 | `app/build.gradle` |
| CI JDK | Temurin 17 | `.github/workflows/android.yml` |
| Desugaring | `com.android.tools:desugar_jdk_libs:2.1.5` | added by Waypoint |

## Build

```bash
./gradlew testDebugUnitTest assembleRelease lintDebug
```

The release APK is signed with `WAYPOINT_KEYSTORE_*` environment variables when present (CI secrets:
`WAYPOINT_KEYSTORE_B64`, `WAYPOINT_KEYSTORE_PASSWORD`, `WAYPOINT_KEY_ALIAS`, `WAYPOINT_KEY_PASSWORD`).
Without them it falls back to `signing/sideload.keystore`, a **public, non-secret** key whose only
purpose is to let sideloaded builds update each other. Never use it for a store listing.

## CI

`.github/workflows/android.yml` runs unit tests, builds the release APK, runs lint, uploads reports,
and commits the APK to `apk/Waypoint.apk` with `apk/BUILD_INFO.txt` (commit + SHA-256).
