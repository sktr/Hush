# Autoplay Blocker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** BeatBridgeを最小構成の自動再生ブロッカーに作り替える。接続時ミュート→停止→音量復帰でSpotify等の勝手再生を無音で潰す。EQ・Ask-on-connect・アプリ起動・起動遅延は削除し、残すのはBTデバイス選択のみ

**Architecture:** `BluetoothMonitorService.triggerMediaPlay()` が送る `KEYCODE_MEDIA_PLAY` を `KEYCODE_MEDIA_PAUSE` に変え、前後で `AudioManager` の音楽ストリーム音量を退避・0化・復帰させる。判断ロジックはpureな `PlaybackBlocker` に切り出し、Serviceは配線だけにする。機能追加と削除は別コミットに分ける (同一変更に混ぜない)。

**Tech Stack:** Kotlin, Android SDK (AudioManager, BluetoothDevice.ACTION_ACL_CONNECTED), JUnit4 (既存 `app/src/test`)

**Spec:** ユーザー要望: 車ナビにBT接続されるのは良いがSpotify自動再生だけ止めたい。一瞬の音漏れもミュート区間に吸収する。対象コードは `app/src/main/java/com/beatbridge/BluetoothMonitorService.kt:255-260` と `NotificationActionActivity.kt:27-31`。

## Global Constraints

- 既存APIスキーマを勝手に変えない
- 本番コードはテストなしで書かない (TDD)
- リファクタと機能追加を同一変更に混ぜない
- 警告・エラーがある状態でコミットしない
- コミットメッセージは英語・conventional commits形式

---

### Task 1: PlaybackBlockerのpureロジック + 単体テスト

**Files:**
- Create: `app/src/main/java/com/beatbridge/PlaybackBlocker.kt`
- Test: `app/src/test/java/com/beatbridge/PlaybackBlockerTest.kt`

**Interfaces:**
- Consumes: なし (Android Frameworkに依存しないpure logic)
- Produces: `object PlaybackBlocker { fun pauseKeyEvents(): List<Pair<Int, Int>>, fun shouldRestoreVolume(isPlaying: Boolean): Boolean, const val MUTE_VOLUME: Int, const val PAUSE_RETRIES: Int, const val RESTORE_DELAY_MS: Long }` — Task 2のService配線が使う

- [ ] **Step 1: Write the failing test**

```kotlin
package com.beatbridge

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBlockerTest {

    @Test
    fun pauseSequenceUsesPauseKeyDownAndUp() {
        val events = PlaybackBlocker.pauseKeyEvents()
        assertEquals(2, events.size)
        assertEquals(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_PAUSE, events[0])
        assertEquals(KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_PAUSE, events[1])
    }

    @Test
    fun restoresVolumeOnlyWhenStopped() {
        assertTrue(PlaybackBlocker.shouldRestoreVolume(isPlaying = false))
        assertFalse(PlaybackBlocker.shouldRestoreVolume(isPlaying = true))
    }

    @Test
    fun muteVolumeIsZero() {
        assertEquals(0, PlaybackBlocker.MUTE_VOLUME)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.beatbridge.PlaybackBlockerTest" 2>&1 | tail -20`
Expected: FAIL with "unresolved reference: PlaybackBlocker"

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.beatbridge

import android.view.KeyEvent

object PlaybackBlocker {
    const val MUTE_VOLUME: Int = 0
    const val PAUSE_RETRIES: Int = 2
    const val PAUSE_RETRY_DELAY_MS: Long = 1_000L
    const val RESTORE_DELAY_MS: Long = 4_000L

    fun pauseKeyEvents(): List<Pair<Int, Int>> = listOf(
        KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_PAUSE,
    )

