package com.tg.ghostd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pure JVM tests for per-capture grant tokens (no Android runtime).
 *
 * These cover the stale-state defects: a cancelled/timed-out request must
 * clear its pending state, a late result/cancel from an old consent sheet
 * must be ignored, and a newer request must never be completed by an older
 * token.
 */
class ProjectionHubTest {

    @After
    fun cleanup() {
        ProjectionHub.stop()
    }

    @Test
    fun `fresh request is leader and follower shares the pending token`() {
        val leader = ProjectionHub.beginGrantRequest()
        assertTrue(leader.leader)
        assertTrue(leader.token > 0L)
        assertEquals(leader.token, ProjectionHub.pendingTokenForTest())

        val follower = ProjectionHub.beginGrantRequest()
        assertFalse(follower.leader)
        assertEquals(leader.token, follower.token)
        assertSame(leader.pending, follower.pending)
        // Neither may hold up a later capture forever: the follower's wait is
        // released by the leader's terminal transition, not by its own token.
        assertEquals(1L, follower.pending!!.latch.count)
    }

    @Test
    fun `cancel clears pending state and wakes followers`() {
        val leader = ProjectionHub.beginGrantRequest()
        val follower = ProjectionHub.beginGrantRequest()

        ProjectionHub.onGrantCancelled(leader.token)

        assertEquals(0L, ProjectionHub.pendingTokenForTest())
        assertEquals(0L, follower.pending!!.latch.count)
        assertTrue(follower.pending!!.cancelled)
        // Duplicate/late cancellation from the same (dead) sheet is a no-op.
        ProjectionHub.onGrantCancelled(leader.token)
        assertEquals(0L, ProjectionHub.pendingTokenForTest())
    }

    @Test
    fun `new request after cancel gets a strictly newer token`() {
        val first = ProjectionHub.beginGrantRequest()
        ProjectionHub.onGrantCancelled(first.token)

        val second = ProjectionHub.beginGrantRequest()
        assertTrue(second.leader)
        assertTrue(second.token > first.token)
        assertEquals(second.token, ProjectionHub.pendingTokenForTest())
    }

    @Test
    fun `late result from an old token cannot complete the current request`() {
        val old = ProjectionHub.beginGrantRequest()
        ProjectionHub.onGrantCancelled(old.token)

        val current = ProjectionHub.beginGrantRequest()
        ProjectionHub.onGrant(old.token, 0, null)  // stale consent sheet

        assertEquals(current.token, ProjectionHub.pendingTokenForTest())
        assertEquals(1L, current.pending!!.latch.count)
        assertFalse(current.pending!!.cancelled)
    }

    @Test
    fun `result with the matching token clears the request exactly once`() {
        val request = ProjectionHub.beginGrantRequest()
        ProjectionHub.onGrant(request.token, 0, null)

        assertEquals(0L, ProjectionHub.pendingTokenForTest())
        assertEquals(0L, request.pending!!.latch.count)
        // Late duplicate result: nothing left to complete, no crash.
        ProjectionHub.onGrant(request.token, 0, null)
        assertEquals(0L, ProjectionHub.pendingTokenForTest())
    }

    @Test
    fun `stop cancels a pending request and clears waiter state`() {
        val request = ProjectionHub.beginGrantRequest()
        ProjectionHub.stop()

        assertEquals(0L, ProjectionHub.pendingTokenForTest())
        assertEquals(0L, request.pending!!.latch.count)
        assertTrue(request.pending!!.cancelled)
        // A newer capture starts from clean state after the stop.
        val next = ProjectionHub.beginGrantRequest()
        assertTrue(next.leader)
        assertTrue(next.token > request.token)
    }

    @Test
    fun `follower wait leaves promptly when the leader is cancelled`() {
        val leader = ProjectionHub.beginGrantRequest()
        val follower = ProjectionHub.beginGrantRequest()
        ProjectionHub.onGrantCancelled(leader.token)

        // The follower's latch is released immediately (bounded, not 120 s).
        assertTrue(follower.pending!!.latch.await(50, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `pre-consent arm expects a projection with a short ttl`() {
        val msg = ProjectionHub.consentArmMessage()
        assertEquals("arm", msg.getString("cmd"))
        assertEquals("projection", msg.getString("expect"))
        assertEquals(15_000, msg.getInt("ttl_ms"))
    }
}
