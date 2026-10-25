package com.tg.ghostd

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the MediaProjection grant lifecycle.
 *
 * The consent result (resultCode + Intent) is handed over by
 * [ScreenCaptureActivity]. Once granted, the projection object is kept alive
 * and reused for every screen verb (shot / record / live) until it is
 * stopped ("screen stop" or the user taps the cast notification), matching
 * the "projection persists until stopped" design.
 *
 * Android 14+ requires a foreground service of type mediaProjection to be
 * running BEFORE calling getMediaProjection, so [ensureProjection] promotes
 * GhostService to that type first. The grant Intent can only be used once
 * on 14+, hence caching the MediaProjection object, not the grant.
 *
 * Wedge-safety (0.2.8): [stop] takes NO lock that a waiting/creating
 * [ensureProjection] caller can hold for long — `hubLock` is only held for
 * short state updates, never across a wait — so `screen off` always returns
 * promptly even when a capture is wedged. The grant wait itself is
 * bounded ([GRANT_TIMEOUT_S]) and interruptible: [stop] interrupts the
 * waiter and cancels a pending grant. Only one consent dialog is shown at
 * a time (single-flight): concurrent callers wait for the leader instead of
 * stacking a second dialog.
 */
object ProjectionHub {

    @Volatile private var projection: MediaProjection? = null

    /** Guards the grant-state fields below; NEVER held across a wait. */
    private val hubLock = Any()
    private var grantLatch: CountDownLatch? = null
    @Volatile private var grantCode = 0
    @Volatile private var grantData: Intent? = null
    @Volatile private var grantCancelled = false
    @Volatile private var grantWaiter: Thread? = null

    /** True while one thread is between grant-wait and projection-create. */
    @Volatile private var inflight = false

    fun projectionOrNull(): MediaProjection? = projection

    /** True if a reusable projection is already running. */
    fun hasProjection(): Boolean = projection != null

    /**
     * Returns the live MediaProjection, requesting user consent (dialog)
     * when none exists yet. All waits are bounded and interruptible.
     */
    fun ensureProjection(ctx: Context): MediaProjection {
        projection?.let { return it }

        // Single-flight: while one thread requests the grant / creates the
        // projection, later callers poll for the outcome instead of popping
        // a SECOND consent dialog for the same grant. The poll sleeps
        // WITHOUT holding hubLock, so stop() is never blocked by it.
        while (true) {
            val iAmLeader = synchronized(hubLock) {
                when {
                    inflight -> false
                    else -> {
                        inflight = true
                        true
                    }
                }
            }
            if (iAmLeader) break
            projection?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Exception("projection wait interrupted (screen stopped?)")
            }
        }
        try {
            projection?.let { return it }

            val (code, data) = awaitGrant(ctx)

            // Android 14+: FGS with mediaProjection type must run before
            // getMediaProjection; GhostService declares dataSync|mediaProjection.
            val svc = GhostService.instance
                ?: throw Exception("GhostService not running; cannot start projection")
            svc.promoteToProjection()

            val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
            val p = try {
                mpm.getMediaProjection(code, data)
            } catch (e: Exception) {
                throw Exception("getMediaProjection failed: ${e.message}")
            } ?: throw Exception("getMediaProjection returned null")
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                }
            }, Handler(Looper.getMainLooper()))
            projection = p
            return p
        } finally {
            synchronized(hubLock) {
                inflight = false
            }
        }
    }

    /**
     * Shows the consent dialog (once) and waits for the grant result.
     * Bounded by [GRANT_TIMEOUT_S]; interrupted when [stop] runs.
     */
    private fun awaitGrant(ctx: Context): Pair<Int, Intent> {
        val latch: CountDownLatch
        synchronized(hubLock) {
            if (grantLatch == null) {
                grantCancelled = false
                grantCode = 0
                grantData = null
                grantLatch = CountDownLatch(1)
                try {
                    ctx.startActivity(
                        Intent(ctx, ScreenCaptureActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    grantLatch = null
                    throw Exception("cannot start consent activity: ${e.message}")
                }
            }
            latch = grantLatch!!
            grantWaiter = Thread.currentThread()
        }
        try {
            if (!latch.await(GRANT_TIMEOUT_S, TimeUnit.SECONDS)) {
                throw Exception("projection grant timeout (user did not confirm)")
            }
            if (grantCancelled) throw Exception("projection stopped while waiting for consent")
            val code = grantCode
            val data = grantData
            synchronized(hubLock) { grantLatch = null }
            if (code == 0 || data == null) throw Exception("projection denied by user")
            return Pair(code, data)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Exception("projection grant wait interrupted (screen stopped?)")
        } finally {
            grantWaiter = null
        }
    }

    /** Called by ScreenCaptureActivity with the consent result. */
    fun onGrant(resultCode: Int, data: Intent?) {
        synchronized(hubLock) {
            grantCode = resultCode
            grantData = data
            grantLatch?.countDown()
        }
    }

    /**
     * Stop the projection (releases the cast chip / status icon).
     * Lock-free and interruptible-safe: also cancels a pending grant wait
     * so a wedged `screen shot`/`screen live` command returns promptly
     * with an error instead of hanging until the grant timeout.
     */
    fun stop() {
        synchronized(hubLock) {
            grantCancelled = true
            grantCode = 0
            grantData = null
            grantLatch?.countDown()
        }
        grantWaiter?.interrupt()
        val p = projection
        projection = null
        try {
            p?.stop()
        } catch (_: Exception) {
        }
    }

    private const val GRANT_TIMEOUT_S = 120L
}
