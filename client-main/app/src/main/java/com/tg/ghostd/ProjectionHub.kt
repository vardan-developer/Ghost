package com.tg.ghostd

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the pre-consent keyguard gate should do. */
internal enum class ConsentUnlockAction { PROCEED, TRY_STORED_PIN, BLOCK }

/**
 * Pure pre-consent keyguard decision (JVM-tested). A MediaProjection consent
 * sheet cannot be completed while the keyguard is up, so a locked device must
 * either be unlocked with the PIN remembered by an earlier `unlock` or fail
 * with an actionable error instead of waiting out the 120 s grant timeout.
 */
internal object CaptureUnlockPolicy {
    fun decide(keyguardLocked: Boolean, hasStoredPin: Boolean): ConsentUnlockAction = when {
        !keyguardLocked -> ConsentUnlockAction.PROCEED
        hasStoredPin -> ConsentUnlockAction.TRY_STORED_PIN
        else -> ConsentUnlockAction.BLOCK
    }

    fun lockedError(hasStoredPin: Boolean): String = if (hasStoredPin) {
        "device is still locked: stored-PIN unlock did not clear the keyguard before " +
            "screen-capture consent. Unlock the phone or re-run `unlock <pin>` after " +
            "checking the PIN, then retry"
    } else {
        "device is locked and no PIN is stored: run `unlock <pin>` once (or unlock the " +
            "phone manually), then retry — the screen-capture consent sheet cannot be " +
            "completed while locked"
    }
}

/**
 * Owns one MediaProjection grant for exactly one capture session.
 *
 * The user decision is consent **per capture/session**: a projection is never
 * retained or reused. [ensureProjection] therefore always requests a fresh
 * grant and returns a [Grant] that the caller must [release] on every exit
 * (success, error, stop, timeout).
 *
 * Android 14+ requires a foreground service of type mediaProjection to be
 * running BEFORE calling getMediaProjection, so [ensureProjection] promotes
 * GhostService to that type first.
 *
 * Grant requests are identified by a monotonically increasing [Grant.token].
 * The consent result and the projection callback are matched against the
 * pending/active token, so a late result or an old projection's `onStop`
 * can never complete, clear or stop a newer capture. All waiter state is
 * cleared on every exit (success, denial, timeout, interruption, [stop]),
 * the consent activity is started outside the lock, and follower waits are
 * bounded by the same overall deadline as the leader.
 */
object ProjectionHub {

    /** Intent extra carrying the grant-request token into [ScreenCaptureActivity]. */
    const val EXTRA_TOKEN = "com.tg.ghostd.extra.PROJECTION_TOKEN"

    /**
     * A live projection belonging to one capture session. Always call
     * [ProjectionHub.release] when the session ends.
     */
    class Grant internal constructor(
        val token: Long,
        val projection: MediaProjection
    ) {
        /** Set by Android when sharing ends (cast UI / system revoke). */
        @Volatile internal var stopped = false
        /** Set once by [ProjectionHub.release] (idempotent teardown). */
        @Volatile internal var released = false
        internal var callback: MediaProjection.Callback? = null
    }

    internal class PendingGrant(val token: Long) {
        val latch = CountDownLatch(1)
        @Volatile var resultCode = 0
        @Volatile var data: Intent? = null
        @Volatile var cancelled = false
        @Volatile var waiter: Thread? = null
    }

    internal class GrantRequest(
        val token: Long,
        val leader: Boolean,
        internal val pending: PendingGrant?
    )

    private class ActiveProjection(val grant: Grant) {
        lateinit var callback: MediaProjection.Callback
    }

    /** Guards the grant state below; NEVER held across a wait or an Activity start. */
    private val hubLock = Any()
    private var nextToken = 1L
    private var pending: PendingGrant? = null
    private var active: ActiveProjection? = null

    /** Incremented by [stop] so an in-flight grant is abandoned, not adopted. */
    private var stopGeneration = 0L

    fun projectionOrNull(): MediaProjection? = synchronized(hubLock) {
        active?.grant?.projection
    }

    /** True while a projection belonging to a session is live. */
    fun hasProjection(): Boolean = synchronized(hubLock) { active != null }