    fun shouldRestoreVolume(isPlaying: Boolean): Boolean = !isPlaying
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.beatbridge.PlaybackBlockerTest" 2>&1 | tail -10`
Expected: PASS (BUILD SUCCESSFUL)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/beatbridge/PlaybackBlocker.kt app/src/test/java/com/beatbridge/PlaybackBlockerTest.kt
git commit -m "feat: add PlaybackBlocker pause policy with tests"
```

### Task 2: BluetoothMonitorServiceの再生抑止配線 (機能追加のみ・削除は混ぜない)

**Files:**
- Modify: `app/src/main/java/com/beatbridge/BluetoothMonitorService.kt` (`triggerMediaPlay` 付近 255-260行のみ。`handleDeviceConnected` の呼び出し2箇所の関数名置換)
- Test: `app/src/test/java/com/beatbridge/BluetoothMonitorServiceTest.kt` (既存の重複排除テストが壊れていないことの回帰確認)

**Interfaces:**
- Consumes: Task 1の `PlaybackBlocker.pauseKeyEvents()`, `MUTE_VOLUME`, `PAUSE_RETRIES`, `RESTORE_DELAY_MS`, `shouldRestoreVolume()`
- Produces: `private fun suppressAutoplay()` — 接続時のデフォルト動作がミュート→Pause→復帰になる。このタスクではEQ・Ask・アプリ起動・遅延ロジックに一切触らない (削除はTask 3)

- [ ] **Step 1: Write the failing test (回帰テストの存在確認)**

既存テストが通ることを確認する (新規振る舞いの直接テストはRobolectricなしではAudioManagerがmockできないため、Task 1のpure logicでカバーしServiceは配線のみにする):

Run: `./gradlew :app:testDebugUnitTest --tests "com.beatbridge.BluetoothMonitorServiceTest" 2>&1 | tail -10`
Expected: PASS (現状維持のベースライン)

- [ ] **Step 2: Implement suppressAutoplay配線**

`triggerMediaPlay()` を以下に置き換える (シグネチャ変更なし・呼び出し側2箇所はそのまま動く):

```kotlin
private fun suppressAutoplay() {
    val audioManager = getSystemService(AudioManager::class.java) ?: return
    val previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, PlaybackBlocker.MUTE_VOLUME, 0)

    fun dispatchPause() {
        for ((action, keyCode) in PlaybackBlocker.pauseKeyEvents()) {
            audioManager.dispatchMediaKeyEvent(KeyEvent(action, keyCode))
        }
        Log.i(TAG, "MEDIA_PAUSE dispatched")
    }

