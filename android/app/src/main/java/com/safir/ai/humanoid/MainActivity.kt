package com.safir.ai.humanoid

import android.Manifest
import android.os.Bundle
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

            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF102448))
                ) {
                    // This is intentionally kept mounted for the lifetime of the screen.
                    // Once the durable HeyGen MP4 URLs/files are resolved, state changes only
                    // replace the media item; the avatar surface itself never disappears.
                    MotionPlayer(
                        mediaUri = null,
                        loop = state == AvatarState.IDLE || state == AvatarState.LISTENING,
                        modifier = Modifier.fillMaxSize()
                    )

                    Text(
                        text = state.name,
                        color = Color(0xFFB9D8FF),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp)
                    )

                    FloatingActionButton(
                        onClick = {
                            requestMic.launch(Manifest.permission.RECORD_AUDIO)
                            state = if (state == AvatarState.LISTENING) AvatarState.IDLE else AvatarState.LISTENING
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
