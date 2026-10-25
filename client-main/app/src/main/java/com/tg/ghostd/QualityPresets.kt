package com.tg.ghostd

/**
 * Pure (no-Android-imports) quality presets shared by cam live and screen
 * live/record. JPEG sizes/qualities/fps per preset name.
 *
 * low/med/high are abstract tiers; 480p/720p/1080p are explicit caps.
 */
object QualityPresets {

    data class Preset(
        val name: String,
        val width: Int,
        val height: Int,
        /** JPEG encoder quality 0-100. */
        val jpegQuality: Int,
        /** Default frames per second for live streaming. */
        val fps: Int
    )

    private val map: Map<String, Preset> = listOf(
        Preset("low", 320, 240, 50, 4),
        Preset("med", 640, 480, 60, 8),
        Preset("high", 1280, 720, 70, 12),
        Preset("480p", 640, 480, 65, 10),
        Preset("720p", 1280, 720, 70, 12),
        Preset("1080p", 1920, 1080, 80, 15)
    ).associateBy { it.name }

    val default: Preset = map.getValue("med")

    val names: Set<String> = map.keys

    /** Lookup with fallback to the default preset ("med"). */
    fun get(name: String?): Preset = map[name ?: "med"] ?: default

    /** Clamp an --fps override to a sane range; null means "use preset default". */
    fun fpsOverride(fps: Int?): Int? =
        if (fps == null || fps < 1) null else minOf(fps, MAX_FPS)

    /** Clamp JPEG quality 10..100. */
    fun jpegQuality(q: Int?): Int = when {
        q == null -> 60
        q < 10 -> 10
        q > 100 -> 100
        else -> q
    }

    const val MAX_FPS = 30
}
