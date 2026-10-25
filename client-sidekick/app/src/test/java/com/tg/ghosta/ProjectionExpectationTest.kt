package com.tg.ghosta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionExpectationTest {

    private val expectation = ProjectionExpectation()

    @Test
    fun `nothing is expected before ghostd asks`() {
        assertFalse(expectation.isActive(0))
        assertFalse(expectation.isActive(1_000))
        assertEquals(0L, expectation.remainingMs(1_000))
    }

    @Test
    fun `expectation is active only inside its ttl`() {
        expectation.expect(nowMs = 10_000, ttlMs = 15_000)
        assertTrue(expectation.isActive(10_000))
        assertTrue(expectation.isActive(24_999))
        // Expiry is exclusive: one millisecond later it is gone.
        assertFalse(expectation.isActive(25_000))
        assertFalse(expectation.isActive(60_000))
        assertEquals(5_000L, expectation.remainingMs(20_000))
        assertEquals(0L, expectation.remainingMs(25_000))
    }

    @Test
    fun `a missing or nonsensical ttl falls back to the default`() {
        val expires = expectation.expect(nowMs = 0, ttlMs = null)
        assertEquals(ProjectionExpectation.DEFAULT_TTL_MS, expires)
        assertTrue(expectation.isActive(ProjectionExpectation.DEFAULT_TTL_MS - 1))
        assertFalse(expectation.isActive(ProjectionExpectation.DEFAULT_TTL_MS))

        expectation.expect(nowMs = 0, ttlMs = 0)
        assertTrue(expectation.isActive(1))
        expectation.expect(nowMs = 0, ttlMs = -5_000)
        assertTrue(expectation.isActive(1))
    }

    @Test
    fun `a requested ttl is clamped to the maximum`() {
        val expires = expectation.expect(nowMs = 0, ttlMs = 10 * 60_000)
        assertEquals(ProjectionExpectation.MAX_TTL_MS, expires)
        assertTrue(expectation.isActive(ProjectionExpectation.MAX_TTL_MS - 1))
        assertFalse(expectation.isActive(ProjectionExpectation.MAX_TTL_MS))
    }

    @Test
    fun `a later request replaces the earlier window`() {
        expectation.expect(nowMs = 1_000, ttlMs = 15_000)
        expectation.expect(nowMs = 9_000, ttlMs = 15_000)
        // The first request's expiry would have been 16_000; the refreshed one
        // keeps the expectation valid past it, but never forever.
        assertTrue(expectation.isActive(20_000))
        assertFalse(expectation.isActive(24_000))
    }

    @Test
    fun `clear drops a live expectation`() {
        expectation.expect(nowMs = 1_000, ttlMs = 15_000)
        assertTrue(expectation.isActive(2_000))
        expectation.clear()
        assertFalse(expectation.isActive(2_000))
        assertEquals(0L, expectation.remainingMs(2_000))
    }

    @Test
    fun `an expired expectation cannot be revived by reading it`() {
        expectation.expect(nowMs = 1_000, ttlMs = 1_000)
        assertFalse(expectation.isActive(2_000))
        assertFalse(expectation.isActive(2_001))
        assertEquals(0L, expectation.remainingMs(2_001))
    }
}