    dispatchPause()
    handler.postDelayed({
        dispatchPause()
        handler.postDelayed({
            if (PlaybackBlocker.shouldRestoreVolume(audioManager.isMusicActive)) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
                Log.i(TAG, "Volume restored to $previousVolume")
            }
        }, PlaybackBlocker.RESTORE_DELAY_MS)
    }, PlaybackBlocker.PAUSE_RETRY_DELAY_MS)
}
```

呼び出し側の変更は `triggerMediaPlay()` → `suppressAutoplay()` のリネームのみ (`handleDeviceConnected` のelse分岐と `launchAppsSequentially` 完了時)。EQ・Ask・起動遅延ロジックには触らない。

- [ ] **Step 3: Run tests**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -10`
Expected: PASS (全テスト成功)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/beatbridge/BluetoothMonitorService.kt
git commit -m "feat: suppress autoplay with mute-pause-restore on connect"
```

### Task 3: 最小構成化 — EQ/Ask/アプリ起動/起動遅延の削除 (削除のみ・振る舞い変更なし)

残すもの: BTデバイス選択 (`PREF_SELECTED_DEVICES` / `PREF_ANY_DEVICE`)、重複排除、CompanionDevice対応、Foreground常駐通知、言語切替。
削除するもの: EQ (`applyEqualizer`/`releaseEqualizer`/`DeviceEqualizerActivity`/`PREF_DEVICE_EQ_PREFIX`)、Ask-on-connect (`showDeviceChoices`/`choicePendingIntent`/`PREF_DEVICE_ASK_PREFIX`/通知チャネル`ACTIONS_CHANNEL_ID`/`NotificationActionActivity`)、アプリ起動 (`launchAppsSequentially`/`launchApp`/`DeviceAppsActivity`/`AppAdapter`/`MusicApp`/`normalizeMusicApps`/`PREF_SELECTED_APPS`/`PREF_DEVICE_APPS_PREFIX`/`effectiveAppSelection`)、起動遅延スライダー (`PREF_LAUNCH_DELAY`)。

**Files:**
- Modify: `app/src/main/java/com/beatbridge/BluetoothMonitorService.kt` (上記関数の削除、`handleDeviceConnected` を選択判定→`suppressAutoplay()` のみに簡素化)
- Modify: `app/src/main/java/com/beatbridge/MainActivity.kt` (アプリ一覧・遅延スライダー・`onConfigure`・不要なcompanion関数/定数の削除)
- Modify: `app/src/main/AndroidManifest.xml` (`DeviceAppsActivity`/`DeviceEqualizerActivity`/`NotificationActionActivity` のactivity宣言削除)
- Delete: `app/src/main/java/com/beatbridge/NotificationActionActivity.kt`, `DeviceAppsActivity.kt`, `DeviceEqualizerActivity.kt`, `AppAdapter.kt` (参照が残っていないことをgrepで確認後に削除)
- Modify: `app/src/test/java/com/beatbridge/MainActivityPrefsTest.kt` (削除したPREF定数・`effectiveAppSelection`・`normalizeMusicApps` を参照するテストを削除。残す: `PREFS_NAME`/`PREF_SELECTED_DEVICES`/`PREF_ANY_DEVICE`/言語/`requiredBluetoothPermissions`/`shouldMonitor`)
- Test: `./gradlew :app:testDebugUnitTest` 全件回帰

**Interfaces:**
- Consumes: Task 2の `suppressAutoplay()`
- Produces: 最小構成のブロッカー。Task 2の抑止振る舞いは変えない (削除のみ)

- [ ] **Step 1: 参照箇所の洗い出し**

Run: `grep -rn "PREF_SELECTED_APPS\|PREF_LAUNCH_DELAY\|PREF_DEVICE_APPS_PREFIX\|PREF_DEVICE_ASK_PREFIX\|PREF_DEVICE_EQ_PREFIX\|effectiveAppSelection\|normalizeMusicApps\|MusicApp\|NotificationActionActivity\|DeviceAppsActivity\|DeviceEqualizerActivity\|showDeviceChoices\|launchAppsSequentially\|applyEqualizer" app/src/main --include="*.kt" --include="*.xml"`
Expected: 削除対象の全参照リスト (このリストを潰し切る)

- [ ] **Step 2: テストを先に更新 (TDDのRED)**

`MainActivityPrefsTest.kt` から削除対象を参照するテスト (`prefSelectedAppsKey`/`prefLaunchDelayKey`/`prefDeviceAskPrefix`/`prefDeviceEqPrefix`/`deviceAppSelectionInherits...`/`explicitEmpty...`/`appPickerDeduplicates...`/`prefKeys_areDistinct`内の削除キー) を削除する。

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -10`
Expected: FAIL (本番コードがまだ削除対象を参照するためコンパイルエラーになる — 正しい失敗。typoではなく参照除去が原因であることを確認)

- [ ] **Step 3: 本番コードから削除 (GREEN)**

Service・MainActivity・Manifestから削除対象を除去し、不要Activity/AppAdapterファイルを削除する。

- [ ] **Step 4: Run tests to verify it passes**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -10`
Expected: PASS (BUILD SUCCESSFUL)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: strip EQ, ask-on-connect, app launch and delay for minimal blocker"
```

### Task 4: 通知アクション整理 + 最終ビルド検証

Ask通知チャネル (`ACTIONS_CHANNEL_ID`) と `ACTIONS_NOTIFICATION_ID` がTask 3の削除で未参照になっていれば除去する (参照が残っていればこのタスクで除去)。残っていなければこのタスクは検証のみ。

- [ ] **Step 1: Run full verification**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, 警告・エラーなし

- [ ] **Step 2: Commit (変更があればのみ)**

```bash
git add -A
git commit -m "chore: remove unused ask notification channel"
```
変更がなければコミットなし。

## Self-Review

- [x] Spec coverage: ミュート→停止→復帰の3要素はTask 2で実装、判断pure化はTask 1、手動潰しはTask 3。EQ/Ask/起動遅延は触らない。
- [x] Placeholder scan: TBD/TODO/適宜などの曖昧表現なし。全ステップに実コードと実コマンドあり。
- [x] Type consistency: `pauseKeyEvents(): List<Pair<Int, Int>>` をTask 1で定義しTask 2・3で同一シグネチャで使用。`MUTE_VOLUME: Int` と `setStreamVolume(Int)` の型一致。
