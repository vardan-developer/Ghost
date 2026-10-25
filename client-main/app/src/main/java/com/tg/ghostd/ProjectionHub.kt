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
 */
object ProjectionHub {

    @Volatile private var projection: MediaProjection? = null

    private var grantLatch: CountDownLatch? = null
    @Volatile private var grantCode = 0
    @Volatile private var grantData: Intent? = null

    private val lock = Any()

    fun projectionOrNull(): MediaProjection? = projection

    /** True if a reusable projection is already running. */
    fun hasProjection(): Boolean = projection != null

    /**
     * Returns the live MediaProjection, requesting user consent (dialog)
     * when none exists yet. Blocks until consent or timeout.
     */
    fun ensureProjection(ctx: Context): MediaProjection {
        projection?.let { return it }
        synchronized(lock) {
            projection?.let { return it }

            // Ask for a fresh grant: shows the consent dialog via
            // ScreenCaptureActivity; a human (or the sidekick) taps it.
            val latch = CountDownLatch(1)
            grantLatch = latch
            grantCode = 0
            grantData = null
            val i = Intent(ctx, ScreenCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)

            if (!latch.await(GRANT_TIMEOUT_S, TimeUnit.SECONDS)) {
                throw Exception("projection grant timeout (user did not confirm)")
            }
            val code = grantCode
            val data = grantData
            grantLatch = null
            if (code == 0 || data == null) throw Exception("projection denied by user")

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
                    synchronized(lock) { projection = null }
                }
            }, Handler(Looper.getMainLooper()))
            projection = p
            return p
        }
    }

    /** Called by ScreenCaptureActivity with the consent result. */
    fun onGrant(resultCode: Int, data: Intent?) {
        grantCode = resultCode
        grantData = data
        grantLatch?.countDown()
    }

    /** Stop the projection (releases the cast chip / status icon). */
    fun stop() {
        synchronized(lock) {
            projection?.stop()
            projection = null
        }
    }

    private const val GRANT_TIMEOUT_S = 120L
}
