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

            fun applyVoiceEvent(
                event: VoiceSyncEvent,
                behavior: SpeechBehavior = SpeechBehavior(),
            ) {
                val decision = VoiceSyncEngine.decide(event, behavior)
                state = decision.state
                motion = decision.motion
            }

            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF102448))
                ) {
                    // Persistent avatar surface. The motion player owns body-video playback only.
                    // FIRST_AUDIO_FRAME and AUDIO_COMPLETED must come from the TTS/audio player,
                    // not from body-motion video callbacks.
                    MotionPlayer(
                        mediaUri = null,
                        loop = motion.loopable,
                        modifier = Modifier.fillMaxSize(),
                        onPlaybackEnded = {
                            // Body-motion completion is only authoritative for the explicit
                            // speak-to-idle bridge. Speech completion itself is audio-authoritative.
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
                    }

                    FloatingActionButton(
                        onClick = {
                            requestMic.launch(Manifest.permission.RECORD_AUDIO)

                            if (state == AvatarState.SPEAKING || state == AvatarState.THINKING) {
                                // Barge-in path: the future audio/TTS layer must cancel audio first,
                                // then emit INTERRUPTED here.
                                applyVoiceEvent(VoiceSyncEvent.INTERRUPTED)
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
