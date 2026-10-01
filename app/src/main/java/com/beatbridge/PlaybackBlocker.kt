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
