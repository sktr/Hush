# AGENTS.md — BeatBridge

Android Kotlin single-module app (`:app`, `com.beatbridge`, minSdk 26 / compile+target 36, Java 17, viewBinding).

## Architecture (autoplay blocker)

- `app/src/main/java/com/beatbridge/BluetoothMonitorService.kt` — foreground service. `ACL_CONNECTED` → `suppressAutoplay()`: mute `STREAM_MUSIC` → dispatch `KEYCODE_MEDIA_PAUSE` → poll `isMusicActive` every `POLL_INTERVAL_MS` (1s) up to `POLL_MAX_ATTEMPTS` (15) → restore volume on stop/timeout/`onDestroy`. Re-entry keeps first saved volume (no overwrite with 0). Connection dedupe per address, 3s window.
- `PlaybackBlocker.kt` — pure logic only (`pauseKeyEvents()`, `shouldContinuePolling()`, volume constants). Keep it Android-framework-free so unit tests stay JVM-only.
- `MainActivity.kt` — device selection prefs (`PREF_SELECTED_DEVICES`, `PREF_ANY_DEVICE`) + `shouldMonitor()` gating for the service. Nothing else belongs there.
- Unit tests: `app/src/test/java/com/beatbridge/` (`PlaybackBlockerTest`, `BluetoothMonitorServiceTest`). E2E/screenshots under `app/src/androidTest/` need an emulator — don't run locally by default.

## Commands

```bash
./gradlew :app:testDebugUnitTest --tests "com.beatbridge.PlaybackBlockerTest"  # single test class
./gradlew :app:testDebugUnitTest      # all unit tests (fast, run first)
./gradlew :app:lintDebug              # lint (must be clean before commit)
./gradlew :app:assembleDebug          # debug APKs
```

- CI (`main.yml`): `./gradlew test` + `assembleDebug` on push/PR to `main`; commits containing `[release-bump]` skip build. Fortnightly scheduled releases.
- `version.properties` is the single source for `versionName`/`versionCode`. Don't hardcode versions in `build.gradle.kts`. The `playStore` Gradle property remaps versionCode (`base * 100 + 51`); default builds use ABI-split codes.
- Fresh shells need `export ANDROID_HOME=$HOME/Library/Android/sdk` before any `./gradlew` invocation, otherwise the build fails with "SDK location not found".
- `scripts/` holds shell guards (`test_bluetooth_permission_guard.sh`, `test_lint_fixes.sh`, `test_reproducible_build_config.sh`) — check CI usage before trusting them blindly.

## APK / release gotchas

- ABI splits are on (`isUniversalApk = false`) → `assembleDebug` emits **4 APKs**, no universal: `app/build/outputs/apk/debug/app-{arm64-v8a,armeabi-v7a,x86_64,x86}-debug.apk`. For real-device testing use **arm64-v8a**. CI's artifact path (`app-debug.apk`) does not match split output — don't rely on it.
- Manual preview pattern used before: `gh release create <tag> --prerelease` with the 4 APKs attached (e.g. `autoplay-blocker-preview1/2`). Work happens on `feat/*` branches; push the branch before cutting the release so APKs match code.
- Plan docs live in `docs/superpowers/plans/` (e.g. `2026-10-01-autoplay-blocker.md`) — read the matching plan before touching blocker logic.
