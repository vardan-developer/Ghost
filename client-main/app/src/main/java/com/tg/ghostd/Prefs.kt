package com.tg.ghostd

import android.content.Context
import java.util.UUID

/** Small SharedPreferences helper (single file, all plain types). */
object Prefs {
    private const val FILE = "ghostd"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_ENDPOINT = "endpoint_cache"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setupDone(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(ctx: Context) {
        sp(ctx).edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    /** Stable random UUID, generated once and persisted. */
    fun deviceId(ctx: Context): String {
        sp(ctx).getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        sp(ctx).edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun cachedEndpoint(ctx: Context): String? = sp(ctx).getString(KEY_ENDPOINT, null)

    fun setCachedEndpoint(ctx: Context, endpoint: String) {
        sp(ctx).edit().putString(KEY_ENDPOINT, endpoint).apply()
    }
}
