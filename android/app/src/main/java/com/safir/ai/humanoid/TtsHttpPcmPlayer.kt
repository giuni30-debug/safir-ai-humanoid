package com.safir.ai.humanoid

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class TtsHttpPcmPlayer(
    private val onEvent: (VoiceSyncEvent) -> Unit,
    private val onPcmStart: () -> Unit = {},
    private val onPcmChunk: (ByteArray) -> Unit = {},
    private val onPcmEnd: () -> Unit = {},
    private val onPcmInterrupt: () -> Unit = {},
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
        onPcmStart()

        thread(name = "safir-tts-pcm-$turnId") {
            var conn: HttpURLConnection? = null
            var track: AudioTrack? = null
            var firstAudio = true
            try {
                val endpoint = URL("${BuildConfig.SUPABASE_URL}/functions/v1/humanoid-tts")
                conn = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 45_000
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

                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(PCM_CHUNK_BYTES * 2)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                activeTrack = track
                track.setVolume(1.0f)
                track.play()

                conn.inputStream.use { input ->
                    val buffer = ByteArray(PCM_CHUNK_BYTES)
                    while (isCurrent(turnId)) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                        onPcmChunk(chunk)

                        if (firstAudio) {
                            firstAudio = false
                            onEvent(VoiceSyncEvent.FIRST_AUDIO_FRAME)
                        }

                        var offset = 0
                        while (offset < chunk.size && isCurrent(turnId)) {
                            val written = track.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
                            offset += written
                        }
                    }
                }

                if (isCurrent(turnId)) {
                    runCatching { track.stop() }
                    onPcmEnd()
                    onEvent(VoiceSyncEvent.AUDIO_COMPLETED)
                    activeTurnId = 0L
                }
            } catch (t: Throwable) {
                if (isCurrent(turnId)) {
                    onPcmInterrupt()
                    onError(t.message ?: t.javaClass.simpleName)
                }
            } finally {
                runCatching { track?.release() }
                if (activeTrack === track) activeTrack = null
                runCatching { conn?.disconnect() }
                if (activeTurnId == turnId) activeConnection = null
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
        runCatching { track?.stop() }
        runCatching { track?.release() }

        if (hadActiveTurn) onPcmInterrupt()
        if (notify && hadActiveTurn) onEvent(VoiceSyncEvent.INTERRUPTED)
    }

    private fun isCurrent(turnId: Long): Boolean = activeTurnId == turnId

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val PCM_CHUNK_BYTES = 8_192
    }
}
