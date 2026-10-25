package com.tg.ghosta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * No-op boot receiver: the system rebinds accessibility services automatically,
 * so nothing to do here — this exists purely to observe boot delivery
 * (and keep the process known to the system). Never starts a service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("ghosta", "boot completed")
        }
    }
}
