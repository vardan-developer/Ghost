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
    private const val KEY_DEVICE_TOKEN_ENC = "device_token_enc"
    private const val TOKEN_ALIAS = "ghostd.device_token"

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

    /**
     * Device auth token issued on hello_ack. New writes are AES/GCM encrypted
     * with a non-exportable Android Keystore key; a legacy plaintext token is
     * migrated on read and removed after the encrypted copy commits.
     */
    fun deviceToken(ctx: Context): String? {
        sp(ctx).getString(KEY_DEVICE_TOKEN_ENC, null)?.let { blob ->
            decryptString(TOKEN_ALIAS, blob)?.let { return it }
        }
        val legacy = sp(ctx).getString(KEY_DEVICE_TOKEN, null) ?: return null
        // If the keystore is unavailable, keep using the legacy token for
        // this session rather than locking the owner out.
        setDeviceToken(ctx, legacy)
        return legacy
    }

    /** Returns false when the token could not be encrypted/persisted. */
    fun setDeviceToken(ctx: Context, token: String): Boolean {
        val blob = try {
            encryptString(TOKEN_ALIAS, token)
        } catch (_: Exception) {
            return false
        }
        return sp(ctx).edit()
            .putString(KEY_DEVICE_TOKEN_ENC, blob)
            .remove(KEY_DEVICE_TOKEN)
            .commit()
    }

    fun clearDeviceToken(ctx: Context) {
        sp(ctx).edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_DEVICE_TOKEN_ENC)
            .commit()
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
            val blob = encryptString(PIN_ALIAS, pin)
            sp(ctx).edit().putString(KEY_PIN, blob).apply()
        } catch (_: Exception) {
        }
    }

    fun pin(ctx: Context): String? {
        val blob = sp(ctx).getString(KEY_PIN, null) ?: return null
        return decryptString(PIN_ALIAS, blob)
    }

    fun hasPin(ctx: Context): Boolean = sp(ctx).getString(KEY_PIN, null) != null

    fun clearPin(ctx: Context) {
        sp(ctx).edit().remove(KEY_PIN).apply()
    }

    private fun encryptString(alias: String, value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(alias))
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun decryptString(alias: String, blob: String): String? {
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size <= 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(alias),
                GCMParameterSpec(128, raw.copyOfRange(0, 12))
            )
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val gen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }
}
