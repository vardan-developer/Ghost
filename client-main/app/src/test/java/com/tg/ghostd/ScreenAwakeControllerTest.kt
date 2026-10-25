package com.tg.ghostd

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [ScreenAwakeController]'s reference counting, bounded
 * renewal and release paths. The PowerManager and the timer are injected, so
 * no Android runtime is needed.
 */
class ScreenAwakeControllerTest {

    private class FakeLock : ScreenAwakeController.Lock {
        var acquires = 0
        var releases = 0
        var lastTimeoutMs = 0L
        var failAcquire = false
        var held = false

        override fun acquire(timeoutMs: Long): Boolean {
            acquires++
            lastTimeoutMs = timeoutMs
            if (failAcquire) return false
            held = true
            return true
        }

        override fun release() {
            releases++
            held = false
        }
    }

    private class FakeScheduler : ScreenAwakeController.Scheduler {
        var schedules = 0
        var periodMs = 0L
        var cancelled = false
        private var task: (() -> Unit)? = null

        override fun schedule(periodMs: Long, task: () -> Unit): AutoCloseable {
            schedules++
            this.periodMs = periodMs
            this.task = task
            return AutoCloseable { cancelled = true }
        }

        fun tick() {
            task?.invoke()
        }
    }

    private val fake = FakeLock()
    private val sched = FakeScheduler()
    private var now = 1_000L

    @Before
    fun setUp() {
        ScreenAwakeController.releaseAll()
        fake.held = false
        ScreenAwakeController.lockSource = { fake }
        ScreenAwakeController.scheduler = sched
        ScreenAwakeController.clock = { now }
    }

    @After
    fun tearDown() {
        ScreenAwakeController.releaseAll()
        ScreenAwakeController.resetHooks()
    }

    @Test
    fun `begin acquires a bounded bright session and schedules renewal`() {
        val token = ScreenAwakeController.begin(null)

        assertTrue(ScreenAwakeController.isActive(token))
        assertTrue(fake.held)
        assertEquals(ScreenAwakeController.LOCK_TIMEOUT_MS, fake.lastTimeoutMs)
        assertEquals(1, sched.schedules)
        assertEquals(ScreenAwakeController.RENEW_INTERVAL_MS, sched.periodMs)
    }

    @Test
    fun `concurrent sessions share one lock and release after the last end`() {
        val a = ScreenAwakeController.begin(null)
        val b = ScreenAwakeController.begin(null)
        assertEquals(2, ScreenAwakeController.activeCount())
        assertTrue(fake.held)
        assertEquals(1, sched.schedules)   // one renewal for the whole group

        ScreenAwakeController.end(a)
        assertTrue(fake.held)              // b still active
        assertEquals(0, fake.releases)

        ScreenAwakeController.end(b)
        assertFalse(fake.held)
        assertEquals(1, fake.releases)
        assertEquals(0, ScreenAwakeController.activeCount())
        assertTrue(sched.cancelled)
    }

    @Test
    fun `end is idempotent and a stale token never releases a newer session`() {
        val stale = ScreenAwakeController.begin(null)
        ScreenAwakeController.end(stale)
        assertFalse(fake.held)

        val current = ScreenAwakeController.begin(null)
        ScreenAwakeController.end(stale)   // duplicate / late end

        assertTrue(ScreenAwakeController.isActive(current))
        assertTrue(fake.held)
        assertEquals(1, ScreenAwakeController.activeCount())

        ScreenAwakeController.end(current)
        assertFalse(fake.held)
    }

    @Test
    fun `renewal refreshes while sessions live and stops after release`() {
        val token = ScreenAwakeController.begin(null)
        val before = fake.acquires

        sched.tick()
        assertEquals(before + 1, fake.acquires)   // activity refreshed
        assertEquals(ScreenAwakeController.LOCK_TIMEOUT_MS, fake.lastTimeoutMs)

        ScreenAwakeController.end(token)
        assertTrue(sched.cancelled)
        val afterEnd = fake.acquires
        sched.tick()                              // queued tick after release
        assertEquals(afterEnd, fake.acquires)
        assertFalse(fake.held)
    }

