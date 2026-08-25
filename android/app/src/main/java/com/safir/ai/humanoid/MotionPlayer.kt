package com.safir.ai.humanoid

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Persistent visual surface for the Safir humanoid.
 *
 * The player instance survives state changes so the avatar surface is never
 * replaced by another screen. Motion selection only swaps MediaItems.
 */
@Composable
fun MotionPlayer(
    mediaUri: String?,
    loop: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    LaunchedEffect(mediaUri, loop) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF

        if (!mediaUri.isNullOrBlank()) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(mediaUri)))
            player.prepare()
            player.play()
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
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
        update = { view -> view.player = player }
    )
}
