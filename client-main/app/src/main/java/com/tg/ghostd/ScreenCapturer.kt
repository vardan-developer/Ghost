package com.tg.ghostd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.neovisionaries.ws.client.WebSocket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Screen capture verbs: shot (single PNG, base64 in the command result),
 * record (bounded MJPEG-over-WS stream), live (open-ended stream).
 *
 * Consent is requested per capture/session: every shot/stream obtains a fresh
 * [ProjectionHub.Grant] and releases exactly that projection on every exit
 * (success, error, stop, timeout, first-frame failure).
 *
 * Each capture runs in a [CaptureSession] with its own generation, worker,
 * virtual display, ImageReader, frame queue and projection. All shared-slot
 * mutations (MediaBus claim/release, `current`) happen under [lifecycle], and
 * an old worker checks [isCurrent] before sending or clearing anything, so a
 * timed-out/stopped session can never stop, clear or send into a newer one.
 *
 * The actual capture work runs on a dedicated worker thread; the command
 * thread waits with a HARD time-box, so `screen shot` always answers. Every
 * frame wait is bounded, interruptible and stop-aware; `screen stop`/`screen
 * off` wake the shot waiter, interrupt the worker and release the projection.
 */
object ScreenCapturer {
    /** ImageReader callbacks need a Looper; capture runs on plain worker
     *  threads (no Looper) — always hand them the main looper.
     *  Lazy: JVM unit tests touch this class without an Android runtime. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** One capture session. Fields are owned by the session's worker plus the
     *  force-stop path; [resLock] makes teardown idempotent. */
    private class CaptureSession(val gen: Long) {
        val lifecycle = Any()
        val resLock = Any()
        val stopRequested = AtomicBoolean(false)
        val done = CountDownLatch(1)

        @Volatile var worker: Thread? = null
        @Volatile var grant: ProjectionHub.Grant? = null
        @Volatile var display: VirtualDisplay? = null
        @Volatile var reader: ImageReader? = null
        @Volatile var frames: BlockingQueue<Image>? = null
        @Volatile var framesSent = 0L
        @Volatile var failure: Exception? = null
        /** True while this session still owns the MediaBus slot. */
        @Volatile var busOwned = false
    }

    private val lifecycle = Any()
    private val genCounter = AtomicLong(0L)

    /** The session allowed to touch the bus / projection. Older workers see
     *  `current !== this` and must not send, clear or stop anything. */
    @Volatile private var current: CaptureSession? = null

    // ------------------------------------------------------------------ shot

