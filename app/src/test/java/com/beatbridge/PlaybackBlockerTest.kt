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

    @Test
    fun keepsPollingWhilePlaying() {
        assertTrue(PlaybackBlocker.shouldContinuePolling(isPlaying = true, attempt = 0))
        assertTrue(
            PlaybackBlocker.shouldContinuePolling(
                isPlaying = true,
                attempt = PlaybackBlocker.POLL_MAX_ATTEMPTS - 1,
            ),
        )
    }

    @Test
    fun stopSequenceUsesStopKeyDownAndUp() {
        val events = PlaybackBlocker.stopKeyEvents()
        assertEquals(2, events.size)
        assertEquals(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_STOP, events[0])
        assertEquals(KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_STOP, events[1])
    }

    @Test
    fun stopsPollingWhenPausedOrTimedOut() {
        assertFalse(PlaybackBlocker.shouldContinuePolling(isPlaying = false, attempt = 0))
        assertFalse(
            PlaybackBlocker.shouldContinuePolling(
                isPlaying = true,
                attempt = PlaybackBlocker.POLL_MAX_ATTEMPTS,
            ),
        )
    }
}