    /**
     * Request a FRESH projection grant and wait for it (bounded and
     * interruptible). Concurrent callers do not stack consent dialogs: a
     * follower waits for the leader, then requests its own fresh grant.
     */
    fun ensureProjection(ctx: Context, isCancelled: () -> Boolean = { false }): Grant {
        val stopGen = synchronized(hubLock) { stopGeneration }
        val deadline = elapsedMs() + GRANT_TIMEOUT_MS
        while (true) {
            if (isCancelled()) throw Exception("projection stopped before consent")
            val request = beginGrantRequest()
            if (request.leader) {
                return createProjection(ctx, request.pending!!, deadline, stopGen, isCancelled)
            }
            awaitLeader(request.pending!!, deadline, stopGen)
            // The leader's grant belongs to the leader's session. Loop and
            // request our own fresh consent so sessions never share one.
        }
    }

    /**
     * Starts a new grant request. Returns [GrantRequest.leader] = false when
     * another request is already pending (single consent dialog at a time).
     */
    internal fun beginGrantRequest(): GrantRequest = synchronized(hubLock) {
        val currentPending = pending
        if (currentPending != null) {
            GrantRequest(currentPending.token, false, currentPending)
        } else {
            val fresh = PendingGrant(nextToken++)
            pending = fresh
            GrantRequest(fresh.token, true, fresh)
        }
    }

    internal fun pendingTokenForTest(): Long = synchronized(hubLock) { pending?.token ?: 0L }

    private fun createProjection(
        ctx: Context,
        grantRequest: PendingGrant,
        deadline: Long,
        stopGen: Long,
        isCancelled: () -> Boolean
    ): Grant {
        val token = grantRequest.token
        try {
            // A stop may land between the request registration and the
            // Activity launch. Check before showing any UI; stop() also
            // cancels a pending request it can see.
            if (isCancelled() || stoppedSince(stopGen)) {
                throw Exception("projection stopped before consent")
            }
            // A locked keyguard suppresses the consent sheet. Wake first and
            // use the remembered PIN when available; otherwise fail now with
            // an actionable error instead of burning the 120 s grant timeout.
            ensureUnlockedForConsent(ctx, isCancelled)
            if (isCancelled() || stoppedSince(stopGen)) {
                throw Exception("projection stopped before consent")
            }
            // Arm the sidekick synchronously right before the sheet so the
            // consent automation cannot race the async auto-arm timer. The
            // sidekick also recognises the capture consent class itself; this
            // is a best-effort handshake, never a blocking requirement.
            armSidekickBeforeConsent()
            launchConsentActivity(ctx, token)

            val (code, data) = awaitGrant(grantRequest, deadline, stopGen, isCancelled)
            if (isCancelled() || stoppedSince(stopGen)) {
                throw Exception("projection stopped while waiting for consent")
            }

            val svc = GhostService.instance
                ?: throw Exception("GhostService not running; cannot start projection")
            svc.promoteToProjection()

            val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
                ?: throw Exception("MediaProjectionManager unavailable")
            val projection = try {
                mpm.getMediaProjection(code, data)
            } catch (e: Exception) {
                throw Exception("getMediaProjection failed: ${e.message}")
            } ?: throw Exception("getMediaProjection returned null")

            val grant = Grant(token, projection)
            val record = ActiveProjection(grant)
            record.callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    grant.stopped = true
                    synchronized(hubLock) {
                        // Only the still-active token may clear the slot: an
                        // old projection's callback must never disturb a
                        // newer capture.
                        if (active === record) active = null
                    }
                    if (!hasProjection()) GhostService.instance?.demoteToBase()
                }
            }
            grant.callback = record.callback

