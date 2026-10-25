package com.tg.ghostd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the MediaBus single-capture-slot ("busy") lock and its
 * force-release, plus ScreenCapturer.stop()'s wedge recovery. These cover
 * the 0.2.8 bug: a capture error/wedge must never hold the busy slot
 * permanently (ctx is null -> no PowerManager/wake lock, so no Android
 * runtime is needed).
 */
class MediaBusTest {

    /** Screen-awake lock is injected so stream sessions are observable here. */
    private class FakeAwakeLock : ScreenAwakeController.Lock {
        var held = false
        var releases = 0

        override fun acquire(timeoutMs: Long): Boolean {
            held = true
            return true
        }

        override fun release() {
            releases++
            held = false
        }
    }

    private val awakeLock = FakeAwakeLock()
    private var now = 1_000L
    private var renewTick: (() -> Unit)? = null

    @Before
    fun setUpAwake() {
        ScreenAwakeController.releaseAll()
        ScreenAwakeController.lockSource = { awakeLock }
        ScreenAwakeController.scheduler = ScreenAwakeController.Scheduler { _, task ->
            renewTick = task
            AutoCloseable { renewTick = null }
        }
        ScreenAwakeController.clock = { now }
    }

    @After
    fun cleanup() {
        MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
        MediaBus.forceEnd(MediaBus.SOURCE_CAM)
        MediaBus.forceEnd(MediaBus.SOURCE_MIC)
        ScreenAwakeController.releaseAll()
        ScreenAwakeController.resetHooks()
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

    @Test
    fun `a stream opens one screen-awake session and end releases it`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        assertEquals(1, ScreenAwakeController.activeCount())
        assertTrue(awakeLock.held)

        MediaBus.end(MediaBus.SOURCE_SCREEN)
        assertEquals(0, ScreenAwakeController.activeCount())
        assertFalse(awakeLock.held)
    }

    @Test
    fun `end of another source never releases the stream session`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        MediaBus.end(MediaBus.SOURCE_CAM)
        assertEquals(1, ScreenAwakeController.activeCount())
        assertTrue(MediaBus.isActive(MediaBus.SOURCE_SCREEN))

        MediaBus.end(MediaBus.SOURCE_SCREEN)
        assertEquals(0, ScreenAwakeController.activeCount())
    }

    @Test
    fun `frames refresh the stream session so it survives the idle cap`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)

        now += ScreenAwakeController.IDLE_TIMEOUT_MS - 1
        MediaBus.sendFrame("screen-live", byteArrayOf(1, 2, 3))   // activity

        now += ScreenAwakeController.IDLE_TIMEOUT_MS - 1
        renewTick?.invoke()
        assertEquals(1, ScreenAwakeController.activeCount())
        assertTrue(awakeLock.held)
        assertTrue(MediaBus.isActive(MediaBus.SOURCE_SCREEN))

        MediaBus.end(MediaBus.SOURCE_SCREEN)
        assertEquals(0, ScreenAwakeController.activeCount())
    }

    @Test
    fun `a stalled stream session idle-expires but keeps the capture slot`() {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)

        now += ScreenAwakeController.IDLE_TIMEOUT_MS
        renewTick?.invoke()

        // screen-awake released; the stream/CPU lock are a separate concern
        assertEquals(0, ScreenAwakeController.activeCount())
        assertFalse(awakeLock.held)
        assertTrue(MediaBus.isActive(MediaBus.SOURCE_SCREEN))

        // frames after the expiry cannot resurrect the session
        MediaBus.sendFrame("screen-live", byteArrayOf(1))
        assertEquals(0, ScreenAwakeController.activeCount())
        assertFalse(awakeLock.held)

        MediaBus.end(MediaBus.SOURCE_SCREEN)
        assertNull(MediaBus.activeSource)
    }

    @Test
    fun `stale end and forceEnd from an older generation are no-ops`() {
        val first = MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
        assertEquals(0, ScreenAwakeController.activeCount())

        val second = MediaBus.begin(MediaBus.SOURCE_SCREEN, null, null)
        assertTrue(second > first)

        // A wedged predecessor finally unwinds: it must not tear down the
        // newer stream or its screen-awake session.
        MediaBus.end(MediaBus.SOURCE_SCREEN, first)
        MediaBus.forceEnd(MediaBus.SOURCE_SCREEN, first)
        assertTrue(MediaBus.isActive(MediaBus.SOURCE_SCREEN))
        assertEquals(1, ScreenAwakeController.activeCount())
        assertTrue(awakeLock.held)

        MediaBus.end(MediaBus.SOURCE_SCREEN, second)
        assertNull(MediaBus.activeSource)
        assertEquals(0, ScreenAwakeController.activeCount())
    }
}
