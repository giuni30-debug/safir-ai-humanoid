package com.safir.ai.humanoid

import android.content.Context
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class LiveAvatarClient(
    context: Context,
    private val endpoint: String = "${BuildConfig.SUPABASE_URL}/functions/v1/humanoid-heygen",
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var room: Room? = null
    private var eventJob: Job? = null
    private var activeSessionToken: String? = null
    private var activeVideoTrack: VideoTrack? = null
    private var attachedRenderer: SurfaceViewRenderer? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    fun connect(
        avatarId: String,
        onReady: (() -> Unit)? = null,
        onError: (String) -> Unit,
    ) {
        if (avatarId.isBlank()) {
            onError("LiveAvatar avatar id missing")
            return
        }
        if (isConnected) {
            onReady?.invoke()
            return
        }

        requestLiteSession(
            avatarId = avatarId,
            onSuccess = { session ->
                scope.launch {
                    try {
                        disconnectInternal(stopRemote = false)

                        val nextRoom = LiveKit.create(appContext)
                        room = nextRoom
                        activeSessionToken = session.sessionToken

                        eventJob = launch {
                            nextRoom.events.collect { event ->
                                when (event) {
                                    is RoomEvent.TrackSubscribed -> {
                                        val track = event.track
                                        if (track is VideoTrack && event.participant.identity == "heygen") {
                                            attachTrack(track)
                                        }
                                    }
                                    is RoomEvent.Disconnected -> {
                                        isConnected = false
                                    }
                                    else -> Unit
                                }
                            }
                        }

                        nextRoom.connect(session.livekitUrl, session.livekitClientToken)

                        val existing = nextRoom.remoteParticipants.values
                            .firstOrNull { it.identity == "heygen" }
                            ?.getTrackPublication(Track.Source.CAMERA)
                            ?.track as? VideoTrack
                        if (existing != null) attachTrack(existing)

                        isConnected = true
                        onReady?.invoke()
                    } catch (t: Throwable) {
                        isConnected = false
                        onError("LiveAvatar connect failed: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
            },
            onError = onError,
        )
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        scope.launch {
            if (attachedRenderer === renderer) return@launch

            activeVideoTrack?.let { oldTrack ->
                attachedRenderer?.let { oldRenderer ->
                    runCatching { oldTrack.removeRenderer(oldRenderer) }
                }
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

    fun disconnect() {
        scope.launch { disconnectInternal(stopRemote = true) }
    }

    fun release() {
        scope.launch {
            disconnectInternal(stopRemote = true)
            scope.cancel()
        }
    }

    private fun attachTrack(track: VideoTrack) {
        val previous = activeVideoTrack
        val renderer = attachedRenderer

        if (previous !== track && renderer != null) {
            runCatching { previous?.removeRenderer(renderer) }
        }

        activeVideoTrack = track
        if (renderer != null) runCatching { track.addRenderer(renderer) }
    }

    private suspend fun disconnectInternal(stopRemote: Boolean) {
        val sessionToken = activeSessionToken
        activeSessionToken = null
        isConnected = false

        val currentTrack = activeVideoTrack
        val renderer = attachedRenderer
        if (currentTrack != null && renderer != null) {
            runCatching { currentTrack.removeRenderer(renderer) }
        }
        activeVideoTrack = null

        eventJob?.cancel()
        eventJob = null

        val currentRoom = room
        room = null
        if (currentRoom != null) runCatching { currentRoom.disconnect() }

        if (stopRemote && !sessionToken.isNullOrBlank()) {
            stopSessionAsync(sessionToken)
        }
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
                if (status !in 200..299) {
                    throw IllegalStateException("HTTP $status ${raw.take(300)}")
                }

                val json = JSONObject(raw)
                val session = LiveAvatarSession(
                    sessionToken = json.getString("session_token"),
                    livekitUrl = json.getString("livekit_url"),
                    livekitClientToken = json.getString("livekit_client_token"),
                    wsUrl = json.optString("ws_url"),
                )
                onSuccess(session)
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
                // Best-effort cleanup only. Never block app shutdown on provider cleanup.
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
}

data class LiveAvatarSession(
    val sessionToken: String,
    val livekitUrl: String,
    val livekitClientToken: String,
    val wsUrl: String,
)
