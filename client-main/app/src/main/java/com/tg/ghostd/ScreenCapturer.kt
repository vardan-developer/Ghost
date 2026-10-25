package com.tg.ghostd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.neovisionaries.ws.client.WebSocket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Screen capture verbs: shot (single PNG, base64 in the command result),
 * record (bounded MJPEG-over-WS stream), live (open-ended stream).
 *
 * Virtual display + ImageReader(RGBA_8888) -> Bitmap -> JPEG at --quality,
 * throttled to --fps. JPEG/MJPEG chosen over H.264 for v1: single-frame
 * decodability, no mux/SPS-PPS plumbing, tunable per-frame quality — one
 * line of justification, as requested.
 *
 * Wedge-safety (0.2.8): the actual capture work (consent wait, virtual
 * display, frame wait, encode) runs on a dedicated worker thread; the
 * command thread waits for it with a HARD time-box ([SHOT_BUDGET_S]) and
 * force-releases the MediaBus slot on timeout, so `screen shot` always
 * answers and the "busy" lock can never wedge. Every frame wait is
 * bounded, interruptible and stop-aware: `screen stop`/`screen off`
 * interrupt the worker and tear down the virtual display / ImageReader /
 * projection, so the stop path works even when a capture is stuck.
 */
object ScreenCapturer {
    /** ImageReader callbacks need a Looper; capture runs on plain worker
     *  threads (no Looper) since 0.2.8 — always hand them the main looper.
     *  Lazy: JVM unit tests touch this class without an Android runtime. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var stopRequested = false

    /** Frames pushed to the server by the active stream (first-frame check). */
    @Volatile private var framesSent = 0L

    /** The active shot/stream worker (null when idle) — interrupted by stop(). */
    @Volatile private var worker: Thread? = null
    @Volatile private var currentDisplay: VirtualDisplay? = null
    @Volatile private var currentReader: ImageReader? = null

    // ------------------------------------------------------------------ shot

    /**
     * Single-frame capture. Runs the capture on a worker thread and waits
     * for it with a hard budget: the command response NEVER hangs forever,
     * and on timeout the busy lock is force-released (retry-able).
     */
    fun shot(ctx: Context, ws: WebSocket?): JSONObject {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, ctx, ws)
        stopRequested = false
        val done = CountDownLatch(1)
        val out = arrayOfNulls<JSONObject>(1)
        val err = arrayOfNulls<Exception>(1)
        val t = Thread {
            try {
                out[0] = shotInternal(ctx)
            } catch (e: Exception) {
                err[0] = e
            } finally {
                MediaBus.end(MediaBus.SOURCE_SCREEN)
                worker = null
                done.countDown()
            }
        }.apply { name = "ghostd-shot"; isDaemon = true }
        worker = t
        t.start()

