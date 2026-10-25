package com.tg.ghostd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests for the shared quality preset table. */
class QualityPresetsTest {

    @Test
    fun `all documented presets exist`() {
        val expected = setOf("low", "med", "high", "480p", "720p", "1080p")
        assertEquals(expected, QualityPresets.names)
    }

    @Test
    fun `lookup falls back to med`() {
        assertEquals(QualityPresets.default, QualityPresets.get(null))
        assertEquals(QualityPresets.default, QualityPresets.get("bogus"))
        assertEquals("720p", QualityPresets.get("720p").name)
    }

    @Test
    fun `preset fields are sane`() {
        for (name in QualityPresets.names) {
            val p = QualityPresets.get(name)
            assertTrue("width ${p.width}", p.width in 320..1920)
            assertTrue("height ${p.height}", p.height in 240..1080)
            assertTrue("jpeg ${p.jpegQuality}", p.jpegQuality in 10..100)
            assertTrue("fps ${p.fps}", p.fps in 1..30)
        }
    }

    @Test
    fun `higher tiers have more pixels`() {
        val low = QualityPresets.get("low")
        val med = QualityPresets.get("med")
        val high = QualityPresets.get("high")
        assertTrue(low.width * low.height < med.width * med.height)
        assertTrue(med.width * med.height < high.width * high.height)
        assertTrue(low.jpegQuality < med.jpegQuality)
        assertTrue(med.jpegQuality < high.jpegQuality)
    }

    @Test
    fun `fps override clamps and passes through`() {
        assertNull(QualityPresets.fpsOverride(null))
        assertNull(QualityPresets.fpsOverride(0))
        assertNull(QualityPresets.fpsOverride(-5))
        assertEquals(1, QualityPresets.fpsOverride(1))
        assertEquals(25, QualityPresets.fpsOverride(25))
        assertEquals(30, QualityPresets.fpsOverride(100))
    }

    @Test
    fun `jpeg quality clamps`() {
        assertEquals(60, QualityPresets.jpegQuality(null))
        assertEquals(10, QualityPresets.jpegQuality(1))
        assertEquals(100, QualityPresets.jpegQuality(150))
        assertEquals(85, QualityPresets.jpegQuality(85))
        assertFalse(QualityPresets.jpegQuality(0) < 10)
    }
}
