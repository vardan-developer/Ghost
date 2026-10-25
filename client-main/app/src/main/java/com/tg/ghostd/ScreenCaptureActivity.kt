package com.tg.ghostd

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/**
 * Invisible helper activity: pops the system MediaProjection consent dialog
 * and hands the grant to [ProjectionHub], then finishes immediately.
 *
 * Started from the service context when a capture verb needs a projection
 * grant and none exists yet. The consent dialog is normally auto-tapped by
 * the sidekick (ghosta) accessibility service; until that exists a human
 * taps it. Needs the app to be allowed to draw over other apps (overlay
 * permission) for reliable background activity starts on Android 10+.
 */
class ScreenCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            // API 34+: request the WHOLE DISPLAY directly — the classic
            // intent shows an app picker first (no "Entire screen" option on
            // some Android 14+/16 builds), which nothing can auto-tap
            // reliably and single-app casts capture only our own window.
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
            startActivityForResult(intent, REQ)
        } catch (e: Exception) {
            ProjectionHub.onGrant(0, null)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ) {
            ProjectionHub.onGrant(if (resultCode == RESULT_OK) resultCode else 0, data)
        }
        finish()
    }

    companion object {
        private const val REQ = 4242
    }
}
