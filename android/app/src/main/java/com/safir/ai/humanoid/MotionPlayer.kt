package com.safir.ai.humanoid

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Persistent visual surface for the Safir humanoid.
 *
 * The player instance survives state changes so the avatar surface is never
 * replaced by another screen. Motion selection only swaps MediaItems.
 *
 * HeyGen's ambient/background audio is intentionally kept at a low level.
 * Safir's spoken voice still comes from ElevenLabs on top of it.
 */
@Composable
fun MotionPlayer(
    mediaUri: String?,
    loop: Boolean,
    modifier: Modifier = Modifier,
    backgroundVolume: Float = 0.14f,
    onPlaybackStarted: () -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onPlaybackError: (PlaybackException) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentOnPlaybackStarted = rememberUpdatedState(onPlaybackStarted)
    val currentOnPlaybackEnded = rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError = rememberUpdatedState(onPlaybackError)

    val safeBackgroundVolume = backgroundVolume.coerceIn(0f, 1f)

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            volume = safeBackgroundVolume
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) currentOnPlaybackStarted.value.invoke()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentOnPlaybackEnded.value.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnPlaybackError.value.invoke(error)
            }
        }

        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(mediaUri, loop, safeBackgroundVolume) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.volume = safeBackgroundVolume

        if (!mediaUri.isNullOrBlank()) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(mediaUri)))
            player.prepare()
            player.play()
        } else {
            player.clearMediaItems()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
                keepScreenOn = true
            }
        },
        update = { view ->
            player.volume = safeBackgroundVolume
            view.player = player
        }
    )
}
