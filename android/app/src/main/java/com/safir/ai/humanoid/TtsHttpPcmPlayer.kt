package com.safir.ai.humanoid

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import org.json.JSONObject
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
    @Volatile private var activePlayer: MediaPlayer? = null

    fun speak(text: String) {
        cancel(notify = false)

        val turnId = turnCounter.incrementAndGet()
        activeTurnId = turnId
        onEvent(VoiceSyncEvent.TURN_STARTED)

        thread(name = "safir-tts-mp3-$turnId") {
            var conn: HttpURLConnection? = null
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
                    setRequestProperty("Accept", "audio/mpeg")
                }
                activeConnection = conn

                val body = JSONObject()
                    .put("text", text)
                    .put("model_id", "eleven_turbo_v2_5")
                    .put("output_format", "mp3_44100_128")
                    .toString()

                conn.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status !in 200..299) {
                    val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    throw IllegalStateException("TTS HTTP $status ${detail.orEmpty().take(300)}")
                }
                if (!isCurrent(turnId)) return@thread

                val bytes = conn.inputStream.use { it.readBytes() }
                if (!isCurrent(turnId)) return@thread
                if (bytes.isEmpty()) throw IllegalStateException("TTS returned empty audio")

                val dataSource = ByteArrayMediaDataSource(bytes)
                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(dataSource)
                    setOnPreparedListener { mp ->
                        if (!isCurrent(turnId)) {
                            runCatching { mp.release() }
                            return@setOnPreparedListener
                        }
                        activePlayer = mp
                        mp.start()
                        onEvent(VoiceSyncEvent.FIRST_AUDIO_FRAME)
                    }
                    setOnCompletionListener { mp ->
                        if (isCurrent(turnId)) onEvent(VoiceSyncEvent.AUDIO_COMPLETED)
                        runCatching { mp.release() }
                        if (activeTurnId == turnId) {
                            activePlayer = null
                            activeTurnId = 0L
                        }
                        runCatching { dataSource.close() }
                    }
                    setOnErrorListener { mp, what, extra ->
                        if (isCurrent(turnId)) onError("MediaPlayer error $what/$extra")
                        runCatching { mp.release() }
                        if (activeTurnId == turnId) {
                            activePlayer = null
                            activeTurnId = 0L
                        }
                        runCatching { dataSource.close() }
                        true
                    }
                }

                if (!isCurrent(turnId)) {
                    runCatching { player.release() }
                    runCatching { dataSource.close() }
                    return@thread
                }

                activePlayer = player
                player.prepareAsync()
            } catch (t: Throwable) {
                if (isCurrent(turnId)) onError(t.message ?: t.javaClass.simpleName)
            } finally {
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
        val player = activePlayer
        activeConnection = null
        activePlayer = null

        runCatching { conn?.disconnect() }
        runCatching { player?.stop() }
        runCatching { player?.reset() }
        runCatching { player?.release() }

        if (notify && hadActiveTurn) onEvent(VoiceSyncEvent.INTERRUPTED)
    }

    private fun isCurrent(turnId: Long): Boolean = activeTurnId == turnId

    private class ByteArrayMediaDataSource(
        private val data: ByteArray,
    ) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val available = data.size - position.toInt()
            val count = minOf(size, available)
            System.arraycopy(data, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() = Unit
    }
}
