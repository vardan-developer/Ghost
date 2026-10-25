package com.tg.ghostd

/**
 * Pure-JVM install decisions (no Android imports → unit-testable).
 *
 * Version ORDERING is based on `longVersionCode`, never on [compare] /
 * [isNewer]: versionName is display metadata only. A rebuild that keeps the
 * same name but bumps the code is an upgrade; a bigger name with an older
 * code is not.
 *
 * The same object also holds the package/session matching rules used by the
 * PackageInstaller status receiver, so the security-relevant decisions can be
 * tested on the JVM.
 */
object VersionInfo {

    /**
     * Dotted numeric version-name comparison, for DISPLAY ONLY.
     *
     * "0.2.0" vs "0.1.9" → positive; equal strings → 0. Missing parts are 0
     * and non-numeric parts are 0. Do not use this to decide whether to
     * install an APK — use [versionCodeNewer] / [shouldProvisionBundled].
     */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Display-name ordering; see [compare]. NOT an install decision. */
    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0

    private fun parts(v: String): List<Int> =
        v.split('.').map { it.toIntOrNull() ?: 0 }

    // ----------------------------------------------------- versionCode rules

    /**
     * True when [candidate] is a higher longVersionCode than [current].
     * A known code always beats a missing/unknown current code; an unknown
     * candidate never beats a known one.
     */
    fun versionCodeNewer(candidate: Long?, current: Long?): Boolean =
        candidate != null && (current == null || candidate > current)

    /**
     * Bundled-provisioning decision. Install when ghosta is absent, or when
     * the bundled APK has a strictly higher longVersionCode than what is
     * installed. An unreadable bundled APK (null code) never replaces an
     * existing install.
     */
    fun shouldProvisionBundled(bundledCode: Long?, installedCode: Long?): Boolean =
        installedCode == null || versionCodeNewer(bundledCode, installedCode)

    /**
     * Post-install verification: the installed package must now be at least
     * the archive's versionCode. (At least, not exactly, so a concurrent
     * newer install cannot be reported as a failure.)
     */
    fun installedMatches(expectedCode: Long?, installedCode: Long?): Boolean =
        expectedCode != null && installedCode != null && installedCode >= expectedCode

    // ---------------------------------------------------------- package rules

    /** The APK archive must declare exactly the package we asked to install. */
    fun archivePackageMatches(expectedPackage: String, archivePackage: String?): Boolean =
        archivePackage != null && archivePackage == expectedPackage

    /**
     * True only when a PackageInstaller status broadcast belongs to the
     * session that is waiting for it. The package check is enforced when the
     * system supplies a non-blank EXTRA_PACKAGE_NAME; the session id is
     * always required.
     */
    fun statusBelongsTo(
        expectedSessionId: Int,
        expectedPackage: String,
        actualSessionId: Int,
        actualPackage: String?
    ): Boolean =
        actualSessionId == expectedSessionId &&
            (actualPackage.isNullOrBlank() || actualPackage == expectedPackage)
}
