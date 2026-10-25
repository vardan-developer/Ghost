package com.tg.ghostd

import android.content.Context
import android.os.PowerManager
import java.util.Timer
import java.util.TimerTask

/**
 * Keeps the display awake while an operator command or a capture stream is
 * running, so the per-capture MediaProjection consent sheet and the sidekick
 * unlock/consent flows are not raced by the screen turning off.
 *
 * Design:
 *  - Reference counted by opaque tokens: any number of concurrent commands /
 *    streams share ONE screen wake lock, released as soon as the last session
 *    ends (or [releaseAll] runs on disconnect / service teardown).
 *  - Token based: [end] is idempotent, and a stale or duplicated token can
 *    never release a newer session.
 *  - Bounded: acquired with a short timeout and renewed only for sessions that
 *    showed activity ([refresh]) within [IDLE_TIMEOUT_MS]. Sessions idle past
 *    that cap are dropped and released automatically, so a leaked token cannot
 *    keep renewing the lock forever.
 *  - Best effort: a missing or failing PowerManager never fails a command.
 *
 * The CPU-only PARTIAL_WAKE_LOCK stays in [MediaBus]; this controller owns
 * only the screen. It never touches SCREEN_OFF_TIMEOUT (a user/system setting).
 */
object ScreenAwakeController {

    const val LOCK_TAG = "ghostd:screen-awake"

    /** Bright wake lock, injectable so the state machine is JVM-tested. */
    internal interface Lock {
        /** (Re)acquire for [timeoutMs]; returns false when it failed. */
        fun acquire(timeoutMs: Long): Boolean
        fun release()
    }

    /** Renewal timer, injectable so tests drive ticks deterministically. */
    internal fun interface Scheduler {
        fun schedule(periodMs: Long, task: () -> Unit): AutoCloseable
    }

    /** Bounded hold: the OS drops the lock by itself if we ever fail to renew. */
    internal const val LOCK_TIMEOUT_MS = 15_000L

    /** Renew well inside [LOCK_TIMEOUT_MS] while any session is active. */
    internal const val RENEW_INTERVAL_MS = 10_000L

    /** A session with no [refresh] for this long is stale and gets dropped. */
    internal const val IDLE_TIMEOUT_MS = 120_000L

    private val lock = Any()

    /** Live sessions: token -> monotonic timestamp of its last activity. */
    private val sessions = LinkedHashMap<Long, Long>()
    private var nextToken = 1L
    private var held: Lock? = null
    private var renewal: AutoCloseable? = null

    @Volatile internal var lockSource: (Context?) -> Lock? = { ctx -> ctx?.let(::platformLock) }
    @Volatile internal var scheduler: Scheduler = TimerScheduler
    @Volatile internal var clock: () -> Long = { System.nanoTime() / 1_000_000L }

    /**
     * Start a session and return its token. Never throws: if the lock cannot
     * be created or acquired the session is still tracked (the renewal retries
     * while it stays active) and the command proceeds normally.
     */
    fun begin(ctx: Context?): Long {
        synchronized(lock) {
            val token = nextToken++
            sessions[token] = clock()
            if (held == null) {
                held = try {
                    lockSource(ctx)
                } catch (_: Exception) {
                    null
                }
            }
            acquire(held)
            if (renewal == null) {
                renewal = try {
                    scheduler.schedule(RENEW_INTERVAL_MS) { renew() }
                } catch (_: Exception) {
                    null
                }
            }
            return token
        }
    }

    /** End one session. Idempotent; a stale token releases nothing. */
    fun end(token: Long) {
        synchronized(lock) {
            if (sessions.remove(token) == null) return
            if (sessions.isNotEmpty()) return
            stopRenewal()
            releaseHeld()
        }
    }

    /**
     * Mark operator/stream activity for a live session, extending its idle
     * allowance. Unknown or already-expired tokens are ignored (idempotent);
     * an expired session can never be resurrected by a late frame.
     */
    fun refresh(token: Long) {
        if (token == 0L) return
        synchronized(lock) {
            if (!sessions.containsKey(token)) return
            sessions[token] = clock()
        }
    }

    /** Explicit "force" teardown of one session (wedged command/stream owner). */
    fun forceEnd(token: Long) = end(token)

    /** Drop every session and the lock now (disconnect, service destruction). */
    fun releaseAll() {
        synchronized(lock) {
            sessions.clear()
            stopRenewal()
            releaseHeld()
        }
    }

    internal fun activeCount(): Int = synchronized(lock) { sessions.size }

    internal fun isActive(token: Long): Boolean = synchronized(lock) { sessions.containsKey(token) }

    /** Restore the production hooks after a unit test injected fakes. */
    internal fun resetHooks() {
        lockSource = { ctx -> ctx?.let(::platformLock) }
        scheduler = TimerScheduler
        clock = { System.nanoTime() / 1_000_000L }
    }

    // ------------------------------------------------------------- internals

    private fun renew() {
        synchronized(lock) {
            // A tick can still be queued while/after the last end (or
            // releaseAll); never re-acquire a lock we already gave back.
            if (renewal == null) return
            val now = clock()
            if (sessions.isNotEmpty()) {
                sessions.entries.removeAll { now - it.value >= IDLE_TIMEOUT_MS }
            }
            if (sessions.isEmpty()) {
                // Everything idle-expired (or there was nothing live): stop
                // renewing and let the screen go.
                stopRenewal()
                releaseHeld()
                return
            }
            acquire(held)
        }
    }

    private fun acquire(l: Lock?) {
        val wake = l ?: return
        try {
            wake.acquire(LOCK_TIMEOUT_MS)
        } catch (_: Exception) {
            // keep-awake is best effort: never fail the command/stream
        }
    }

    private fun stopRenewal() {
        try {
            renewal?.close()
        } catch (_: Exception) {
        }
        renewal = null
    }

    private fun releaseHeld() {
        val l = held ?: return
        held = null
        try {
            l.release()
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun platformLock(ctx: Context): Lock? {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return null
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            LOCK_TAG
        )
        wl.setReferenceCounted(false)
        return object : Lock {
            override fun acquire(timeoutMs: Long): Boolean = try {
                // Re-arm the bound on every renewal instead of stacking
                // references; with refcounting off one release is enough.
                if (wl.isHeld) wl.release()
                wl.acquire(timeoutMs)
                wl.isHeld
            } catch (_: Exception) {
                false
            }

            override fun release() {
                try {
                    if (wl.isHeld) wl.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private object TimerScheduler : Scheduler {
        override fun schedule(periodMs: Long, task: () -> Unit): AutoCloseable {
            val timer = Timer(LOCK_TAG, true)
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        task()
                    } catch (_: Exception) {
                    }
                }
            }, periodMs, periodMs)
            return AutoCloseable { timer.cancel() }
        }
    }
}
