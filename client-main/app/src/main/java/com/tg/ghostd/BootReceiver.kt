package com.tg.ghostd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED: start after reboot.
        // MY_PACKAGE_REPLACED: start after a silent self-update — Android
        // kills the process for the package replace and does NOT restart
        // services on its own; without this every OTA update left the app
        // dead until manually launched.
        val start = Prefs.setupDone(context) && (
            intent.action == Intent.ACTION_BOOT_COMPLETED ||
                intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
            )
        if (start) {
            context.startForegroundService(Intent(context, GhostService::class.java))
        }
    }
}
