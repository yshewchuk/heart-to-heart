# AGENTS.md

## Cursor Cloud specific instructions

### Project overview

Heart-to-Heart is an Android native app (Kotlin, Jetpack Compose) with a Firebase Cloud Functions backend (TypeScript). There is no Docker, no CI/CD, and no traditional server. See `README.md` for full details.

| Component | Path | Language | Build command |
|-----------|------|----------|---------------|
| Android app | `app/` | Kotlin | `./gradlew assembleDebug` |
| Firebase Functions | `functions/` | TypeScript | `cd functions && npm run build` |

### Prerequisites

- **JDK 21** (system default, used by Gradle/Kotlin)
- **Android SDK** at `/opt/android-sdk` with `ANDROID_HOME` set; platform 35, build-tools 35.0.0, platform-tools installed
- **Node.js 20** via nvm (for Firebase Functions)
- **Gradle 8.5** wrapper is in the repo (`./gradlew`)

### Key commands

| Task | Command |
|------|---------|
| Build Android debug APK | `./gradlew assembleDebug` |
| Run Android lint | `./gradlew lint` |
| Run Android unit tests | `./gradlew test` |
| Build Firebase Functions | `cd functions && npm run build` |
| Type-check Functions | `cd functions && npx tsc --noEmit` |
| Install Functions deps | `cd functions && npm install` |

### Important caveats

- **`google-services.json`** is git-ignored. Place the real file from Firebase (Android app id **`com.yurishewchuk.hearttoheart`**) at `app/google-services.json` for local builds. GitHub Actions writes it from the repository secret **`GOOGLE_SERVICES_JSON`** (same JSON contents as the file) before `./gradlew assembleRelease`.
- **No unit test source files** currently exist in `app/src/test/` or `app/src/androidTest/`. The `./gradlew test` task succeeds with `NO-SOURCE`.
- The Kotlin compiler produces warnings about unused variables in `MainActivity.kt`, `CategoryButton.kt`, `HistoryScreen.kt`, and `ScanQRScreen.kt`. These are pre-existing and not introduced by environment setup.
- Android instrumentation tests (`connectedAndroidTest`) require a physical device or emulator and cannot run in this headless cloud VM.
- The app has a **local test mode** that works without Firebase pairing — you can trigger local alarms without a backend. This is relevant for on-device manual testing only.
