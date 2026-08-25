package com.safir.ai.humanoid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var onMicPermissionGranted: (() -> Unit)? = null

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onMicPermissionGranted?.invoke()
        onMicPermissionGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var state by remember { mutableStateOf(AvatarState.IDLE) }
            var motion by remember { mutableStateOf(MotionEngine.primaryFor(AvatarState.IDLE)) }
            var lastError by remember { mutableStateOf<String?>(null) }
            var transcript by remember { mutableStateOf("") }
            var recognitionActive by remember { mutableStateOf(false) }
            var suppressClientError by remember { mutableStateOf(false) }

            val mainHandler = remember { Handler(Looper.getMainLooper()) }

            fun applyVoiceEvent(
                event: VoiceSyncEvent,
                behavior: SpeechBehavior = SpeechBehavior(),
            ) {
                val decision = VoiceSyncEngine.decide(event, behavior)
                state = decision.state
                motion = decision.motion
            }

            val ttsPlayer = remember {
                TtsHttpPcmPlayer(
                    onEvent = { event -> runOnUiThread { applyVoiceEvent(event) } },
                    onError = { message ->
                        runOnUiThread {
                            lastError = message
                            state = AvatarState.IDLE
                            motion = MotionEngine.primaryFor(AvatarState.IDLE)
                        }
                    },
                )
            }

            val speechRecognizer = remember {
                if (SpeechRecognizer.isRecognitionAvailable(this)) SpeechRecognizer.createSpeechRecognizer(this) else null
            }

            DisposableEffect(speechRecognizer, ttsPlayer) {
                onDispose {
                    suppressClientError = true
                    recognitionActive = false
                    runCatching { speechRecognizer?.cancel() }
                    runCatching { speechRecognizer?.destroy() }
                    ttsPlayer.release()
                }
            }

            fun resetToIdle() {
                recognitionActive = false
                state = AvatarState.IDLE
                motion = MotionEngine.primaryFor(AvatarState.IDLE)
            }

            fun stopListeningToIdle() {
                if (recognitionActive) {
                    suppressClientError = true
                    recognitionActive = false
                    runCatching { speechRecognizer?.cancel() }
                }
                resetToIdle()
            }

            fun startListening() {
                if (speechRecognizer == null) {
                    lastError = "Speech recognition unavailable on this device"
                    return
                }
                if (recognitionActive) return

                lastError = null
                transcript = ""
                suppressClientError = false
                recognitionActive = true
                state = AvatarState.LISTENING
                motion = MotionEngine.primaryFor(AvatarState.LISTENING)

                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onError(error: Int) {
                        runOnUiThread {
                            val benignClientError = error == SpeechRecognizer.ERROR_CLIENT && suppressClientError
                            recognitionActive = false
                            suppressClientError = false

                            if (benignClientError) {
                                if (state == AvatarState.LISTENING) resetToIdle()
                                return@runOnUiThread
                            }

                            lastError = "STT error $error"
                            resetToIdle()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (text.isNotEmpty()) runOnUiThread { transcript = text }
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        runOnUiThread {
                            recognitionActive = false
                            suppressClientError = true
                            transcript = text

                            if (text.isBlank()) {
                                resetToIdle()
                            } else {
                                state = AvatarState.THINKING
                                motion = MotionEngine.primaryFor(AvatarState.THINKING)
                                mainHandler.postDelayed({
                                    suppressClientError = false
                                    ttsPlayer.speak(text)
                                }, 150L)
                            }
                        }
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                runCatching { speechRecognizer.startListening(intent) }
                    .onFailure {
                        recognitionActive = false
                        lastError = "STT start failed"
                        resetToIdle()
                    }
            }

            fun ensureMicThenListen() {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startListening()
                } else {
                    onMicPermissionGranted = { startListening() }
                    requestMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF102448))
                ) {
                    MotionPlayer(
                        mediaUri = null,
                        loop = motion.loopable,
                        modifier = Modifier.fillMaxSize(),
                        onPlaybackEnded = {
                            if (motion.id == MotionEngine.speakToIdle.id) {
                                resetToIdle()
                            }
                        }
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(state.name, color = Color(0xFFB9D8FF))
                        Text(motion.id, color = Color(0xFF7FDBFF))
                        if (transcript.isNotBlank()) Text(transcript, color = Color.White)
                        lastError?.let { Text(it, color = Color(0xFFFFB4AB)) }
                    }

                    FloatingActionButton(
                        onClick = {
                            lastError = null
                            when (state) {
                                AvatarState.SPEAKING, AvatarState.THINKING -> {
                                    ttsPlayer.interrupt()
                                    mainHandler.postDelayed({ ensureMicThenListen() }, 200L)
                                }
                                AvatarState.LISTENING -> stopListeningToIdle()
                                else -> ensureMicThenListen()
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(24.dp)
                    ) {
                        Text("MIC")
                    }
                }
            }
        }
    }
}

enum class AvatarState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    EMOTION
}
