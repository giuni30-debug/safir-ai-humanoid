package com.safir.ai.humanoid

import android.Manifest
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var state by remember { mutableStateOf(AvatarState.IDLE) }
            var motion by remember { mutableStateOf(MotionEngine.primaryFor(AvatarState.IDLE)) }
            var lastError by remember { mutableStateOf<String?>(null) }

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

            DisposableEffect(ttsPlayer) {
                onDispose { ttsPlayer.release() }
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
                                state = AvatarState.IDLE
                                motion = MotionEngine.primaryFor(AvatarState.IDLE)
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
                        lastError?.let { Text(it, color = Color(0xFFFFB4AB)) }
                    }

                    FloatingActionButton(
                        onClick = {
                            requestMic.launch(Manifest.permission.RECORD_AUDIO)
                            lastError = null

                            if (state == AvatarState.SPEAKING || state == AvatarState.THINKING) {
                                ttsPlayer.interrupt()
                            } else if (state == AvatarState.LISTENING) {
                                state = AvatarState.IDLE
                                motion = MotionEngine.primaryFor(AvatarState.IDLE)
                            } else {
                                state = AvatarState.LISTENING
                                motion = MotionEngine.primaryFor(AvatarState.LISTENING)
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
