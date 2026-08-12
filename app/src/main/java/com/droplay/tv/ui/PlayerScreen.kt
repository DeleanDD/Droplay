package com.droplay.tv.ui

import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.droplay.tv.data.MediaEntry
import kotlinx.coroutines.delay

private enum class TrackPanel { AUDIO, SUBTITLES }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    media: MediaEntry,
    resumeAt: Long,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onProgress: (Long, Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(media.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(media.url))
            prepare()
            if (resumeAt > 10_000) seekTo(resumeAt)
            playWhenReady = true
        }
    }
    var controls by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(resumeAt) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<TrackPanel?>(null) }
    var interaction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootFocus = remember { FocusRequester() }

    fun wake() { controls = true; interaction = System.currentTimeMillis() }
    fun seek(delta: Long) {
        if (duration > 0) player.seekTo((player.currentPosition + delta).coerceIn(0, duration))
        wake()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
        }
        player.addListener(listener)
        onDispose {
            onProgress(player.currentPosition, player.duration.coerceAtLeast(0))
            player.removeListener(listener); player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
            delay(500)
        }
    }
    LaunchedEffect(interaction, playing) {
        delay(5_000)
        if (playing && System.currentTimeMillis() - interaction >= 4_900) controls = false
    }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seek(-15_000); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(15_000); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (!controls) wake() else { if (player.isPlaying) player.pause() else player.play(); wake() }; true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { wake(); false }
                    KeyEvent.KEYCODE_BACK -> { onBack(); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000); true }
                    else -> false
                }
            }
    ) {
        AndroidView(factory = { PlayerView(it).apply { useController = false; this.player = player } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cyan)

        if (controls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xBB000000), Color.Transparent, Color(0xDD000000))))) {
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    ControlButton("←  Voltar", onBack)
                    Column(Modifier.padding(start = 20.dp).weight(1f)) { Text(media.name, fontSize = 24.sp); Text(media.group, color = Muted, fontSize = 13.sp) }
                    ControlButton(if (favorite) "♥ Favorito" else "♡ Favoritar", onFavorite)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (duration > 0) {
                        TvSeekBar(position, duration) { player.seekTo(it); wake() }
                        Row(Modifier.fillMaxWidth()) { Text(formatTime(position), fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text("-${formatTime((duration - position).coerceAtLeast(0))}", fontSize = 12.sp) }
                    } else Text("TRANSMISSÃO AO VIVO", color = Coral, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        ControlButton("−30s") { seek(-30_000) }
                        Spacer(Modifier.width(16.dp))
                        Button(onClick = { if (player.isPlaying) player.pause() else player.play(); wake() }, Modifier.size(70.dp), contentPadding = PaddingValues(0.dp)) { Text(if (playing) "Ⅱ" else "▶", fontSize = 24.sp) }
                        Spacer(Modifier.width(16.dp))
                        ControlButton("+30s") { seek(30_000) }
                        Spacer(Modifier.width(35.dp))
                        ControlButton("Áudio") { panel = TrackPanel.AUDIO; wake() }
                        Spacer(Modifier.width(10.dp))
                        ControlButton("Legendas") { panel = TrackPanel.SUBTITLES; wake() }
                    }
                    Text("←/→ avança 15s  •  mantenha o foco na barra para navegar rapidamente", Modifier.align(Alignment.CenterHorizontally), color = Muted, fontSize = 11.sp)
                }
            }
        }
    }

    panel?.let { TrackDialog(player, it, onDismiss = { panel = null; wake() }) }
}

@Composable private fun ControlButton(text: String, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(click, Modifier.onFocusChanged { focused = it.isFocused }.border(if (focused) 2.dp else 0.dp, Cyan, RoundedCornerShape(50)), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (focused) Color(0xFF25305B) else Color(0x9905081B))) { Text(text) }
}

@Composable private fun TvSeekBar(position: Long, duration: Long, seek: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fraction = (position.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(28.dp).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false else when (event.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { seek((position - 30_000).coerceAtLeast(0)); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { seek((position + 30_000).coerceAtMost(duration)); true }
            else -> false
        }
    }.focusable(), contentAlignment = Alignment.Center) {
        LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(if (focused) 10.dp else 6.dp), color = if (focused) Cyan else Violet, trackColor = Color(0xFF444B68))
    }
}

@OptIn(UnstableApi::class)
@Composable private fun TrackDialog(player: ExoPlayer, panel: TrackPanel, onDismiss: () -> Unit) {
    val type = if (panel == TrackPanel.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
    val choices = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).map { index -> Triple(group, index, group.getTrackFormat(index)) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (panel == TrackPanel.AUDIO) "Faixa de áudio" else "Legendas") }, text = {
        Column(Modifier.widthIn(min = 420.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (panel == TrackPanel.SUBTITLES) TextButton(onClick = { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build(); onDismiss() }) { Text("Desativadas") }
            if (choices.isEmpty()) Text("Nenhuma faixa disponível neste conteúdo.", color = Muted)
            choices.forEachIndexed { n, (group, index, format) ->
                val label = listOfNotNull(format.label, format.language?.uppercase(), format.channelCount.takeIf { it > 0 }?.let { "$it canais" }).distinct().joinToString(" · ").ifBlank { "Faixa ${n + 1}" }
                TextButton(onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build(); onDismiss()
                }, Modifier.fillMaxWidth()) { Text(label, Modifier.fillMaxWidth()) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } })
}

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
