package com.tg.ghostd

/**
 * Pure-JVM version-string helpers (no Android imports → unit-testable).
 * Compares dotted numeric version names like "0.2.0" (missing parts = 0,
 * non-numeric parts = 0).
 */
object VersionInfo {

    /** "0.2.0" vs "0.1.9" → positive; equal strings → 0. */
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

    /** True when [candidate] is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0

    private fun parts(v: String): List<Int> =
        v.split('.').map { it.toIntOrNull() ?: 0 }
}
