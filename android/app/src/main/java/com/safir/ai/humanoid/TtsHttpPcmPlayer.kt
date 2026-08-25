package com.safir.ai.humanoid

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TtsHttpPcmPlayer(
    private val onEvent: (VoiceSyncEvent) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var audioTrack: AudioTrack? = null

    fun speak(text: String) {
        cancel(notify = false)
        cancelled.set(false)
        onEvent(VoiceSyncEvent.TURN_STARTED)

        thread(name = "safir-tts-pcm") {
            var track: AudioTrack? = null
            try {
                val endpoint = URL("${BuildConfig.SUPABASE_URL}/functions/v1/humanoid-tts")
                val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                connection = conn

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

                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(SAMPLE_RATE / 2)

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
                    .build()
                audioTrack = track
                track.play()

                var firstAudibleChunk = true
                var totalBytesWritten = 0L
                val buffer = ByteArray(4096)

                BufferedInputStream(conn.inputStream).use { input ->
                    while (!cancelled.get()) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        var offset = 0
                        while (offset < read && !cancelled.get()) {
                            val written = track.write(buffer, offset, read - offset, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
                            if (written > 0) {
                                offset += written
                                totalBytesWritten += written
                                if (firstAudibleChunk) {
                                    firstAudibleChunk = false
                                    onEvent(VoiceSyncEvent.FIRST_AUDIO_FRAME)
                                }
                            }
                        }
                    }
                }

                if (cancelled.get()) return@thread

                val framesWritten = totalBytesWritten / BYTES_PER_FRAME
                while (!cancelled.get() && playbackFrames(track) < framesWritten) {
                    Thread.sleep(10)
                }
                if (!cancelled.get()) onEvent(VoiceSyncEvent.AUDIO_COMPLETED)
            } catch (t: Throwable) {
                if (!cancelled.get()) onError(t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { track?.pause() }
                runCatching { track?.flush() }
                runCatching { track?.release() }
                audioTrack = null
                runCatching { connection?.disconnect() }
                connection = null
            }
        }
    }

    fun interrupt() = cancel(notify = true)

    fun release() = cancel(notify = false)

    private fun cancel(notify: Boolean) {
        val wasActive = connection != null || audioTrack != null
        cancelled.set(true)
        runCatching { connection?.disconnect() }
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        if (notify && wasActive) onEvent(VoiceSyncEvent.INTERRUPTED)
    }

    @Suppress("DEPRECATION")
    private fun playbackFrames(track: AudioTrack): Long =
        track.playbackHeadPosition.toLong() and 0xffffffffL

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2L
    }
}
