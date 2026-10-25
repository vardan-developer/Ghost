package com.tg.ghostd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the MediaBus single-capture-slot ("busy") lock and its
 * force-release, plus ScreenCapturer.stop()'s wedge recovery. These cover
 * the 0.2.8 bug: a capture error/wedge must never hold the busy slot
 * permanently (ctx is null -> no PowerManager/wake lock, so no Android
 * runtime is needed).
 */
class MediaBusTest {

    @After
    fun cleanup() {
        MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
        MediaBus.forceEnd(MediaBus.SOURCE_CAM)
        MediaBus.forceEnd(MediaBus.SOURCE_MIC)
    }

    private fun busyMessage(e: Exception): String = e.message ?: ""

    @Test
    fun `begin claims the slot and second source is busy`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        assertTrue(MediaBus.isActive(MediaBus.SOURCE_SCREEN))
        assertEquals("screen", MediaBus.activeSource)

        val e = assertThrows(Exception::class.java) {
            MediaBus.begin(MediaBus.SOURCE_CAM, null, null)
        }
        assertTrue(busyMessage(e), busyMessage(e).startsWith("busy:"))
        assertTrue(busyMessage(e).contains(MediaBus.SOURCE_SCREEN))
        // the failed begin must NOT steal or clear the slot
        assertTrue(MediaBus.isActive(MediaBus.SOURCE_SCREEN))
    }

    @Test
    fun `end releases the slot for the next capture`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        MediaBus.end(MediaBus.SOURCE_SCREEN)
        assertNull(MediaBus.activeSource)
        // next source can claim it now
        MediaBus.begin(MediaBus.SOURCE_CAM, null, null)
        assertTrue(MediaBus.isActive(MediaBus.SOURCE_CAM))
        MediaBus.end(MediaBus.SOURCE_CAM)
    }

    @Test
    fun `forceEnd releases a wedged owner and is idempotent`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
        assertNull(MediaBus.activeSource)
        // second force (e.g. repeated screen stop) is a no-op
        MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
        assertNull(MediaBus.activeSource)
        // slot is immediately re-claimable after a wedge
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        assertTrue(MediaBus.isActive(MediaBus.SOURCE_SCREEN))
        MediaBus.end(MediaBus.SOURCE_SCREEN)
    }

    @Test
    fun `late end after forceEnd is a no-op`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
        // the (formerly) wedged worker finally reaches its finally-block:
        MediaBus.end(MediaBus.SOURCE_SCREEN)
        assertNull(MediaBus.activeSource)
        // ... and must not have re-claimed anything
        assertFalse(MediaBus.isActive(MediaBus.SOURCE_SCREEN))
    }

    @Test
    fun `forceEnd of another source does not clear the slot`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        MediaBus.forceEnd(MediaBus.SOURCE_CAM)
        assertTrue(MediaBus.isActive(MediaBus.SOURCE_SCREEN))
        MediaBus.end(MediaBus.SOURCE_SCREEN)
    }

    @Test
    fun `sendFrame without a socket reports failure`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        assertFalse(MediaBus.sendFrame("screen-live", byteArrayOf(1, 2, 3)))
        MediaBus.end(MediaBus.SOURCE_SCREEN)
    }

    @Test
    fun `screen stop force-releases a wedged busy slot`() {
        // simulate a wedged capture: slot claimed, no worker to unwind it
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        ScreenCapturer.stop()
        assertNull(MediaBus.activeSource)
        // the very next capture can start (the reported Android-16 bug)
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        MediaBus.end(MediaBus.SOURCE_SCREEN)
    }

    @Test
    fun `screen stop is safe when nothing is active`() {
        assertNull(MediaBus.activeSource)
        val r = ScreenCapturer.stop()
        assertEquals("screen", r.optString("stopping"))
        assertNull(MediaBus.activeSource)
    }
}
