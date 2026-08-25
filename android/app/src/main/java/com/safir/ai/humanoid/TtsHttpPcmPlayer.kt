package com.safir.ai.humanoid

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class TtsHttpPcmPlayer(
    private val onEvent: (VoiceSyncEvent) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val turnCounter = AtomicLong(0L)

    @Volatile private var activeTurnId = 0L
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activeTrack: AudioTrack? = null

    fun speak(text: String) {
        cancel(notify = false)

        val turnId = turnCounter.incrementAndGet()
        activeTurnId = turnId
        onEvent(VoiceSyncEvent.TURN_STARTED)

        thread(name = "safir-tts-pcm-$turnId") {
            var conn: HttpURLConnection? = null
            var track: AudioTrack? = null

            try {
                val endpoint = URL("${BuildConfig.SUPABASE_URL}/functions/v1/humanoid-tts")
                conn = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                activeConnection = conn

                val body = JSONObject()
                    .put("text", text)
                    .put("model_id", "eleven_flash_v2_5")
                    .put("output_format", "pcm_24000")
                    .toString()

                conn.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status !in 200..299) {
                    val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    throw IllegalStateException("TTS HTTP $status ${detail.orEmpty().take(300)}")
                }
                if (!isCurrent(turnId)) return@thread

                val declaredFormat = conn.getHeaderField("x-safir-output-format")
                if (!declaredFormat.isNullOrBlank() && declaredFormat != "pcm_24000") {
                    throw IllegalStateException("TTS format mismatch: $declaredFormat")
                }

                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(SAMPLE_RATE)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBuffer)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()

                activeTrack = track
                track.play()

                var totalBytesWritten = 0L
                var firstFrameSent = false
                val buffer = ByteArray(4096)

                BufferedInputStream(conn.inputStream).use { input ->
                    while (isCurrent(turnId)) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        var offset = 0
                        while (offset < read && isCurrent(turnId)) {
                            val written = track.write(buffer, offset, read - offset, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
                            if (written == 0) continue

                            offset += written
                            totalBytesWritten += written

                            if (!firstFrameSent) {
                                firstFrameSent = true
                                onEvent(VoiceSyncEvent.FIRST_AUDIO_FRAME)
                            }
                        }
                    }
                }

                if (!isCurrent(turnId)) return@thread
                if (totalBytesWritten == 0L) {
                    throw IllegalStateException("TTS returned empty audio")
                }

                val framesWritten = totalBytesWritten / BYTES_PER_FRAME
                while (isCurrent(turnId) && playbackFrames(track) < framesWritten) {
                    Thread.sleep(10)
                }

                if (isCurrent(turnId)) onEvent(VoiceSyncEvent.AUDIO_COMPLETED)
            } catch (t: Throwable) {
                if (isCurrent(turnId)) onError(t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { track?.pause() }
                runCatching { track?.flush() }
                runCatching { track?.release() }
                runCatching { conn?.disconnect() }

                if (activeTurnId == turnId) {
                    activeTrack = null
                    activeConnection = null
                }
            }
        }
    }

    fun interrupt() = cancel(notify = true)

    fun release() = cancel(notify = false)

    private fun cancel(notify: Boolean) {
        val hadActiveTurn = activeTurnId != 0L
        activeTurnId = 0L
        turnCounter.incrementAndGet()

        val conn = activeConnection
        val track = activeTrack
        activeConnection = null
        activeTrack = null

        runCatching { conn?.disconnect() }
        runCatching { track?.pause() }
        runCatching { track?.flush() }

        if (notify && hadActiveTurn) onEvent(VoiceSyncEvent.INTERRUPTED)
    }

    private fun isCurrent(turnId: Long): Boolean = activeTurnId == turnId

    @Suppress("DEPRECATION")
    private fun playbackFrames(track: AudioTrack): Long =
        track.playbackHeadPosition.toLong() and 0xffffffffL

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2L
    }
}
