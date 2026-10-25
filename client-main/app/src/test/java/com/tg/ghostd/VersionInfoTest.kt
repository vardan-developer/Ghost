package com.tg.ghostd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionInfoTest {

    // version bump sanity: 0.1.0 -> 0.2.0 must register as an upgrade

    @Test fun versionBumpIsNewer() {
        assertTrue(VersionInfo.isNewer("0.2.0", "0.1.0"))
    }

    @Test fun sameVersionIsNotNewer() {
        assertFalse(VersionInfo.isNewer("0.2.0", "0.2.0"))
        assertEquals(0, VersionInfo.compare("0.2.0", "0.2.0"))
    }

    @Test fun downgradeIsNotNewer() {
        assertTrue(VersionInfo.isNewer("0.1.0", "0.2.0") xor true)
        assertTrue(VersionInfo.compare("0.1.0", "0.2.0") < 0)
    }

    @Test fun minorOverflowComparesNumerically() {
        assertTrue(VersionInfo.isNewer("0.2.0", "0.1.9"))
        assertTrue(VersionInfo.isNewer("0.10.0", "0.9.2"))
        assertTrue(VersionInfo.isNewer("1.0.0", "0.99.99"))
    }

    @Test fun missingPartsAreZero() {
        assertTrue(VersionInfo.isNewer("0.2", "0.1.9"))
        assertEquals(0, VersionInfo.compare("0.2.0", "0.2.0.0"))
        assertTrue(VersionInfo.isNewer("1", "0.9"))
    }

    @Test fun garbagePartsAreZero() {
        assertEquals(0, VersionInfo.compare("0.2.0-beta", "0.2.0"))
        assertTrue(VersionInfo.isNewer("0.3.0", "0.2.0-x"))
    }
}
