# AGENTS.md — Hush

Android Kotlin single-module app (`:app`, `com.sktr.hush`, minSdk 26 / compile+target 36, Java 17, viewBinding). Japanese UI only (default `values/`, no other locales).

## Architecture (autoplay blocker)

- `app/src/main/java/com/sktr/hush/BluetoothMonitorService.kt` — foreground service. `ACL_CONNECTED`/A2DP-connected → `suppressAutoplay()`: mute `STREAM_MUSIC` → steal transient audio focus → send `MEDIA_STOP` broadcast + `STOP`/`PAUSE` dispatch while `isMusicActive`, full ~15s window (`POLL_INTERVAL_MS` 1s × `POLL_MAX_ATTEMPTS` 15, no early restore) → restore volume + abandon focus on timeout/`onDestroy`. Saved volume adopts upward only (early trigger reads pre-A2DP 0; system per-device volume adopted later). A2DP bypasses the 3s connection dedupe.
- `PlaybackBlocker.kt` — pure logic only (`pauseKeyEvents()`, `stopKeyEvents()`, `shouldContinuePolling()`, `shouldAdoptVolume()`). Keep it Android-framework-free so unit tests stay JVM-only.
- `DebugLog.kt` + `LogStore.kt` — persistent in-app debug log (logcat tag `Hush`, file `hush-debug.log`, 300 entries / 64KB cap). Service writes key events there; `MainActivity` shows it with share/clear.
- `MainActivity.kt` — device selection prefs (`PREF_SELECTED_DEVICES`, `PREF_ANY_DEVICE`) + `shouldMonitor()` gating for the service. Nothing else belongs there.
- Unit tests: `app/src/test/java/com/sktr/hush/`. E2E/screenshots under `app/src/androidTest/` need an emulator — don't run locally by default.

## Commands

```bash
./gradlew :app:testDebugUnitTest --tests "com.sktr.hush.PlaybackBlockerTest"  # single test class
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
- Plan docs live in `docs/superpowers/plans/` — read the matching plan before touching blocker logic.
