package com.tg.ghostd

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Invisible helper Activity that obtains a MediaProjection consent result for
 * one capture. The request token is carried in [ProjectionHub.EXTRA_TOKEN] and
 * returned with the result so [ProjectionHub] can reject stale grants.
 *
 * There is no persistent projection: the operator must consent for every
 * shot/stream. If this Activity is destroyed without producing a result, it
 * cancels the matching request so the capture fails fast instead of hanging.
 */
class ScreenCaptureActivity : Activity() {

    private var token: Long = 0L
    private var resultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = savedInstanceState?.getLong(ProjectionHub.EXTRA_TOKEN)
            ?: intent?.getLongExtra(ProjectionHub.EXTRA_TOKEN, 0L)
            ?: 0L
        if (token == 0L) {
            Log.w(TAG, "missing projection request token")
            finish()
            return
        }
        // After a configuration change the pending system dialog's result is
        // delivered to the recreated instance; do not stack a second request.
        if (savedInstanceState == null) requestConsent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(ProjectionHub.EXTRA_TOKEN, token)
    }

    @Suppress("DEPRECATION")
    private fun requestConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            Log.w(TAG, "MediaProjectionManager unavailable")
            cancelGrant()
            finish()
            return
        }
        try {
            // API 34+: request the WHOLE DISPLAY directly — the classic
            // intent shows an app picker first (no "Entire screen" option on
            // some Android 14+/16 builds), and single-app casts capture only
            // our own window.
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                mpm.createScreenCaptureIntent()
            }
            startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            Log.w(TAG, "cannot request projection consent: ${e.message}")
            cancelGrant()
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        resultSent = true
        if (resultCode == RESULT_OK && data != null) {
            ProjectionHub.onGrant(token, resultCode, data)
        } else {
            // User dismissed/denied the consent sheet.
            ProjectionHub.onGrant(token, resultCode, null)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!resultSent && !isChangingConfigurations) cancelGrant()
    }

    private fun cancelGrant() {
        resultSent = true
        ProjectionHub.onGrantCancelled(token)
    }

    private companion object {
        const val TAG = "GhostCaptureActivity"
        const val REQUEST_CODE = 0x5C7
    }
}
