package com.safir.ai.humanoid

import android.content.Context
import android.util.Base64
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LiveAvatarClient(
    context: Context,
    private val endpoint: String = "${BuildConfig.SUPABASE_URL}/functions/v1/humanoid-heygen",
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wsClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var room: Room? = null
    private var eventJob: Job? = null
    private var activeSessionToken: String? = null
    private var activeVideoTrack: VideoTrack? = null
    private var attachedRenderer: SurfaceViewRenderer? = null
    private var onVideoReady: (() -> Unit)? = null
    private var onDisconnected: (() -> Unit)? = null
    private var controlSocket: WebSocket? = null

    private val speechLock = Any()
    private var speechEventId: String? = null
    private var speechBuffer = ByteArrayOutputStream()
    private var firstSpeechChunk = true

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isControlConnected: Boolean = false
        private set

    fun connect(
        avatarId: String,
        onReady: (() -> Unit)? = null,
        onVideoReady: (() -> Unit)? = null,
        onDisconnected: (() -> Unit)? = null,
        onError: (String) -> Unit,
    ) {
        if (avatarId.isBlank()) {
            onError("LiveAvatar avatar id missing")
            return
        }
        if (isConnected) {
            onReady?.invoke()
            if (activeVideoTrack != null) onVideoReady?.invoke()
            return
        }

        this.onVideoReady = onVideoReady
        this.onDisconnected = onDisconnected

        requestLiteSession(
            avatarId = avatarId,
            onSuccess = { session ->
                scope.launch {
                    try {
                        disconnectInternal(stopRemote = false, notifyDisconnected = false)

                        val nextRoom = LiveKit.create(appContext)
                        room = nextRoom
                        activeSessionToken = session.sessionToken

                        eventJob = launch {
                            nextRoom.events.collect { event ->
                                when (event) {
                                    is RoomEvent.TrackSubscribed -> {
                                        val track = event.track
                                        if (track is VideoTrack) attachTrack(track)
                                    }
                                    is RoomEvent.Disconnected -> {
                                        isConnected = false
                                        this@LiveAvatarClient.onDisconnected?.invoke()
                                    }
                                    else -> Unit
                                }
                            }
                        }

                        nextRoom.connect(session.livekitUrl, session.livekitClientToken)

                        val existing = nextRoom.remoteParticipants.values
                            .asSequence()
                            .mapNotNull { participant ->
                                participant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
                            }
                            .firstOrNull()
                        if (existing != null) attachTrack(existing)

                        openControlSocket(session.wsUrl, onError)
                        isConnected = true
                        onReady?.invoke()
                    } catch (t: Throwable) {
                        isConnected = false
                        this@LiveAvatarClient.onDisconnected?.invoke()
                        onError("LiveAvatar connect failed: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
            },
            onError = {
                this.onDisconnected?.invoke()
                onError(it)
            },
        )
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        scope.launch {
            if (attachedRenderer === renderer) return@launch
            activeVideoTrack?.let { oldTrack ->
                attachedRenderer?.let { oldRenderer -> runCatching { oldTrack.removeRenderer(oldRenderer) } }
            }
            attachedRenderer = renderer
            room?.initVideoRenderer(renderer)
            activeVideoTrack?.let { track -> runCatching { track.addRenderer(renderer) } }
        }
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        scope.launch {
            activeVideoTrack?.let { track -> runCatching { track.removeRenderer(renderer) } }
            if (attachedRenderer === renderer) attachedRenderer = null
        }
    }

    fun beginSpeech() {
        synchronized(speechLock) {
            speechEventId = "safir-${UUID.randomUUID()}"
            speechBuffer.reset()
            firstSpeechChunk = true
        }
    }

    fun pushSpeechPcm(pcm24kMono16: ByteArray) {
        if (pcm24kMono16.isEmpty()) return
        synchronized(speechLock) {
            if (speechEventId == null) beginSpeech()
            speechBuffer.write(pcm24kMono16)
            flushSpeechBuffer(force = false)
        }
    }

    fun endSpeech() {
        synchronized(speechLock) {
            val eventId = speechEventId ?: return
            flushSpeechBuffer(force = true)
            controlSocket?.send(
                JSONObject()
                    .put("type", "agent.speak_end")
                    .put("event_id", eventId)
                    .toString()
            )
            speechEventId = null
            speechBuffer.reset()
            firstSpeechChunk = true
        }
    }

    fun interruptSpeech() {
        synchronized(speechLock) {
            speechEventId = null
            speechBuffer.reset()
            firstSpeechChunk = true
        }
        controlSocket?.send(
            JSONObject()
                .put("type", "agent.interrupt")
                .put("event_id", "interrupt-${UUID.randomUUID()}")
                .toString()
        )
    }

    fun disconnect() {
        scope.launch { disconnectInternal(stopRemote = true, notifyDisconnected = true) }
    }

    fun release() {
        scope.launch {
            disconnectInternal(stopRemote = true, notifyDisconnected = false)
            onVideoReady = null
            onDisconnected = null
            wsClient.dispatcher.executorService.shutdown()
            scope.cancel()
        }
    }

    private fun flushSpeechBuffer(force: Boolean) {
        val eventId = speechEventId ?: return
        if (!isControlConnected) return

        var bytes = speechBuffer.toByteArray()
        var target = if (firstSpeechChunk) FIRST_CHUNK_BYTES else NEXT_CHUNK_BYTES

        while (bytes.size >= target || (force && bytes.isNotEmpty())) {
            val count = if (bytes.size >= target) target else bytes.size
            val chunk = bytes.copyOfRange(0, count)
            bytes = bytes.copyOfRange(count, bytes.size)

            val audio = Base64.encodeToString(chunk, Base64.NO_WRAP)
            controlSocket?.send(
                JSONObject()
                    .put("type", "agent.speak")
                    .put("event_id", eventId)
                    .put("audio", audio)
                    .toString()
            )

            firstSpeechChunk = false
            target = NEXT_CHUNK_BYTES
            if (!force && bytes.size < target) break
        }

        speechBuffer.reset()
        if (bytes.isNotEmpty()) speechBuffer.write(bytes)
    }

    private fun openControlSocket(wsUrl: String, onError: (String) -> Unit) {
        if (wsUrl.isBlank()) {
            onError("LiveAvatar control WebSocket missing")
            return
        }

        controlSocket?.close(1000, "replace")
        isControlConnected = false

        val request = Request.Builder().url(wsUrl).build()
        controlSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // The socket is open, but LiveAvatar only accepts speech after its
                // session.state_updated event reports connected.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "session.state_updated" -> {
                        val state = json.optString("state").ifBlank {
                            json.optJSONObject("data")?.optString("state").orEmpty()
                        }
                        if (state.equals("connected", ignoreCase = true)) {
                            isControlConnected = true
                        }
                    }
                    "agent.speak_ended" -> Unit
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isControlConnected = false
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isControlConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isControlConnected = false
                onError("LiveAvatar control failed: ${t.message ?: t.javaClass.simpleName}")
            }
        })
    }

    private fun attachTrack(track: VideoTrack) {
        val previous = activeVideoTrack
        val renderer = attachedRenderer
        if (previous !== track && renderer != null) runCatching { previous?.removeRenderer(renderer) }
        activeVideoTrack = track
        if (renderer != null) runCatching { track.addRenderer(renderer) }
        onVideoReady?.invoke()
    }

    private suspend fun disconnectInternal(stopRemote: Boolean, notifyDisconnected: Boolean) {
        val sessionToken = activeSessionToken
        activeSessionToken = null
        isConnected = false
        isControlConnected = false

        synchronized(speechLock) {
            speechEventId = null
            speechBuffer.reset()
            firstSpeechChunk = true
        }

        controlSocket?.close(1000, "disconnect")
        controlSocket = null

        val currentTrack = activeVideoTrack
        val renderer = attachedRenderer
        if (currentTrack != null && renderer != null) runCatching { currentTrack.removeRenderer(renderer) }
        activeVideoTrack = null

        eventJob?.cancel()
        eventJob = null

        val currentRoom = room
        room = null
        if (currentRoom != null) runCatching { currentRoom.disconnect() }

        if (stopRemote && !sessionToken.isNullOrBlank()) stopSessionAsync(sessionToken)
        if (notifyDisconnected) onDisconnected?.invoke()
    }

    private fun requestLiteSession(
        avatarId: String,
        onSuccess: (LiveAvatarSession) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "safir-liveavatar-session") {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection()
                val body = JSONObject()
                    .put("action", "create_lite_session")
                    .put("avatar_id", avatarId)
                    .put("quality", "high")
                    .put("is_sandbox", false)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                val raw = if (status in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
                if (status !in 200..299) throw IllegalStateException("HTTP $status ${raw.take(300)}")

                val json = JSONObject(raw)
                onSuccess(
                    LiveAvatarSession(
                        sessionToken = json.getString("session_token"),
                        livekitUrl = json.getString("livekit_url"),
                        livekitClientToken = json.getString("livekit_client_token"),
                        wsUrl = json.optString("ws_url"),
                    )
                )
            } catch (t: Throwable) {
                onError("LiveAvatar session failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun stopSessionAsync(sessionToken: String) {
        thread(name = "safir-liveavatar-stop") {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection()
                val body = JSONObject()
                    .put("action", "stop_session")
                    .put("session_token", sessionToken)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                runCatching { conn.inputStream.close() }
            } catch (_: Throwable) {
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    private fun openConnection(): HttpURLConnection {
        return (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
    }

    companion object {
        // LiveAvatar LITE expects raw PCM: signed 16-bit little-endian, mono, 24 kHz.
        private const val BYTES_PER_SECOND = 48_000
        private const val FIRST_CHUNK_BYTES = 28_800 // 600 ms
        private const val NEXT_CHUNK_BYTES = BYTES_PER_SECOND // 1 second
    }
}

data class LiveAvatarSession(
    val sessionToken: String,
    val livekitUrl: String,
    val livekitClientToken: String,
    val wsUrl: String,
)
