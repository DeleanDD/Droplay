package com.droplay.tv.ui

import androidx.activity.compose.BackHandler
import android.view.KeyEvent
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import com.droplay.tv.data.MediaEntry
import com.droplay.tv.data.MediaKind
import com.droplay.tv.data.SubtitleTrack
import com.droplay.tv.R
import com.droplay.tv.subtitles.*
import kotlinx.coroutines.delay

private enum class TrackPanel { AUDIO, SUBTITLES }
private enum class ControlTarget { BACK, ACTIONS }

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
    val subtitleViewModel: SubtitleViewModel = viewModel(key = "subtitles-${media.id}")
    val subtitleState by subtitleViewModel.state.collectAsState()
    val subtitleSettings = remember { SubtitleSettings(context) }
    var subtitleAppearance by remember { mutableStateOf(subtitleSettings.appearance()) }
    var activeOpenSubtitle by remember(media.id) { mutableStateOf<SubtitleCandidate?>(null) }
    val player = remember(media.url) {
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(45_000)
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 DROPLAY/1.2.12")
            .setDefaultRequestProperties(mapOf("Accept" to "*/*", "Accept-Encoding" to "identity"))
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(context, renderers).setLoadControl(loadControl).setMediaSourceFactory(mediaSourceFactory).build().apply {
            setMediaItem(playerMediaItem(media)); prepare()
            if (resumeAt > 0) seekTo(resumeAt)
            setHandleAudioBecomingNoisy(true)
            playWhenReady = true
        }
    }
    var controls by remember { mutableStateOf(true) }
    var controlFocused by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(resumeAt) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<TrackPanel?>(null) }
    var interaction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var ghost by remember { mutableStateOf<String?>(null) }
    var activeExternalSubtitle by remember(media.url) { mutableStateOf<com.droplay.tv.data.SubtitleTrack?>(null) }
    var tracksRevision by remember { mutableIntStateOf(0) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var retriedFromStart by remember(media.url) { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val favoriteFocus = remember { FocusRequester() }
    var requestedFocus by remember { mutableStateOf<ControlTarget?>(null) }

    fun wake() { controls = true; interaction = System.currentTimeMillis() }
    fun seek(delta: Long) {
        if (duration > 0) player.seekTo((player.currentPosition + delta).coerceIn(0, duration))
        wake()
    }
    fun togglePlayback() {
        if (player.isPlaying) { player.pause(); ghost = "Ⅱ" } else { player.play(); ghost = "▶" }
        wake()
    }
    fun selectExternalSubtitle(track: com.droplay.tv.data.SubtitleTrack?) {
        val resumePosition = player.currentPosition.coerceAtLeast(0)
        val resumePlayback = player.playWhenReady
        activeExternalSubtitle = track
        playbackError = null
        player.setMediaItem(playerMediaItem(media, track), resumePosition)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
            .build()
        player.prepare()
        player.playWhenReady = resumePlayback
    }
    fun saveSubtitleAppearance(value: SubtitleAppearance) {
        val previousDelay = subtitleAppearance.delayMs
        subtitleAppearance = value
        subtitleSettings.saveAppearance(value)
        if (previousDelay != value.delayMs) activeOpenSubtitle?.let { subtitleViewModel.retime(it, value.delayMs) }
    }

    LaunchedEffect(subtitleState) {
        val ready = subtitleState as? SubtitleUiState.Ready ?: return@LaunchedEffect
        activeOpenSubtitle = ready.candidate
        selectExternalSubtitle(SubtitleTrack(
            url = ready.localUrl,
            label = "OpenSubtitles • ${SubtitleRanking.languageLabel(ready.candidate.language)}",
            language = SubtitleRanking.normalizeLanguage(ready.candidate.language),
            mimeType = if (ready.localUrl.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP,
        ))
        subtitleViewModel.consumeReady()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onTracksChanged(tracks: Tracks) { tracksRevision++ }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                buffering = false
                val status = httpStatusCode(error)
                if (activeExternalSubtitle != null) {
                    val resumePosition = player.currentPosition.coerceAtLeast(0)
                    activeExternalSubtitle = null
                    ghost = "CC indisponível"
                    player.setMediaItem(playerMediaItem(media), resumePosition)
                    player.prepare()
                    player.playWhenReady = true
                } else if (status == 416 && !retriedFromStart) {
                    retriedFromStart = true
                    player.setMediaItem(playerMediaItem(media), 0L)
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    playbackError = when (status) {
                        401, 403 -> "O servidor recusou o acesso ao vídeo (HTTP $status). Atualize a biblioteca e tente novamente."
                        404, 410 -> "O vídeo não está mais disponível nesse endereço (HTTP $status). Atualize a biblioteca."
                        429 -> "O servidor limitou temporariamente as reproduções (HTTP 429). Aguarde e tente novamente."
                        in 500..599 -> "O servidor de vídeo está indisponível no momento (HTTP $status)."
                        null -> "Não foi possível reproduzir este conteúdo (${error.errorCodeName})."
                        else -> "O servidor retornou HTTP $status e não entregou o vídeo."
                    }
                }
            }
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
        delay(4_500)
        if (playing && !controlFocused && System.currentTimeMillis() - interaction >= 4_400) controls = false
    }
    LaunchedEffect(ghost) { if (ghost != null) { delay(700); ghost = null } }
    LaunchedEffect(Unit) { rootFocus.requestFocus() }
    LaunchedEffect(controls) {
        if (!controls) {
            controlFocused = false
            requestedFocus = null
            rootFocus.requestFocus()
        }
    }
    BackHandler(enabled = controlFocused) {
        controlFocused = false
        requestedFocus = null
        rootFocus.requestFocus()
        wake()
    }
    LaunchedEffect(controls, requestedFocus) {
        if (controls) {
            when (requestedFocus) {
                ControlTarget.BACK -> backFocus.requestFocus()
                ControlTarget.ACTIONS -> playPauseFocus.requestFocus()
                null -> Unit
            }
            requestedFocus = null
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (controlFocused) false else { seek(-15_000); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (controlFocused) false else { seek(15_000); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                        if (controlFocused) false else { togglePlayback(); true }
                    KeyEvent.KEYCODE_BACK -> if (controlFocused) {
                        controlFocused = false
                        requestedFocus = null
                        rootFocus.requestFocus()
                        wake()
                        true
                    } else false
                    KeyEvent.KEYCODE_DPAD_UP -> { wake(); requestedFocus = ControlTarget.BACK; true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { wake(); requestedFocus = ControlTarget.ACTIONS; true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000); true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    this.player = player
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = {
                it.player = player
                it.isFocusable = false
                it.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                applySubtitleAppearance(it, subtitleAppearance)
            },
            modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        )
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cyan)

        AnimatedVisibility(ghost != null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.size(92.dp).background(Color(0xAA05081B), CircleShape), contentAlignment = Alignment.Center) {
                Text(ghost.orEmpty(), fontSize = 38.sp, color = Color.White)
            }
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xA8000000), Color.Transparent, Color(0xE8000000))))) {
                PlayerBackControl(backFocus, { controlFocused = it; if (it) wake() }, onBack)
                Column(Modifier.align(Alignment.TopStart).padding(start = 92.dp, top = 30.dp)) {
                    Text(media.name, fontSize = 23.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(media.group, color = Muted, fontSize = 12.sp)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 38.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (duration > 0) {
                        TvSeekBar(position, duration, { player.seekTo(it); wake() }, ::togglePlayback,
                            { controlFocused = it; if (it) wake() })
                        Row(Modifier.fillMaxWidth()) {
                            Text(formatTime(position), fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Text(formatTime(duration), fontSize = 11.sp)
                        }
                    } else if (media.kind == com.droplay.tv.data.MediaKind.LIVE) {
                        Text("●  AO VIVO", color = Coral, fontSize = 12.sp)
                    } else {
                        Text("Preparando reprodução…", color = Muted, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MiniControl(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                            if (playing) "Pausar" else "Reproduzir", { controlFocused = it; if (it) wake() },
                            playPauseFocus, ::togglePlayback)
                        Spacer(Modifier.width(10.dp))
                        Text(if (playing) "Reproduzindo" else "Pausado", color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        MiniControl(if (favorite) R.drawable.ic_player_favorite else R.drawable.ic_player_favorite_border, "Favorito", { controlFocused = it; if (it) wake() }, favoriteFocus, onFavorite)
                        Spacer(Modifier.width(8.dp))
                        MiniControl(R.drawable.ic_player_audio, "Áudio", { controlFocused = it; if (it) wake() }) { panel = TrackPanel.AUDIO; wake() }
                        Spacer(Modifier.width(8.dp))
                        MiniControl(R.drawable.ic_player_cc, "Legendas", { controlFocused = it; if (it) wake() }) {
                            panel = TrackPanel.SUBTITLES
                            if (media.kind != MediaKind.LIVE) subtitleViewModel.search(media)
                            wake()
                        }
                    }
                }
            }
        }
        playbackError?.let { message ->
            Surface(Modifier.align(Alignment.Center).widthIn(max = 520.dp), color = Color(0xEE10152E), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message, color = Color.White)
                    Button(onClick = {
                        playbackError = null
                        player.setMediaItem(playerMediaItem(media), player.currentPosition.coerceAtLeast(0))
                        player.prepare()
                        player.playWhenReady = true
                    }) { Text("Tentar novamente") }
                }
            }
        }
    }
    panel?.let {
        TrackDialog(
            player = player, media = media, panel = it, revision = tracksRevision,
            activeExternal = activeExternalSubtitle,
            selectExternal = ::selectExternalSubtitle,
            openSubtitlesState = subtitleState,
            activeOpenSubtitle = activeOpenSubtitle,
            appearance = subtitleAppearance,
            retryOpenSubtitles = { subtitleViewModel.search(media, force = true) },
            downloadOpenSubtitle = { subtitleViewModel.download(it, subtitleAppearance.delayMs) },
            changeAppearance = ::saveSubtitleAppearance,
            onDismiss = { panel = null; controlFocused = false; wake() },
        )
    }
}