            val previous: ActiveProjection?
            synchronized(hubLock) {
                if (stopGen != stopGeneration) {
                    // stop() ran while we were creating: never adopt this one.
                    grant.released = true
                    previous = null
                } else {
                    previous = active
                    active = record
                }
            }
            if (grant.released) {
                try {
                    projection.stop()
                } catch (_: Exception) {
                }
                throw Exception("projection stopped while waiting for consent")
            }
            try {
                projection.registerCallback(record.callback, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                release(grant)
                throw Exception("cannot observe projection: ${e.message}")
            }
            // An orphan from a crashed/raced capture must not linger.
            previous?.let { release(it.grant) }
            return grant
        } finally {
            synchronized(hubLock) {
                if (pending === grantRequest) pending = null
            }
            grantRequest.waiter = null
            // Wake followers so they can re-request instead of timing out.
            grantRequest.latch.countDown()
        }
    }

    /**
     * Make sure the keyguard is gone before the MediaProjection consent sheet
     * is launched.
     *
     * If the device is locked, wake it and try the PIN stored by an earlier
     * `unlock`. When no PIN is stored, or the stored-PIN attempt leaves the
     * keyguard up, throw a clear error immediately — the caller reports it
     * instead of showing a sheet nobody can confirm for 120 s. This never
     * touches grant tokens and runs before any consent UI appears.
     */
    private fun ensureUnlockedForConsent(ctx: Context, isCancelled: () -> Boolean) {
        val km = try {
            ctx.getSystemService(KeyguardManager::class.java)
        } catch (_: Exception) {
            null
        }
        // If KeyguardManager is unavailable we cannot judge the state; keep
        // the previous behaviour rather than blocking every capture.
        if (km == null) return
        val locked = keyguardLocked(km)
        val hasPin = try {
            Prefs.hasPin(ctx)
        } catch (_: Exception) {
            false
        }
        when (CaptureUnlockPolicy.decide(locked, hasPin)) {
            ConsentUnlockAction.PROCEED -> return
            ConsentUnlockAction.BLOCK -> {
                // Turn the screen on so the operator can unlock manually.
                wakeForConsent(ctx)
                throw Exception(CaptureUnlockPolicy.lockedError(false))
            }
            ConsentUnlockAction.TRY_STORED_PIN -> {
                wakeForConsent(ctx)
                if (!isCancelled()) {
                    try {
                        CommandHandler.unlockWithStoredPin(ctx)
                    } catch (_: Exception) {
                        // Reported below as "still locked", with an action.
                    }
                }
                if (isCancelled()) throw Exception("projection stopped before consent")
                if (!keyguardLocked(km)) return
                throw Exception(CaptureUnlockPolicy.lockedError(true))
            }
        }
    }

    private fun keyguardLocked(km: KeyguardManager): Boolean = try {
        km.isKeyguardLocked || km.isDeviceLocked
    } catch (_: Exception) {
        true
    }

    private fun wakeForConsent(ctx: Context) {
        try {
            SystemControl.wake(ctx)
        } catch (_: Exception) {
        }
    }

    /**
     * Arm request sent immediately before the consent sheet. The short TTL
     * scopes ghosta's expectation to this one MediaProjection prompt instead
     * of leaving an indefinite "expect projection" mode armed.
     */
    internal fun consentArmMessage(): JSONObject = JSONObject()
        .put("cmd", "arm")
        .put("expect", "projection")
        .put("ttl_ms", CONSENT_ARM_TTL_MS)

    /**
     * Best-effort synchronous arm immediately before the consent sheet.
     * Localhost connects are refused immediately when the sidekick is gone,
     * so this cannot turn a missing sidekick into a long delay.
     */
    private fun armSidekickBeforeConsent() {
        try {
            if (!SidekickLink.isConnected() && !SidekickLink.ensureConnected()) return
            SidekickLink.send(consentArmMessage())
        } catch (_: Exception) {
            // no sidekick: the human taps the sheet, or the always-on
            // recognised-class handling in ghosta deals with it.
        }
    }

    /** Start the helper Activity with its grant token; never under [hubLock]. */
    private fun launchConsentActivity(ctx: Context, token: Long) {
        try {
            ctx.startActivity(
                Intent(ctx, ScreenCaptureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_TOKEN, token)
            )
        } catch (e: Exception) {
            throw Exception("cannot start consent activity: ${e.message}")
        }
    }

    private fun awaitGrant(
        grantRequest: PendingGrant,
        deadline: Long,
        stopGen: Long,
        isCancelled: () -> Boolean
    ): Pair<Int, Intent> {
        grantRequest.waiter = Thread.currentThread()
        try {
            val remaining = deadline - elapsedMs()
            if (remaining <= 0L) {
                throw Exception("projection grant timeout (user did not confirm)")
            }
            val signalled = try {
                grantRequest.latch.await(remaining, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Exception("projection grant wait interrupted (screen stopped?)")
            }
            if (!signalled) {
                throw Exception("projection grant timeout (user did not confirm)")
            }
            if (grantRequest.cancelled || isCancelled() || stoppedSince(stopGen)) {
                throw Exception("projection stopped while waiting for consent")
            }
            val code = grantRequest.resultCode
            val data = grantRequest.data
            if (code == 0 || data == null) throw Exception("projection denied by user")
            return Pair(code, data)
        } finally {
            grantRequest.waiter = null
        }
    }

    /** Follower wait: bounded by the SAME overall deadline as the leader. */
    private fun awaitLeader(grantRequest: PendingGrant, deadline: Long, stopGen: Long) {
        val remaining = deadline - elapsedMs()
        if (remaining <= 0L) {
            throw Exception("projection grant timeout (user did not confirm)")
        }
        val signalled = try {
            grantRequest.latch.await(remaining, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Exception("projection grant wait interrupted (screen stopped?)")
        }
        if (grantRequest.cancelled || stoppedSince(stopGen)) {
            throw Exception("projection stopped while waiting for consent")
        }
        if (!signalled) {
            throw Exception("projection timeout waiting for the consent dialog")
        }
    }

    /** Called by [ScreenCaptureActivity] with the consent result and its token. */
    fun onGrant(token: Long, resultCode: Int, data: Intent?) {
        synchronized(hubLock) {
            val grantRequest = pending ?: return
            if (grantRequest.token != token) return  // late result from an old sheet
            pending = null
            grantRequest.resultCode = resultCode
            grantRequest.data = data
            grantRequest.latch.countDown()
        }
    }

    /**
     * Called when the consent Activity dies without a result (back press,
     * task removal, crash) so the waiter fails immediately instead of
     * burning the whole grant timeout.
     */
    fun onGrantCancelled(token: Long) {
        synchronized(hubLock) {
            val grantRequest = pending ?: return
            if (grantRequest.token != token) return
            pending = null
            grantRequest.cancelled = true
            grantRequest.latch.countDown()
        }
    }

    /**
     * Release exactly this session's projection. Idempotent and token-safe:
     * an old grant can never clear or stop a newer active projection.
     */
    fun release(grant: Grant?) {
        if (grant == null) return
        val first = synchronized(hubLock) {
            if (active?.grant === grant) active = null
            if (grant.released) {
                false
            } else {
                grant.released = true
                true
            }
        }
        if (!first) return
        grant.stopped = true
        grant.callback?.let { cb ->
            grant.callback = null
            try {
                grant.projection.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        try {
            grant.projection.stop()
        } catch (_: Exception) {
        }
        if (!hasProjection()) GhostService.instance?.demoteToBase()
    }

    /**
     * Stop the current projection and cancel any pending consent request.
     * Never waits on a lock held by a grant waiter, so `screen off`, service
     * destruction and a wedged capture cannot deadlock it.
     */
    fun stop() {
        val cancelled: PendingGrant?
        val stopped: ActiveProjection?
        synchronized(hubLock) {
            stopGeneration++
            cancelled = pending
            pending = null
            cancelled?.cancelled = true
            cancelled?.latch?.countDown()
            stopped = active
            active = null
            stopped?.grant?.stopped = true
        }
        cancelled?.waiter?.interrupt()
        stopped?.let { release(it.grant) }
    }

    private fun stoppedSince(stopGen: Long): Boolean =
        synchronized(hubLock) { stopGen != stopGeneration }

    private fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    /** Hard bound for one consent request (dialog + user decision). */
    private const val GRANT_TIMEOUT_MS = 120_000L

    /** Lifetime of the pre-consent `expect:projection` arm in ghosta. */
    private const val CONSENT_ARM_TTL_MS = 15_000
}
