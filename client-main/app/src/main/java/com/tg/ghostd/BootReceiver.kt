package com.tg.ghostd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED: start after reboot. specialUse (unlike dataSync) is
        // allowed to start from boot on Android 15+.
        // MY_PACKAGE_REPLACED: start after a silent self-update — Android
        // kills the process for the package replace and does NOT restart
        // services on its own; without this every OTA update left the app
        // dead until manually launched.
        try {
            val start = Prefs.setupDone(context) && (
                intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                )
            if (start) {
                context.startForegroundService(Intent(context, GhostService::class.java))
            }
        } catch (e: Exception) {
            // A rejected start must never crash the receiver/process (e.g.
            // OEM or future background-start restrictions). Log and leave the
            // normal reconnect/kick paths to recover later.
            Log.w("ghostd", "BootReceiver start failed for ${intent.action}: ${e.message}")
        }
    }
}