@Composable private fun PlayerBackControl(requester: FocusRequester, focus: (Boolean) -> Unit, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TextButton(onClick = click, modifier = Modifier.alignForPlayerBack().focusRequester(requester)
        .onFocusChanged { focused = it.isFocused; focus(it.isFocused) }
        .size(48.dp).background(if (focused) Color.White else Color(0x7710152E), CircleShape)
        .border(if (focused) 2.dp else 1.dp, if (focused) Cyan else Color(0x55FFFFFF), CircleShape),
        contentPadding = PaddingValues(0.dp)) {
        Icon(painterResource(R.drawable.ic_player_back), "Voltar", tint = if (focused) Navy else Color.White, modifier = Modifier.size(24.dp))
    }
}

private fun Modifier.alignForPlayerBack(): Modifier = this.padding(start = 28.dp, top = 24.dp)

@Composable private fun MiniControl(@DrawableRes icon: Int, description: String, focus: (Boolean) -> Unit, requester: FocusRequester? = null, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusModifier = if (requester != null) Modifier.focusRequester(requester) else Modifier
    TextButton(onClick = click, modifier = focusModifier.onFocusChanged { focused = it.isFocused; focus(it.isFocused) }
        .size(42.dp).graphicsLayer { scaleX = if (focused) 1.12f else 1f; scaleY = if (focused) 1.12f else 1f }
        .background(if (focused) Color.White else Color(0x6610152E), CircleShape)
        .border(if (focused) 2.dp else 1.dp, if (focused) Cyan else Color(0x55FFFFFF), CircleShape),
        contentPadding = PaddingValues(0.dp)) {
        Icon(painterResource(icon), description, tint = if (focused) Navy else Color.White, modifier = Modifier.size(21.dp))
    }
}

