package com.tg.ghostd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.neovisionaries.ws.client.WebSocket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
 */
object ScreenCapturer {

    @Volatile private var stopRequested = false

    // ------------------------------------------------------------------ shot

    fun shot(ctx: Context, ws: WebSocket?): JSONObject {
        MediaBus.begin(MediaBus.SOURCE_SCREEN, ctx, ws)
        try {
            val projection = ProjectionHub.ensureProjection(ctx)
            val (w, h, dpi) = displaySize(ctx)
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val frames = LinkedBlockingQueue<Image>()
            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.let { frames.offer(it) }
                } catch (_: Exception) {
                }
            }, null)
            var vd: VirtualDisplay? = null
            try {
                vd = projection.createVirtualDisplay(
                    "ghostd-shot", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )
                val image = frames.poll(15, TimeUnit.SECONDS)
                    ?: throw Exception("screen capture timed out (no frame)")
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
                vd?.release()
                reader.close()
            }
        } finally {
            MediaBus.end(MediaBus.SOURCE_SCREEN)
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
        val projection = ProjectionHub.ensureProjection(ctx)
        stopRequested = false
        val t = Thread {
            try {
                runStream(ctx, projection, secs, fps, quality, streamName)
            } catch (_: Exception) {
                // stream died (socket gone or capture error); cleaned up below
            } finally {
                MediaBus.end(MediaBus.SOURCE_SCREEN)
            }
        }.apply { name = "ghostd-screen"; start() }
        return JSONObject()
            .put("stream", streamName)
            .put("started", true)
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
        val frames = LinkedBlockingQueue<Image>()
        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.let { frames.offer(it) }
            } catch (_: Exception) {
            }
        }, null)
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
            MediaBus.streamStart(
                streamName,
                JSONObject()
                    .put("width", w).put("height", h)
                    .put("fps", effFps).put("quality", effQ)
                    .put("encoding", "mjpeg")
            )
            val deadline = if (secs != null) System.currentTimeMillis() + secs * 1000L else Long.MAX_VALUE
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                val image = frames.poll(500, TimeUnit.MILLISECONDS) ?: continue
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
                } finally {
                    image.close()
                }
            }
            MediaBus.streamEnd(streamName, sent, if (stopRequested) "stopped" else "done")
        } finally {
            vd?.release()
            reader.close()
            bmp.recycle()
        }
    }

    fun stop(): JSONObject {
        stopRequested = true
        return JSONObject().put("stopping", MediaBus.SOURCE_SCREEN)
    }

    fun requestStopOnly() {
        stopRequested = true
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
}
