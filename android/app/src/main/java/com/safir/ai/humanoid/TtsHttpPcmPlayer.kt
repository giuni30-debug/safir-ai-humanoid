package com.safir.ai.humanoid

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class TtsHttpPcmPlayer(
    private val context: Context,
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
    @Volatile private var activePlayer: MediaPlayer? = null
    @Volatile private var activeFile: File? = null

    fun speak(text: String) {
        cancel(notify = false)

        val turnId = turnCounter.incrementAndGet()
        activeTurnId = turnId
        onEvent(VoiceSyncEvent.TURN_STARTED)

        thread(name = "safir-tts-mp3-$turnId") {
            var conn: HttpURLConnection? = null
            var tempFile: File? = null
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
                    setRequestProperty("Accept", "audio/mpeg")
                }
                activeConnection = conn

                val body = JSONObject()
                    .put("text", text)
                    .put("model_id", "eleven_flash_v2_5")
                    .put("output_format", "mp3_44100_128")
                    .toString()

                conn.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status !in 200..299) {
                    val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    throw IllegalStateException("TTS HTTP $status ${detail.orEmpty().take(300)}")
                }
                if (!isCurrent(turnId)) return@thread

                tempFile = File.createTempFile("safir_voice_", ".mp3", context.cacheDir)
                activeFile = tempFile

                conn.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (!isCurrent(turnId)) return@thread
                if (tempFile.length() <= 0L) throw IllegalStateException("TTS returned empty MP3")

                onPcmStart()

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(tempFile.absolutePath)
                    setVolume(1.0f, 1.0f)
                    setOnPreparedListener { prepared ->
                        if (!isCurrent(turnId)) {
                            runCatching { prepared.release() }
                            return@setOnPreparedListener
                        }
                        prepared.start()
                        onEvent(VoiceSyncEvent.FIRST_AUDIO_FRAME)
                    }
                    setOnCompletionListener { completed ->
                        if (isCurrent(turnId)) {
                            onPcmEnd()
                            onEvent(VoiceSyncEvent.AUDIO_COMPLETED)
                            activeTurnId = 0L
                        }
                        runCatching { completed.release() }
                        if (activePlayer === completed) activePlayer = null
                        activeFile?.let { runCatching { it.delete() } }
                        activeFile = null
                    }
                    setOnErrorListener { failed, what, extra ->
                        if (isCurrent(turnId)) {
                            activeTurnId = 0L
                            onPcmInterrupt()
                            onError("MediaPlayer error $what/$extra")
                        }
                        runCatching { failed.release() }
                        if (activePlayer === failed) activePlayer = null
                        activeFile?.let { runCatching { it.delete() } }
                        activeFile = null
                        true
                    }
                }

                activePlayer = player
                player.prepareAsync()
            } catch (t: Throwable) {
                if (isCurrent(turnId)) {
                    activeTurnId = 0L
                    onPcmInterrupt()
                    onError(t.message ?: t.javaClass.simpleName)
                }
                tempFile?.let { runCatching { it.delete() } }
                if (activeFile === tempFile) activeFile = null
            } finally {
                runCatching { conn?.disconnect() }
                if (activeConnection === conn) activeConnection = null
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
        val file = activeFile
        activeConnection = null
        activePlayer = null
        activeFile = null

        runCatching { conn?.disconnect() }
        runCatching { player?.stop() }
        runCatching { player?.reset() }
        runCatching { player?.release() }
        runCatching { file?.delete() }

        if (hadActiveTurn) onPcmInterrupt()
        if (notify && hadActiveTurn) onEvent(VoiceSyncEvent.INTERRUPTED)
    }

    private fun isCurrent(turnId: Long): Boolean = activeTurnId == turnId
}
