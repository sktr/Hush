package com.beatbridge

import android.view.KeyEvent

object PlaybackBlocker {
    const val MUTE_VOLUME: Int = 0
    const val PAUSE_RETRIES: Int = 2
    const val PAUSE_RETRY_DELAY_MS: Long = 1_000L
    const val RESTORE_DELAY_MS: Long = 4_000L
    const val POLL_INTERVAL_MS: Long = 1_000L
    const val POLL_MAX_ATTEMPTS: Int = 15

    fun pauseKeyEvents(): List<Pair<Int, Int>> = listOf(
        KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_PAUSE,
    )

    fun stopKeyEvents(): List<Pair<Int, Int>> = listOf(
        KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_STOP,
    )

    fun shouldRestoreVolume(isPlaying: Boolean): Boolean = !isPlaying

    fun shouldContinuePolling(isPlaying: Boolean, attempt: Int): Boolean =
        isPlaying && attempt < POLL_MAX_ATTEMPTS
}
