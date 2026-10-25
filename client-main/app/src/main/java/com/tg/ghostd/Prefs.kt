package com.tg.ghostd

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small SharedPreferences helper (single file, all plain types). */
object Prefs {
    private const val FILE = "ghostd"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_ENDPOINT = "endpoint_cache"
    private const val KEY_DEVICE_TOKEN = "device_token"

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

    /** Device auth token issued by the server on hello_ack (approved/pending). */
    fun deviceToken(ctx: Context): String? = sp(ctx).getString(KEY_DEVICE_TOKEN, null)

    fun setDeviceToken(ctx: Context, token: String) {
        sp(ctx).edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun clearDeviceToken(ctx: Context) {
        sp(ctx).edit().remove(KEY_DEVICE_TOKEN).apply()
    }

    // ------------------------------------------------------- keyguard PIN

    /**
     * The device PIN is stored AES/GCM-encrypted with a key that never leaves
     * the Android Keystore, so updates can unlock the phone unattended
     * (wake → swipe → PIN → install → auto-tap). Cleared with `unlock --forget`.
     *
     * Failure to store (keystore unavailable) is non-fatal: the PIN is simply
     * not remembered and every update keeps needing a manual unlock.
     */
    private const val KEY_PIN = "keyguard_pin"
    private const val PIN_ALIAS = "ghostd.keyguard"

    fun setPin(ctx: Context, pin: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, pinKey())
            val ct = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
            val blob = cipher.iv + ct
            sp(ctx).edit()
                .putString(KEY_PIN, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
        } catch (_: Exception) {
        }
    }

    fun pin(ctx: Context): String? {
        return try {
            val blob = sp(ctx).getString(KEY_PIN, null) ?: return null
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size <= 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, pinKey(),
                GCMParameterSpec(128, raw.copyOfRange(0, 12))
            )
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun hasPin(ctx: Context): Boolean = sp(ctx).getString(KEY_PIN, null) != null

    fun clearPin(ctx: Context) {
        sp(ctx).edit().remove(KEY_PIN).apply()
    }

    private fun pinKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(PIN_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val gen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        gen.init(
            KeyGenParameterSpec.Builder(
                PIN_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }
}
