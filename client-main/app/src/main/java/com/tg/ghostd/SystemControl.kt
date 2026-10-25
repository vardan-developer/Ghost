package com.tg.ghostd

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/**
 * Device control: brightness get/set (Settings.System.SCREEN_BRIGHTNESS,
 * needs WRITE_SETTINGS), volume get/set/mute per audio stream, and screen
 * wake via a bright wake lock that causes wakeup.
 */
object SystemControl {

    // ------------------------------------------------------------- brightness

    fun brightnessGet(ctx: Context): JSONObject {
        val v = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        return JSONObject().put("brightness", v)
    }

    /** 0-255. Forces manual brightness mode first (auto mode ignores it). */
    fun brightnessSet(ctx: Context, value: Int): JSONObject {
        if (!Settings.System.canWrite(ctx)) {
            throw Exception("WRITE_SETTINGS not granted (Settings > Modify system settings)")
        }
        val v = value.coerceIn(0, 255)
        Settings.System.putInt(
            ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        if (!Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)) {
            throw Exception("failed to set SCREEN_BRIGHTNESS")
        }
        return JSONObject().put("brightness", v)
    }

    // ----------------------------------------------------------------- volume

    private val STREAMS = mapOf(
        "ring" to AudioManager.STREAM_RING,
        "media" to AudioManager.STREAM_MUSIC,
        "alarm" to AudioManager.STREAM_ALARM,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "system" to AudioManager.STREAM_SYSTEM
    )

    fun volumeGet(ctx: Context, streamName: String): JSONObject {
        val s = streamOf(streamName)
        val am = ctx.getSystemService(AudioManager::class.java)!!
        return JSONObject()
            .put("stream", streamName)
            .put("volume", am.getStreamVolume(s))
            .put("max", am.getStreamMaxVolume(s))
    }

    /** value 0-100 (percent of the stream's max). */
    fun volumeSet(ctx: Context, streamName: String, value: Int): JSONObject {
        val s = streamOf(streamName)
        val am = ctx.getSystemService(AudioManager::class.java)!!
        val max = am.getStreamMaxVolume(s)
        val v = (value.coerceIn(0, 100) * max / 100)
        am.setStreamVolume(s, v, 0)
        return JSONObject()
            .put("stream", streamName)
            .put("volume", v)
            .put("max", max)
    }

    fun volumeMute(ctx: Context, streamName: String, mute: Boolean): JSONObject {
        val s = streamOf(streamName)
        val am = ctx.getSystemService(AudioManager::class.java)!!
        am.adjustStreamVolume(
            s, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0
        )
        return JSONObject().put("stream", streamName).put("muted", mute)
    }

    private fun streamOf(name: String): Int =
        STREAMS[name.lowercase()] ?: throw Exception(
            "unknown stream '$name' (use ring|media|alarm|notification|system)"
        )

    // ------------------------------------------------------------------- wake

    /** Full wake lock with ACQUIRE_CAUSES_WAKEUP: turns the screen on. */
    fun wake(ctx: Context): JSONObject {
        val pm = ctx.getSystemService(PowerManager::class.java)
            ?: throw Exception("no PowerManager")
        @Suppress("DEPRECATION")
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE
        val wl = pm.newWakeLock(flags, "ghostd:wake")
        // acquire-with-timeout: turns the screen on now, auto-releases in 60s
        wl.acquire(60_000L)
        return JSONObject().put("wake", true)
    }
}