    /**
     * Single-frame capture. Runs the capture on a worker thread and waits
     * for it with a hard budget: the command response NEVER hangs forever,
     * and on timeout the session is force-stopped and its projection released.
     */
    fun shot(ctx: Context, ws: WebSocket?): JSONObject {
        val session = newSession()
        beginCapture(ctx, ws, session)

        val out = arrayOfNulls<JSONObject>(1)
        val err = arrayOfNulls<Exception>(1)
        val worker = Thread {
            try {
                out[0] = shotInternal(ctx, session)
            } catch (e: Exception) {
                err[0] = e
            } finally {
                finishSession(session)
                session.done.countDown()
            }
        }.apply { name = "ghostd-shot"; isDaemon = true }
        session.worker = worker
        worker.start()

        val deadline = elapsedMs() + SHOT_BUDGET_MS
        var completed = false
        while (true) {
            val remaining = deadline - elapsedMs()
            if (remaining <= 0L) break
            try {
                if (session.done.await(remaining, TimeUnit.MILLISECONDS)) {
                    completed = true
                    break
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!completed) {
            // Wedged worker (stuck binder call etc.): release worker, bus and
            // projection so later screen verbs work, and answer the command.
            forceStopSession(session)
            throw Exception(
                "screen shot timed out after ${SHOT_BUDGET_MS / 1000}s (capture reset; try again)"
            )
        }
        // stop()/screen off counts the latch down too: report the stop rather
        // than a half-written result from the interrupted worker.
        if (session.stopRequested.get()) throw Exception("screen capture stopped")
        err[0]?.let { throw it }
        return out[0] ?: throw Exception("screen shot failed")
    }

    private fun shotInternal(ctx: Context, session: CaptureSession): JSONObject {
        val grant = ProjectionHub.ensureProjection(ctx) { session.stopRequested.get() }
        session.grant = grant
        ensureRunning(session, grant)

        val (w, h, dpi) = displaySize(ctx)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, SHOT_QUEUE)
        synchronized(session.resLock) { session.reader = reader }
        val frames = ArrayBlockingQueue<Image>(SHOT_QUEUE)
        session.frames = frames
        reader.setOnImageAvailableListener({ r ->
            offerLatest(r, frames, session)
        }, mainHandler)

        var vd: VirtualDisplay? = null
        try {
            ensureRunning(session, grant)
            vd = grant.projection.createVirtualDisplay(
                "ghostd-shot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            synchronized(session.resLock) {
                if (session.display == null) session.display = vd
            }
            val image = pollFrame(session, frames, FRAME_WAIT_MS)
                ?: throw Exception(
                    "screen capture timed out (no frame in ${FRAME_WAIT_MS / 1000}s)"
                )
            try {
                val bmp = toBitmap(image)
                try {
                    val png = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, png)
                    return JSONObject()
                        .put("width", bmp.width)
                        .put("height", bmp.height)
                        .put("png_b64", Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP))
                } finally {
                    bmp.recycle()
                }
            } finally {
                image.close()
            }
        } finally {
            synchronized(session.resLock) {
                if (session.display === vd) session.display = null
                if (session.reader === reader) session.reader = null
                if (session.frames === frames) session.frames = null
                // Null before draining so a late callback (same lock) cannot
                // enqueue into an already-drained queue.
                drainQueue(frames)
            }
            // An earlier force-stop may already have released these; release
            // calls are idempotent and wrapped so teardown never throws.
            try {
                vd?.release()
            } catch (_: Exception) {
            }
            try {
                reader.close()
            } catch (_: Exception) {
            }
        }
    }

    // ----------------------------------------------------------- record/live

    /**
     * Stream the screen as MJPEG frames over the WebSocket.
     * @param secs duration in seconds, or null for live (until [stop], a
     *             MediaProjection revoke, or the cast indicator being stopped).
     */
    fun stream(
        ctx: Context, ws: WebSocket, secs: Int?, fps: Int?, quality: Int?,
        streamName: String
    ): JSONObject {
        val session = newSession()
        beginCapture(ctx, ws, session)

        // Fresh grant per stream. On failure the busy slot and any partial
        // projection state MUST be released before propagating the error.
        val grant = try {
            ProjectionHub.ensureProjection(ctx) { session.stopRequested.get() }
        } catch (e: Exception) {
            forceStopSession(session)
            throw e
        }
        session.grant = grant
        if (session.stopRequested.get() || grant.stopped) {
            forceStopSession(session)
            throw Exception("screen $streamName: projection stopped before capture")
        }

        val worker = Thread {
            try {
                runStream(ctx, session, grant, secs, fps, quality, streamName)
            } catch (e: Exception) {
                session.failure = e
            } finally {
                finishSession(session)
            }
        }.apply { name = "ghostd-screen"; isDaemon = true }
        session.worker = worker
        worker.start()

        // Do not report success before a frame actually exists: a live stream
        // that silently produces nothing used to look healthy in the console.
        val deadline = elapsedMs() + FIRST_FRAME_WAIT_MS
        while (session.framesSent == 0L) {
            if (session.stopRequested.get() || grant.stopped || !isCurrent(session)) break
            if (elapsedMs() >= deadline) break
            try {
                Thread.sleep(FIRST_FRAME_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (session.framesSent == 0L) {
            val reason = when {
                session.stopRequested.get() -> "stopped"
                grant.stopped -> "projection ended"
                !isCurrent(session) ->
                    session.failure?.message ?: "capture ended before the first frame"
                session.failure != null -> "capture failed: ${session.failure?.message}"
                else -> "no frames after ${FIRST_FRAME_WAIT_MS / 1000}s " +
                    "(projection not granted — unlock the phone and allow the " +
                    "screen-share prompt, then retry)"
            }
            // Required cleanup: worker, MediaBus slot and this projection.
            forceStopSession(session)
            throw Exception("screen $streamName: $reason")
        }
        return JSONObject()
            .put("stream", streamName)
            .put("started", true)
            .put("frames", session.framesSent)
            .put("secs", secs ?: JSONObject.NULL)
    }

    private fun runStream(
        ctx: Context, session: CaptureSession, grant: ProjectionHub.Grant,
        secs: Int?, fps: Int?, quality: Int?, streamName: String
    ) {
        val (w, h, dpi) = displaySize(ctx)
        val preset = QualityPresets.get(null)
        val effFps = (QualityPresets.fpsOverride(fps) ?: preset.fps).coerceAtLeast(1)
        val effQ = QualityPresets.jpegQuality(quality ?: 60)
        val intervalMs = 1000L / effFps

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, STREAM_QUEUE)
        synchronized(session.resLock) { session.reader = reader }
        val frames = ArrayBlockingQueue<Image>(STREAM_QUEUE)
        session.frames = frames
        reader.setOnImageAvailableListener({ r ->
            offerLatest(r, frames, session)
        }, mainHandler)

        var vd: VirtualDisplay? = null
        var sent = 0L
        var last = 0L
        var endReason = "done"
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            ensureRunning(session, grant)
            vd = grant.projection.createVirtualDisplay(
                "ghostd-stream", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            synchronized(session.resLock) {
                if (session.display == null) session.display = vd
            }
            val announced = sendTextOwned(session) {
                MediaBus.streamStart(
                    streamName,
                    JSONObject()
                        .put("width", w).put("height", h)
                        .put("fps", effFps).put("quality", effQ)
                        .put("encoding", "mjpeg")
                )
            }
            if (!announced) {
                endReason = "superseded"
                return
            }

            val deadline = if (secs != null) elapsedMs() + secs * 1000L else Long.MAX_VALUE
            while (true) {
                if (session.stopRequested.get()) {
                    endReason = "stopped"
                    break
                }
                if (grant.stopped) {
                    // User stopped sharing from Android's cast UI.
                    endReason = "projection stopped"
                    break
                }
                if (!isCurrent(session)) {
                    endReason = "superseded"
                    break
                }
                if (elapsedMs() >= deadline) {
                    endReason = if (secs != null) "done" else endReason
                    break
                }
                // bounded + interruptible: screen stop breaks out of here
                val image = try {
                    frames.poll(FRAME_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    endReason = if (session.stopRequested.get()) "stopped" else "interrupted"
                    break
                }
                try {
                    if (session.stopRequested.get() || grant.stopped || !isCurrent(session)) {
                        endReason = when {
                            session.stopRequested.get() -> "stopped"
                            grant.stopped -> "projection stopped"
                            else -> "superseded"
                        }
                        break
                    }
                    val now = elapsedMs()
                    if (now - last < intervalMs) continue
                    fillBitmap(image, bmp)
                    val jpeg = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, effQ, jpeg)
                    last = now
                    if (!sendFrameOwned(session, streamName, jpeg.toByteArray())) {
                        endReason = "socket closed"
                        break
                    }
                    sent++
                    session.framesSent = sent
                } finally {
                    image.close()
                }
            }
        } catch (e: Exception) {
            endReason = "error: ${e.message}"
        } finally {
            sendTextOwned(session) { MediaBus.streamEnd(streamName, sent, endReason) }
            synchronized(session.resLock) {
                if (session.display === vd) session.display = null
                if (session.reader === reader) session.reader = null
                if (session.frames === frames) session.frames = null
                // Null before draining so a late callback (same lock) cannot
                // enqueue into an already-drained queue.
                drainQueue(frames)
            }
            try {
                vd?.release()
            } catch (_: Exception) {
            }
            try {
                reader.close()
            } catch (_: Exception) {
            }
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------------ stop

    /**
     * Stop the active capture (stream or in-flight shot) and release its
     * projection. Works even when the worker is wedged: wakes a shot waiter,
     * interrupts the worker, tears down display/reader/queues and
     * force-releases the busy slot.
     */
    fun stop(): JSONObject {
        val session = synchronized(lifecycle) { current }
        if (session != null) {
            session.stopRequested.set(true)
            // Wake a shot() command thread immediately.
            session.done.countDown()
            session.worker?.interrupt()
            // Grace period: a healthy worker exits on its own (and can still
            // deliver stream_end / its command result).
            joinWorker(session, STOP_GRACE_MS)
            forceStopSession(session)
        } else {
            // Orphaned busy slot (e.g. a worker that died without cleanup).
            synchronized(lifecycle) {
                if (MediaBus.isActive(MediaBus.SOURCE_SCREEN)) {
                    MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
                }
            }
        }
        // Per-capture lifecycle: stopping the capture always drops the
        // projection too (the grant Intent/object is never reused).
        ProjectionHub.stop()
        return JSONObject().put("stopping", MediaBus.SOURCE_SCREEN)
    }

    /** Non-blocking capture stop for service destruction. */
    fun requestStopOnly() {
        val session = synchronized(lifecycle) { current } ?: return
        session.stopRequested.set(true)
        session.done.countDown()
        session.worker?.interrupt()
    }

    // --------------------------------------------------------- session state

    private fun newSession(): CaptureSession = CaptureSession(genCounter.incrementAndGet())

    /** Claim the MediaBus slot and publish this session atomically. */
    private fun beginCapture(ctx: Context, ws: WebSocket?, session: CaptureSession) {
        synchronized(lifecycle) {
            MediaBus.begin(MediaBus.SOURCE_SCREEN, ctx, ws)
            session.busOwned = true
            current = session
        }
    }

    /** Normal worker exit: release this session's bus slot and projection. */
    private fun finishSession(session: CaptureSession) {
        synchronized(lifecycle) {
            releaseBusLocked(session, force = false)
            if (current === session) current = null
        }
        releaseResources(session)
        val grant = session.grant
        session.grant = null
        ProjectionHub.release(grant)
    }

    /**
     * Forced teardown (timeout / stop / failed start): never touches a newer
     * session; idempotent with the worker's own [finishSession].
     */
    private fun forceStopSession(session: CaptureSession) {
        session.stopRequested.set(true)
        session.worker?.interrupt()
        session.done.countDown()
        synchronized(lifecycle) {
            releaseBusLocked(session, force = true)
            if (current === session) current = null
        }
        releaseResources(session)
        val grant = session.grant
        session.grant = null
        ProjectionHub.release(grant)
    }

    /** Caller must hold [lifecycle]. */
    private fun releaseBusLocked(session: CaptureSession, force: Boolean) {
        if (!session.busOwned) return
        session.busOwned = false
        if (force) {
            MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
        } else {
            MediaBus.end(MediaBus.SOURCE_SCREEN)
        }
    }

    /** Release display/reader/queue, closing and draining any queued image. */
    private fun releaseResources(session: CaptureSession) {
        synchronized(session.resLock) {
            val display = session.display
            session.display = null
            try {
                display?.release()
            } catch (_: Exception) {
            }
            val reader = session.reader
            session.reader = null
            try {
                reader?.close()
            } catch (_: Exception) {
            }
            // Null the live queue BEFORE draining: a late ImageReader callback
            // (which takes this same lock and checks identity) can then only
            // close its image, never enqueue it into a queue nobody drains.
            val frames = session.frames
            session.frames = null
            frames?.let { drainQueue(it) }
        }
    }

    private fun joinWorker(session: CaptureSession, timeoutMs: Long) {
        val worker = session.worker ?: return
        if (worker === Thread.currentThread()) return
        try {
            worker.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun isCurrent(session: CaptureSession): Boolean =
        synchronized(lifecycle) { current === session }

    private fun ensureRunning(session: CaptureSession, grant: ProjectionHub.Grant) {
        if (session.stopRequested.get()) throw Exception("screen capture stopped")
        if (grant.stopped) throw Exception("screen capture stopped (projection ended)")
        if (!isCurrent(session)) throw Exception("screen capture superseded")
    }

    /** Answer/start/end/frame only while this session still owns the bus. */
    private inline fun sendTextOwned(session: CaptureSession, block: () -> Unit): Boolean =
        synchronized(lifecycle) {
            if (current !== session) return false
            block()
            true
        }

    private fun sendFrameOwned(
        session: CaptureSession, streamName: String, payload: ByteArray
    ): Boolean = synchronized(lifecycle) {
        if (current !== session) return false
        MediaBus.sendFrame(streamName, payload)
    }

    /** Drain a bounded image queue, closing every queued Image. */
    private fun drainQueue(frames: BlockingQueue<Image>) {
        while (true) {
            val image = frames.poll() ?: return
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Bounded, interruptible and stop-aware frame wait. Throws when the
     * capture was stopped/interrupted or the projection ended; returns null
     * only on timeout.
     */
    private fun pollFrame(
        session: CaptureSession, frames: BlockingQueue<Image>, timeoutMs: Long
    ): Image? {
        val deadline = elapsedMs() + timeoutMs
        while (elapsedMs() < deadline) {
            ensureRunning(session, session.grant ?: throw Exception("screen capture has no projection"))
            try {
                frames.poll(FRAME_POLL_MS, TimeUnit.MILLISECONDS)?.let { return it }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Exception("screen capture interrupted (stopped)")
            }
        }
        return null
    }

    /**
     * Admission check for an ImageReader callback: a frame may only enter the
     * queue while the session is running and still publishes exactly this
     * queue. Evaluated under [CaptureSession.resLock]. Pure, so it is unit
     * tested without an Android ImageReader.
     */
    internal fun frameQueueRegistered(
        stopRequested: Boolean, registered: Any?, candidate: Any
    ): Boolean = !stopRequested && registered === candidate

    private fun offerLatest(
        reader: ImageReader, frames: BlockingQueue<Image>, session: CaptureSession
    ) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        // Teardown nulls the queue and drains it under resLock. Holding the
        // same lock here means a frame acquired concurrently either enters
        // before teardown (and is drained) or is rejected and closed -- never
        // orphaned in a queue that is no longer reachable.
        val queued = synchronized(session.resLock) {
            if (frameQueueRegistered(session.stopRequested.get(), session.frames, frames)) {
                frames.offer(image)
            } else {
                false
            }
        }
        if (!queued) {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ util

    private fun displaySize(ctx: Context): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun toBitmap(image: Image): Bitmap {
        val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        fillBitmap(image, bmp)
        return bmp
    }

    /** Copy an RGBA_8888 Image (any rowStride/pixelStride) into a Bitmap. */
    private fun fillBitmap(image: Image, bmp: Bitmap) {
        val plane = image.planes[0]
        val buffer = plane.buffer.rewind() as java.nio.ByteBuffer
        val fast = plane.rowStride == bmp.width * 4 && plane.pixelStride == 4
        if (fast) {
            bmp.copyPixelsFromBuffer(buffer)
            return
        }
        // generic repack for padded buffers (rare on virtual displays)
        val pixels = IntArray(bmp.width * bmp.height)
        var i = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val idx = y * plane.rowStride + x * plane.pixelStride
                val r = buffer.get(idx).toInt() and 0xff
                val g = buffer.get(idx + 1).toInt() and 0xff
                val b = buffer.get(idx + 2).toInt() and 0xff
                val a = buffer.get(idx + 3).toInt() and 0xff
                pixels[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }

    private fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    /** Hard budget for the whole shot (grant wait + frame + encode). */
    private const val SHOT_BUDGET_MS = 135_000L

    /** How long `screen live` waits for its first frame before reporting failure. */
    private const val FIRST_FRAME_WAIT_MS = 8_000L

    /** Max wait for the first (single-frame) frame. */
    private const val FRAME_WAIT_MS = 10_000L

    /** How long stop() waits for a healthy worker to exit by itself. */
    private const val STOP_GRACE_MS = 2_000L

    /** Bounded frame poll while waiting for stop / projection revoke. */
    private const val FRAME_POLL_MS = 500L
    private const val FIRST_FRAME_POLL_MS = 100L

    /** Bounded image queues: excess frames are closed, not accumulated. */
    private const val SHOT_QUEUE = 2
    private const val STREAM_QUEUE = 3
}