@Composable private fun TvSeekBar(position: Long, duration: Long, seek: (Long) -> Unit, togglePlayback: () -> Unit, focus: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fraction = (position.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(26.dp).onFocusChanged { focused = it.isFocused; focus(it.isFocused) }
        .onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) false else when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seek((position - 30_000).coerceAtLeast(0)); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seek((position + 30_000).coerceAtMost(duration)); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayback(); true
                }
                else -> false
            }
        }.focusable(), contentAlignment = Alignment.Center) {
        LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(if (focused) 9.dp else 5.dp),
            color = if (focused) Cyan else Coral, trackColor = Color(0xFF555A6D))
    }
}

@OptIn(UnstableApi::class)
@Composable private fun TrackDialog(
    player: ExoPlayer,
    media: MediaEntry,
    panel: TrackPanel,
    revision: Int,
    activeExternal: com.droplay.tv.data.SubtitleTrack?,
    selectExternal: (com.droplay.tv.data.SubtitleTrack?) -> Unit,
    openSubtitlesState: SubtitleUiState,
    activeOpenSubtitle: SubtitleCandidate?,
    appearance: SubtitleAppearance,
    retryOpenSubtitles: () -> Unit,
    downloadOpenSubtitle: (SubtitleCandidate) -> Unit,
    changeAppearance: (SubtitleAppearance) -> Unit,
    onDismiss: () -> Unit,
) {
    val type = if (panel == TrackPanel.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
    val choices = remember(player, panel, revision) {
        player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).map { index -> Triple(group, index, group.getTrackFormat(index)) }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (panel == TrackPanel.AUDIO) "Faixa de áudio" else "Legendas") }, text = {
        Column(Modifier.widthIn(min = 520.dp).heightIn(max = 510.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (panel == TrackPanel.SUBTITLES) TextButton(onClick = {
                if (activeExternal != null) selectExternal(null)
                else player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
                onDismiss()
            }) { Text("Desativadas") }
            if (choices.isEmpty() && (panel != TrackPanel.SUBTITLES || media.subtitles.isEmpty())) Text(
                if (panel == TrackPanel.SUBTITLES && com.droplay.tv.data.CatalogOrganizer.isSubtitled(media))
                    "Esta versão é legendada, mas a legenda está incorporada à imagem e não pode ser ativada ou desativada."
                else "Nenhuma faixa disponível neste conteúdo.",
                color = Muted,
            )
            choices.forEachIndexed { n, (group, index, format) ->
                val label = listOfNotNull(format.label, format.language?.uppercase(), format.channelCount.takeIf { it > 0 }?.let { "$it canais" }).distinct().joinToString(" · ").ifBlank { "Faixa ${n + 1}" }
                TextButton(onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build(); onDismiss()
                }, Modifier.fillMaxWidth()) { Text(label, Modifier.fillMaxWidth()) }
            }
            if (panel == TrackPanel.SUBTITLES && media.subtitles.isNotEmpty()) {
                HorizontalDivider(color = Color(0x22FFFFFF))
                Text("Legendas fornecidas pelo servidor", color = Muted, fontSize = 12.sp)
                media.subtitles.forEachIndexed { index, subtitle ->
                    val label = listOfNotNull(subtitle.label, subtitle.language?.uppercase()).distinct().joinToString(" · ")
                        .ifBlank { "Legenda externa ${index + 1}" }
                    TextButton(onClick = { selectExternal(subtitle); onDismiss() }, Modifier.fillMaxWidth()) {
                        Text(if (subtitle == activeExternal) "$label  •  ATIVA" else label, Modifier.fillMaxWidth())
                    }
                }
            }
            if (panel == TrackPanel.SUBTITLES && media.kind != MediaKind.LIVE) {
                HorizontalDivider(color = Color(0x22FFFFFF))
                Text("OpenSubtitles", color = Cyan, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                when (openSubtitlesState) {
                    SubtitleUiState.Idle -> TextButton(onClick = retryOpenSubtitles) { Text("Buscar legendas automaticamente") }
                    is SubtitleUiState.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Cyan, strokeWidth = 2.dp)
                        Text("Buscando legendas… página ${openSubtitlesState.page}${openSubtitlesState.totalPages?.let { " de $it" }.orEmpty()}", Modifier.padding(start = 10.dp), color = Muted)
                    }
                    is SubtitleUiState.Downloading -> Text("Baixando ${openSubtitlesState.candidate.release.ifBlank { openSubtitlesState.candidate.fileName }}…", color = Muted)
                    is SubtitleUiState.Error -> Column {
                        Text(openSubtitlesState.message, color = Coral)
                        TextButton(onClick = retryOpenSubtitles) { Text("Tentar novamente") }
                    }
                    is SubtitleUiState.Results -> OpenSubtitlesResults(
                        groups = openSubtitlesState.groups, preferredLanguage = appearance.preferredLanguage,
                        approximate = openSubtitlesState.approximate, active = activeOpenSubtitle,
                        select = downloadOpenSubtitle, changePreferredLanguage = {
                            changeAppearance(appearance.copy(preferredLanguage = it))
                        },
                    )
                    is SubtitleUiState.Ready -> Text("Aplicando legenda…", color = Muted)
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                Text("Sincronização e aparência", color = Muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { changeAppearance(appearance.copy(delayMs = (appearance.delayMs - 500).coerceAtLeast(-30_000))) }) { Text("−0,5s") }
                    Text("Atraso: ${"%+.1f".format(appearance.delayMs / 1000.0)}s", Modifier.width(105.dp), color = Color.White)
                    TextButton(onClick = { changeAppearance(appearance.copy(delayMs = (appearance.delayMs + 500).coerceAtMost(30_000))) }) { Text("+0,5s") }
                    TextButton(onClick = { changeAppearance(appearance.copy(delayMs = 0)) }) { Text("Zerar") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { changeAppearance(appearance.copy(textScale = (appearance.textScale - .1f).coerceAtLeast(.7f))) }) { Text("A−") }
                    Text("Tamanho ${"%.0f".format(appearance.textScale * 100)}%", color = Color.White)
                    TextButton(onClick = { changeAppearance(appearance.copy(textScale = (appearance.textScale + .1f).coerceAtMost(1.6f))) }) { Text("A+") }
                    TextButton(onClick = { changeAppearance(appearance.copy(color = SubtitleColor.entries[(appearance.color.ordinal + 1) % SubtitleColor.entries.size])) }) { Text("Cor: ${appearance.color.name}") }
                    TextButton(onClick = { changeAppearance(appearance.copy(background = !appearance.background)) }) { Text(if (appearance.background) "Fundo: sim" else "Fundo: não") }
                    TextButton(onClick = { changeAppearance(appearance.copy(outline = !appearance.outline)) }) { Text(if (appearance.outline) "Contorno: sim" else "Contorno: não") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } })
}

