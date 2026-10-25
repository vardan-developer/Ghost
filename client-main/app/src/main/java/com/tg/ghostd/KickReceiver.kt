package com.tg.ghostd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Babysitter entry point: ghosta (Display Helper) sends [KICK_ACTION] when
 * ghostd's local-link beacon goes stale — its own watchdog can't recover a
 * wedged main loop, but a separate process can. Starting the FGS again
 * re-creates GhostService with fresh threads (onStartCommand only starts the
 * worker if none exists; a wedged one is replaced because the old service
 * instance was destroyed or its worker died).
 */
class KickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == KICK_ACTION && Prefs.setupDone(context)) {
            // A kick means "state is bad": drop any half-received file
            // transfer too, otherwise it blocks every later push.
            FileReceiver.abortAll("kick")
            try {
                context.startForegroundService(
                    Intent(context, GhostService::class.java)
                        .putExtra("force", true))
            } catch (e: Exception) {
                // Android 12+ FGS-from-background restrictions; the app is
                // battery-optimization-exempt (wizard) which normally allows
                // this — log if an OEM still objects.
                android.util.Log.w("ghostd", "kick start failed: ${e.message}")
            }
        }
    }

    companion object {
        const val KICK_ACTION = "com.tg.ghostd.KICK"
    }
}
