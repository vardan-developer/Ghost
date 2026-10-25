package com.tg.ghostd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the install decisions added for the update audit:
 * versionCode ordering, APK package identity, PackageInstaller status/session
 * ownership, and post-install verification.
 */
class InstallGuardTest {

    // ---------------------------------------------------------- U6 ordering

    @Test fun higherCodeWithSameDisplayNameUpgrades() {
        // 0.3.9 and 0.3.9 display identically; only the code changed.
        assertEquals(0, VersionInfo.compare("0.3.9", "0.3.9"))
        assertTrue(VersionInfo.shouldProvisionBundled(bundledCode = 22L, installedCode = 21L))
    }

    @Test fun higherDisplayNameWithOlderCodeDoesNotUpgrade() {
        // The old versionName comparison would have attempted this (and been
        // rejected as a downgrade); versionCode ordering must skip it.
        assertTrue(VersionInfo.isNewer("0.4.0", "0.3.9"))
        assertFalse(VersionInfo.shouldProvisionBundled(bundledCode = 20L, installedCode = 21L))
    }

    @Test fun equalCodeIsUpToDate() {
        assertFalse(VersionInfo.shouldProvisionBundled(bundledCode = 21L, installedCode = 21L))
    }

    @Test fun missingInstallAlwaysProvisions() {
        assertTrue(VersionInfo.shouldProvisionBundled(bundledCode = 21L, installedCode = null))
        // Even with an unreadable bundle the package is absent, so install is
        // attempted; installApk then rejects the unreadable archive.
        assertTrue(VersionInfo.shouldProvisionBundled(bundledCode = null, installedCode = null))
    }

    @Test fun unreadableBundleDoesNotReplaceKnownInstall() {
        assertFalse(VersionInfo.shouldProvisionBundled(bundledCode = null, installedCode = 21L))
    }

    @Test fun versionCodeNewerRequiresKnownCandidate() {
        assertTrue(VersionInfo.versionCodeNewer(candidate = 22L, current = 21L))
        assertFalse(VersionInfo.versionCodeNewer(candidate = null, current = 21L))
        assertFalse(VersionInfo.versionCodeNewer(candidate = null, current = null))
    }

    // ------------------------------------------------------ U5 package identity

    @Test fun archivePackageMustMatchTarget() {
        assertTrue(
            VersionInfo.archivePackageMatches("com.tg.ghostd", "com.tg.ghostd")
        )
        assertTrue(
            VersionInfo.archivePackageMatches("com.tg.ghosta", "com.tg.ghosta")
        )
        // A ghosta APK staged as ghostd.apk must be rejected, and vice versa.
        assertFalse(
            VersionInfo.archivePackageMatches("com.tg.ghostd", "com.tg.ghosta")
        )
        assertFalse(
            VersionInfo.archivePackageMatches("com.tg.ghosta", "com.tg.ghostd")
        )
        assertFalse(VersionInfo.archivePackageMatches("com.tg.ghostd", null))
    }

    // ------------------------------------------------- U2/U3 status ownership

    @Test fun statusRequiresOwningSession() {
        assertTrue(
            VersionInfo.statusBelongsTo(
                expectedSessionId = 42,
                expectedPackage = "com.tg.ghosta",
                actualSessionId = 42,
                actualPackage = "com.tg.ghosta"
            )
        )
        // Session A's success must not be consumed by waiter B.
        assertFalse(
            VersionInfo.statusBelongsTo(
                expectedSessionId = 43,
                expectedPackage = "com.tg.ghosta",
                actualSessionId = 42,
                actualPackage = "com.tg.ghosta"
            )
        )
    }

    @Test fun statusPackageMismatchIsIgnored() {
        assertFalse(
            VersionInfo.statusBelongsTo(
                expectedSessionId = 42,
                expectedPackage = "com.tg.ghostd",
                actualSessionId = 42,
                actualPackage = "com.tg.ghosta"
            )
        )
    }

    @Test fun statusWithoutPackageFallsBackToSessionId() {
        // Not every OEM includes EXTRA_PACKAGE_NAME on the commit callback;
        // the session id remains mandatory.
        assertTrue(
            VersionInfo.statusBelongsTo(
                expectedSessionId = 42,
                expectedPackage = "com.tg.ghosta",
                actualSessionId = 42,
                actualPackage = null
            )
        )
        assertFalse(
            VersionInfo.statusBelongsTo(
                expectedSessionId = 42,
                expectedPackage = "com.tg.ghosta",
                actualSessionId = -1,
                actualPackage = null
            )
        )
    }

    // ------------------------------------------------- post-install check

    @Test fun successRequiresAtLeastTheArchiveVersionCode() {
        assertTrue(VersionInfo.installedMatches(21L, 21L))
        assertTrue(VersionInfo.installedMatches(21L, 22L))
        assertFalse(VersionInfo.installedMatches(21L, 20L))
        // Still the OLD version after a "successful" callback is not success.
        assertFalse(VersionInfo.installedMatches(22L, 21L))
        assertFalse(VersionInfo.installedMatches(21L, null))
        assertFalse(VersionInfo.installedMatches(null, 21L))
    }
}
