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
            var recognitionActive by remember { mutableStateOf(false) }
            var suppressClientError by remember { mutableStateOf(false) }
            var pendingBehavior by remember { mutableStateOf(SpeechBehavior()) }

            val mainHandler = remember { Handler(Looper.getMainLooper()) }
            val aiClient = remember { AiReplyClient() }
            val memoryClient = remember { HumanoidMemoryClient() }
            val humanoidVideoUri = remember {
                "android.resource://$packageName/${R.raw.safir_humanoid_mars}"
            }

            fun applyVoiceEvent(
                event: VoiceSyncEvent,
                behavior: SpeechBehavior = pendingBehavior,
            ) {
                val decision = VoiceSyncEngine.decide(event, behavior)
                state = decision.state
                motion = decision.motion
            }

            val ttsPlayer = remember {
                TtsHttpPcmPlayer(
                    onEvent = { event ->
                        runOnUiThread { applyVoiceEvent(event) }
                    },
                    onError = {
                        runOnUiThread {
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
                if (speechRecognizer == null || recognitionActive) return

                recognitionActive = true
                suppressClientError = false
                state = AvatarState.LISTENING
                motion = MotionEngine.primaryFor(AvatarState.LISTENING)

                var latestPartial = ""
                var turnDispatched = false
                var fallbackRunnable: Runnable? = null
                var silenceRunnable: Runnable? = null

                fun clearTurnTimers() {
                    fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
                    silenceRunnable?.let { mainHandler.removeCallbacks(it) }
                    fallbackRunnable = null
                    silenceRunnable = null
                }

                fun dispatchRecognized(rawText: String) {
                    if (turnDispatched) return
                    val text = rawText.trim()
                    if (text.isBlank()) {
                        resetToIdle()
                        return
                    }

                    turnDispatched = true
                    clearTurnTimers()
                    recognitionActive = false
                    suppressClientError = true
                    runCatching { speechRecognizer.stopListening() }
                    state = AvatarState.THINKING
                    motion = MotionEngine.primaryFor(AvatarState.THINKING)

                    memoryClient.storeTurn("user", text)

                    fun requestAi(memoryContext: String) {
                        aiClient.request(
                            text = text,
                            memoryContext = memoryContext,
                            onSuccess = { result ->
                                memoryClient.storeTurn("assistant", result.reply)
                                result.memories.forEach { (kind, content) ->
                                    memoryClient.storeMemory(kind, content)
                                }
                                runOnUiThread {
                                    pendingBehavior = result.behavior
                                    suppressClientError = false
                                    ttsPlayer.speak(result.reply)
                                }
                            },
                            onError = {
                                runOnUiThread {
                                    suppressClientError = false
                                    resetToIdle()
                                }
                            },
                        )
                    }

                    memoryClient.fetchContext(
                        onSuccess = { context -> requestAi(context) },
                        onError = { requestAi("") },
                    )
                }

                fun armSilenceDetector() {
                    if (turnDispatched || latestPartial.isBlank()) return
                    silenceRunnable?.let { mainHandler.removeCallbacks(it) }
                    val candidate = latestPartial
                    val runnable = Runnable {
                        if (!turnDispatched && candidate == latestPartial && latestPartial.isNotBlank()) {
                            dispatchRecognized(latestPartial)
                        }
                    }
                    silenceRunnable = runnable
                    mainHandler.postDelayed(runnable, 850L)
                }

                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onEndOfSpeech() {
                        if (!turnDispatched && latestPartial.isNotBlank()) {
                            val candidate = latestPartial
                            val runnable = Runnable { dispatchRecognized(candidate) }
                            fallbackRunnable = runnable
                            mainHandler.postDelayed(runnable, 120L)
                        }
                    }

                    override fun onError(error: Int) {
                        runOnUiThread {
                            val benignClientError = error == SpeechRecognizer.ERROR_CLIENT && suppressClientError
                            val recoverableWithPartial =
                                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                                    latestPartial.isNotBlank()

                            recognitionActive = false

                            when {
                                turnDispatched -> Unit
                                benignClientError -> {
                                    suppressClientError = false
                                    if (state == AvatarState.LISTENING) resetToIdle()
                                }
                                recoverableWithPartial -> {
                                    suppressClientError = false
                                    dispatchRecognized(latestPartial)
                                }
                                else -> {
                                    suppressClientError = false
                                    clearTurnTimers()
                                    resetToIdle()
                                }
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (text.isNotEmpty()) {
                            latestPartial = text
                            runOnUiThread { armSilenceDetector() }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        runOnUiThread {
                            dispatchRecognized(if (text.isNotBlank()) text else latestPartial)
                        }
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
                }

                runCatching { speechRecognizer.startListening(intent) }
                    .onFailure {
                        recognitionActive = false
                        clearTurnTimers()
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
                        .background(Color.Black)
                ) {
                    MotionPlayer(
                        mediaUri = humanoidVideoUri,
                        loop = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    FloatingActionButton(
                        onClick = {
                            when (state) {
                                AvatarState.SPEAKING, AvatarState.THINKING -> {
                                    ttsPlayer.interrupt()
                                    mainHandler.postDelayed({ ensureMicThenListen() }, 80L)
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