@Composable
private fun OpenSubtitlesResults(
    groups: Map<String, List<SubtitleCandidate>>,
    preferredLanguage: String,
    approximate: Boolean,
    active: SubtitleCandidate?,
    select: (SubtitleCandidate) -> Unit,
    changePreferredLanguage: (String) -> Unit,
) {
    if (groups.isEmpty()) {
        Text("Nenhuma legenda encontrada.", color = Muted)
        return
    }
    val preferred = SubtitleRanking.normalizeLanguage(preferredLanguage)
    var language by remember(groups, preferred) { mutableStateOf(preferred.takeIf(groups::containsKey) ?: groups.keys.first()) }
    if (approximate) Text("Correspondência aproximada por título/ano. Confirme a versão antes de usar.", color = Color(0xFFFFC857), fontSize = 12.sp)
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(groups.keys.toList(), key = { it }) { code ->
            TextButton(onClick = { language = code; changePreferredLanguage(code) }) {
                Text(if (code == language) "● ${SubtitleRanking.languageLabel(code)}" else SubtitleRanking.languageLabel(code))
            }
        }
    }
    Text("${groups[language].orEmpty().size} versões em ${SubtitleRanking.languageLabel(language)}", color = Muted, fontSize = 11.sp)
    groups[language].orEmpty().take(40).forEachIndexed { index, candidate ->
        TextButton(onClick = { select(candidate) }, Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                val badges = buildList {
                    if (candidate == active) add("ATIVA")
                    if (candidate.trusted) add("CONFIÁVEL")
                    if (candidate.aiTranslated || candidate.machineTranslated) add("TRADUZIDA")
                }.joinToString(" • ")
                Text("${index + 1}. ${candidate.release.ifBlank { candidate.fileName }}${badges.takeIf(String::isNotBlank)?.let { "  •  $it" }.orEmpty()}", maxLines = 2)
                Text("★ ${"%.1f".format(candidate.rating)}  •  ${candidate.downloads} downloads", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

private fun applySubtitleAppearance(view: PlayerView, appearance: SubtitleAppearance) {
    val foreground = when (appearance.color) {
        SubtitleColor.WHITE -> android.graphics.Color.WHITE
        SubtitleColor.YELLOW -> android.graphics.Color.YELLOW
        SubtitleColor.CYAN -> android.graphics.Color.CYAN
    }
    val background = if (appearance.background) 0x99000000.toInt() else android.graphics.Color.TRANSPARENT
    val edgeType = if (appearance.outline) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE
    view.subtitleView?.apply {
        setFractionalTextSize(0.0533f * appearance.textScale)
        setStyle(CaptionStyleCompat(foreground, background, android.graphics.Color.TRANSPARENT, edgeType, android.graphics.Color.BLACK, null))
    }
}

private fun playerMediaItem(media: MediaEntry, externalSubtitle: com.droplay.tv.data.SubtitleTrack? = null): MediaItem {
    val builder = MediaItem.Builder().setUri(media.url)
    if (externalSubtitle != null) {
        builder.setSubtitleConfigurations(listOf(
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(externalSubtitle.url))
                .setMimeType(subtitleMimeType(externalSubtitle))
                .setLabel(externalSubtitle.label)
                .setLanguage(externalSubtitle.language)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        ))
    }
    return builder.build()
}

private fun subtitleMimeType(track: com.droplay.tv.data.SubtitleTrack): String = when {
    track.mimeType?.contains("vtt", true) == true -> MimeTypes.TEXT_VTT
    track.mimeType?.contains("ass", true) == true || track.mimeType?.contains("ssa", true) == true -> MimeTypes.TEXT_SSA
    track.mimeType?.contains("ttml", true) == true -> MimeTypes.APPLICATION_TTML
    track.url.substringBefore('?').endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
    track.url.substringBefore('?').endsWith(".ass", true) || track.url.substringBefore('?').endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
    track.url.substringBefore('?').let { it.endsWith(".ttml", true) || it.endsWith(".dfxp", true) || it.endsWith(".xml", true) } -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.APPLICATION_SUBRIP
}

private fun httpStatusCode(error: Throwable): Int? {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