        if (!done.await(SHOT_BUDGET_S, TimeUnit.SECONDS)) {
            // Worker wedged (stuck binder call etc.): unstick everything so
            // later screen verbs work, and answer the command now.
            teardownWorker()
            MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
            throw Exception(
                "screen shot timed out after ${SHOT_BUDGET_S}s (capture reset; try again)"
            )
        }
        err[0]?.let { throw it }
        return out[0] ?: throw Exception("screen shot failed")
    }

    private fun shotInternal(ctx: Context): JSONObject {
        val projection = ProjectionHub.ensureProjection(ctx)
        val (w, h, dpi) = displaySize(ctx)
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        currentReader = reader
        val frames = LinkedBlockingQueue<Image>()
        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.let { frames.offer(it) }
            } catch (_: Exception) {
            }
        }, mainHandler)
        var vd: VirtualDisplay? = null
        try {
            vd = projection.createVirtualDisplay(
                "ghostd-shot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            currentDisplay = vd
            val image = pollFrame(frames, FRAME_WAIT_S)
                ?: throw Exception("screen capture timed out (no frame in ${FRAME_WAIT_S}s)")
            try {
                val bmp = toBitmap(image)
                val png = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, png)
                return JSONObject()
                    .put("width", bmp.width)
                    .put("height", bmp.height)
                    .put("png_b64", Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP))
            } finally {
                image.close()
            }
        } finally {
            currentDisplay = null
            currentReader = null
            vd?.release()
            reader.close()
        }
    }

    // ----------------------------------------------------------- record/live

    /**
     * Stream the screen as MJPEG frames over the WebSocket.
     * @param secs duration in seconds, or null for live (until [stop]).
     */
    fun stream(ctx: Context, ws: WebSocket, secs: Int?, fps: Int?, quality: Int?, streamName: String): JSONObject {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, ctx, ws)
        // Projection grant may need a human tap: do it BEFORE starting the
        // stream thread so the command response reflects grant failures.
        // On failure the busy slot MUST be released again (0.2.8: leaked
        // here before, wedging every later capture as "busy").
        val projection = try {
            ProjectionHub.ensureProjection(ctx)
        } catch (e: Exception) {
            MediaBus.end(MediaBus.SOURCE_SCREEN)
            throw e
        }
        stopRequested = false
        framesSent = 0L
        val t = Thread {
            try {
                runStream(ctx, projection, secs, fps, quality, streamName)
            } catch (_: Exception) {
                // stream died (socket gone or capture error); cleaned up below
            } finally {
                MediaBus.end(MediaBus.SOURCE_SCREEN)
                worker = null
            }
        }.apply { name = "ghostd-screen"; isDaemon = true; start() }
        worker = t
        // Do not report success before a frame actually exists: a live stream
        // that silently produces nothing (projection revoked while locked, or
        // the display was already used) used to look healthy in the console.
        val t0 = System.currentTimeMillis()
        while (framesSent == 0L && worker === t &&
            System.currentTimeMillis() - t0 < FIRST_FRAME_WAIT_MS
        ) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (framesSent == 0L) {
            throw Exception(
                "screen $streamName: no frames after ${FIRST_FRAME_WAIT_MS / 1000}s " +
                    "(projection not granted — unlock the phone and allow the " +
                    "screen-share prompt, then retry)"
            )
        }
        return JSONObject()
            .put("stream", streamName)
            .put("started", true)
            .put("frames", framesSent)
            .put("secs", secs ?: JSONObject.NULL)
    }

    private fun runStream(
        ctx: Context, projection: MediaProjection,
        secs: Int?, fps: Int?, quality: Int?,
        streamName: String
    ) {
        val (w, h, dpi) = displaySize(ctx)
        val preset = QualityPresets.get(null)
        val effFps = QualityPresets.fpsOverride(fps) ?: preset.fps
        val effQ = QualityPresets.jpegQuality(quality ?: 60)
        val intervalMs = 1000L / effFps

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        currentReader = reader
        val frames = LinkedBlockingQueue<Image>()
        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.let { frames.offer(it) }
            } catch (_: Exception) {
            }
        }, mainHandler)
        var vd: VirtualDisplay? = null
        var sent = 0L
        val last = longArrayOf(0L)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            vd = projection.createVirtualDisplay(
                "ghostd-stream", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            currentDisplay = vd
            MediaBus.streamStart(
                streamName,
                JSONObject()
                    .put("width", w).put("height", h)
                    .put("fps", effFps).put("quality", effQ)
                    .put("encoding", "mjpeg")
            )
            val deadline = if (secs != null) System.currentTimeMillis() + secs * 1000L else Long.MAX_VALUE
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                // bounded + interruptible: screen stop breaks out of here
                val image = try {
                    frames.poll(500, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    val now = System.currentTimeMillis()
                    if (now - last[0] < intervalMs) continue
                    fillBitmap(image, bmp)
                    val jpeg = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, effQ, jpeg)
                    last[0] = now
                    if (!MediaBus.sendFrame(streamName, jpeg.toByteArray())) {
                        MediaBus.streamEnd(streamName, sent, "socket closed")
                        return
                    }
                    sent++
                    framesSent = sent
                } finally {
                    image.close()
                }
            }
            MediaBus.streamEnd(streamName, sent, if (stopRequested) "stopped" else "done")
        } finally {
            currentDisplay = null
            currentReader = null
            vd?.release()
            reader.close()
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------------ stop

    /**
     * Stop the active capture (stream or in-flight shot). Works even when
     * the worker is wedged: interrupts it, tears down the virtual display /
     * ImageReader and force-releases the busy slot if it does not exit on
     * its own within a short grace period.
     */
    fun stop(): JSONObject {
        stopRequested = true
        worker?.interrupt()
        // Grace period: a healthy worker exits on its own (and can still
        // deliver stream_end / its command result).
        var waited = 0
        while (worker != null && waited < STOP_GRACE_MS) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            waited += 100
        }
        if (worker != null || MediaBus.isActive(MediaBus.SOURCE_SCREEN)) {
            // wedged worker or orphaned busy slot: hard teardown so the
            // next capture can start
            teardownWorker()
            MediaBus.forceEnd(MediaBus.SOURCE_SCREEN)
        }
        return JSONObject().put("stopping", MediaBus.SOURCE_SCREEN)
    }

    fun requestStopOnly() {
        stopRequested = true
        worker?.interrupt()
    }

    /** Interrupt the worker and release its capture resources (idempotent). */
    private fun teardownWorker() {
        worker?.interrupt()
        currentDisplay?.let { d ->
            try {
                d.release()
            } catch (_: Exception) {
            }
        }
        currentDisplay = null
        currentReader?.let { r ->
            try {
                r.close()
            } catch (_: Exception) {
            }
        }
        currentReader = null
    }

    // ------------------------------------------------------------------ util

    /**
     * Bounded, interruptible and stop-aware frame wait. Throws when the
     * capture was stopped/interrupted; returns null only on timeout.
     */
    private fun pollFrame(frames: LinkedBlockingQueue<Image>, timeoutS: Long): Image? {
        val deadline = System.currentTimeMillis() + timeoutS * 1000
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) throw Exception("screen capture stopped")
            try {
                frames.poll(250, TimeUnit.MILLISECONDS)?.let { return it }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Exception("screen capture interrupted (stopped)")
            }
        }
        return null
    }

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

    /** Hard budget for the whole shot (grant wait + frame + encode). */
    private const val SHOT_BUDGET_S = 135L

    /** How long `screen live` waits for its first frame before reporting failure. */
    private const val FIRST_FRAME_WAIT_MS = 8_000L

    /** Max wait for the first (single-frame) frame. */
    private const val FRAME_WAIT_S = 10L

    /** How long stop() waits for a healthy worker to exit by itself. */
    private const val STOP_GRACE_MS = 2_000
}
