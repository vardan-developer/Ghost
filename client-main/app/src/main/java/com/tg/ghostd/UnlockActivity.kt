package com.tg.ghostd

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.view.WindowManager

/** Shows Android's own PIN/password screen so ghosta can complete an
 * owner-requested unlock. No credential is requested or bypassed by this app. */
class UnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }

    private var requested = false

    override fun onResume() {
        super.onResume()
        val locked = try {
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        } catch (_: Exception) {
            false
        }
        if (!locked) {
            finish()
        } else if (!requested) {
            requested = true
            try {
                val intent = getSystemService(KeyguardManager::class.java)
                    .createConfirmDeviceCredentialIntent(
                        "Owner authorization", "Unlock to continue"
                    )
                if (intent == null) finish() else startActivityForResult(intent, 1)
            } catch (_: Exception) {
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        finish()
    }
}
