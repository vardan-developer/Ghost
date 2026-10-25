package com.tg.ghostd

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * Pure state tests for the ImageReader frame admission check. The callback
 * itself needs an Android ImageReader/Image, but the decision that prevents
 * orphaned Images after teardown is plain Kotlin.
 */
class ScreenCapturerStateTest {

    @Test
    fun `frame is admitted only into the live queue of a running session`() {
        val live = Any()
        assertTrue(ScreenCapturer.frameQueueRegistered(false, live, live))
    }

    @Test
    fun `stopped session rejects even its own queue`() {
        val live = Any()
        assertFalse(ScreenCapturer.frameQueueRegistered(true, live, live))
    }

    @Test
    fun `torn-down session rejects a queue that is no longer registered`() {
        val old = Any()
        val current = Any()
        assertFalse(ScreenCapturer.frameQueueRegistered(false, null, old))
        assertFalse(ScreenCapturer.frameQueueRegistered(false, current, old))
    }
}