    @Test
    fun `acquire failure never throws and the renewal retries`() {
        fake.failAcquire = true
        val token = ScreenAwakeController.begin(null)   // must not throw
        assertTrue(ScreenAwakeController.isActive(token))
        assertFalse(fake.held)

        fake.failAcquire = false
        sched.tick()
        assertTrue(fake.held)

        ScreenAwakeController.end(token)
        assertFalse(fake.held)
    }

    @Test
    fun `a missing PowerManager still yields a balanced session`() {
        ScreenAwakeController.lockSource = { null }
        val token = ScreenAwakeController.begin(null)
        assertTrue(ScreenAwakeController.isActive(token))
        ScreenAwakeController.end(token)
        assertFalse(ScreenAwakeController.isActive(token))
        assertEquals(0, ScreenAwakeController.activeCount())
    }

    @Test
    fun `forceEnd ends only its own session and is idempotent`() {
        val a = ScreenAwakeController.begin(null)
        val b = ScreenAwakeController.begin(null)

        ScreenAwakeController.forceEnd(a)
        assertFalse(ScreenAwakeController.isActive(a))
        assertTrue(ScreenAwakeController.isActive(b))
        assertTrue(fake.held)

        ScreenAwakeController.forceEnd(a)   // repeated stop
        assertTrue(ScreenAwakeController.isActive(b))

        ScreenAwakeController.end(b)
        assertFalse(fake.held)
    }

    @Test
    fun `an idle session expires and releases without end being called`() {
        val token = ScreenAwakeController.begin(null)
        assertTrue(fake.held)

        now += ScreenAwakeController.IDLE_TIMEOUT_MS
        sched.tick()   // the owner leaked: no end() ever arrives

        assertFalse(ScreenAwakeController.isActive(token))
        assertEquals(0, ScreenAwakeController.activeCount())
        assertFalse(fake.held)
        assertTrue(sched.cancelled)

        // a late frame/end for the expired token cannot resurrect it
        ScreenAwakeController.refresh(token)
        ScreenAwakeController.end(token)
        assertFalse(fake.held)
        assertEquals(0, ScreenAwakeController.activeCount())
    }

    @Test
    fun `refreshed sessions outlive the idle cap`() {
        val token = ScreenAwakeController.begin(null)

        now += ScreenAwakeController.IDLE_TIMEOUT_MS - 1
        ScreenAwakeController.refresh(token)
        now += ScreenAwakeController.IDLE_TIMEOUT_MS - 1
        sched.tick()

        assertTrue(ScreenAwakeController.isActive(token))
        assertTrue(fake.held)
        assertFalse(sched.cancelled)

        // ... and still expires once the activity stops
        now += ScreenAwakeController.IDLE_TIMEOUT_MS
        sched.tick()
        assertFalse(ScreenAwakeController.isActive(token))
        assertFalse(fake.held)
    }

    @Test
    fun `only the expired session is dropped while an active one remains`() {
        val idle = ScreenAwakeController.begin(null)
        now += ScreenAwakeController.IDLE_TIMEOUT_MS / 2
        val active = ScreenAwakeController.begin(null)

        now += ScreenAwakeController.IDLE_TIMEOUT_MS / 2 + 1
        ScreenAwakeController.refresh(active)
        sched.tick()

        assertFalse(ScreenAwakeController.isActive(idle))
        assertTrue(ScreenAwakeController.isActive(active))
        assertTrue(fake.held)

        ScreenAwakeController.end(active)
        assertFalse(fake.held)
    }

    @Test
    fun `refresh of an unknown or zero token is a no-op`() {
        ScreenAwakeController.refresh(0L)
        ScreenAwakeController.refresh(9_999L)
        assertEquals(0, ScreenAwakeController.activeCount())
        assertFalse(fake.held)
    }

    @Test
    fun `releaseAll drops every session (disconnect and destroy)`() {
        val a = ScreenAwakeController.begin(null)
        ScreenAwakeController.begin(null)

        ScreenAwakeController.releaseAll()
        assertEquals(0, ScreenAwakeController.activeCount())
        assertFalse(fake.held)
        assertTrue(sched.cancelled)
        assertEquals(1, fake.releases)

        ScreenAwakeController.end(a)        // late end is a no-op
        assertEquals(1, fake.releases)
        assertFalse(ScreenAwakeController.isActive(a))
    }
}
