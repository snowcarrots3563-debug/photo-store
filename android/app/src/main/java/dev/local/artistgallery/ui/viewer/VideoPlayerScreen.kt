package dev.local.artistgallery.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.local.artistgallery.data.preview.AssetReader
import dev.local.artistgallery.ui.DButton
import dev.local.artistgallery.ui.DColors
import dev.local.artistgallery.ui.DIcon
import dev.local.artistgallery.ui.DIconButton
import dev.local.artistgallery.ui.GalleryEvent
import dev.local.artistgallery.ui.GalleryUiState
import kotlinx.coroutines.delay

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
fun VideoPlayerScreen(state: GalleryUiState, assetId: String, assetReader: AssetReader?, onEvent: (GalleryEvent) -> Unit) {
    val item = state.mediaItems.firstOrNull { it.id == assetId }
    val context = LocalContext.current
    val uri = remember(item?.storageKey, assetReader) { runCatching { if (item == null) null else assetReader?.contentUri(item.storageKey) }.getOrNull() }
    val player = remember(uri) {
        uri?.let {
            runCatching {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(it))
                    playWhenReady = false
                }
            }.getOrNull()
        }
    }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var isReady by remember(player) { mutableStateOf(false) }
    var errorMessage by remember(player) { mutableStateOf<String?>(null) }
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var sliderMs by remember(player) { mutableFloatStateOf(0f) }
    var draggingSlider by remember(player) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isReady = playbackState == Player.STATE_READY
                if (playbackState == Player.STATE_ENDED) isPlaying = false
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorMessage = "无法播放此视频：${error.errorCodeName}"
                isReady = false
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        player?.addListener(listener)
        player?.prepare()
        player?.pause()
        isReady = player?.playbackState == Player.STATE_READY
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player?.removeListener(listener)
            player?.pause()
            player?.release()
        }
    }
    DisposableEffect(view, isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(player) {
        while (player != null) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L } ?: 0L
            if (!draggingSlider) sliderMs = positionMs.toFloat()
            delay(250)
        }
    }

    Column(Modifier.fillMaxSize().background(DColors.Background).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            DIconButton(DIcon.Back, "返回") { onEvent(GalleryEvent.CloseOverlay) }
            Text(item?.name.orEmpty(), Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1)
            Spacer(Modifier.size(48.dp))
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (player != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { androidContext -> PlayerView(androidContext).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    } },
                    update = { it.player = player },
                    onRelease = { it.player = null },
                )
                DIconButton(
                    if (isPlaying) DIcon.Pause else DIcon.Play,
                    if (isPlaying) "暂停视频" else "播放视频",
                    enabled = isReady,
                ) { if (isPlaying) player.pause() else player.play() }
            } else {
                Text(errorMessage ?: "视频文件暂时无法读取", color = Color.White, fontSize = 14.sp)
            }
        }
        if (player != null) {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp).clip(RoundedCornerShape(22.dp)).background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(if (draggingSlider) sliderMs.toLong() else positionMs), color = DColors.Muted, fontSize = 12.sp)
                    Text(formatTime(durationMs), color = DColors.Muted, fontSize = 12.sp)
                }
                Slider(
                    value = sliderMs.coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                    onValueChange = { draggingSlider = true; sliderMs = it },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    enabled = isReady && durationMs > 0,
                    onValueChangeFinished = { player.seekTo(sliderMs.toLong()); draggingSlider = false },
                )
                if (errorMessage != null) Text(errorMessage!!, color = DColors.Error, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        } else {
            DButton("返回作品集", primary = true, onClick = { onEvent(GalleryEvent.CloseOverlay) })
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
