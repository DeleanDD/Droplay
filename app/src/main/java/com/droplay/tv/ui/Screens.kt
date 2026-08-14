package com.droplay.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droplay.tv.AppState
import com.droplay.tv.DroplayViewModel
import com.droplay.tv.R
import com.droplay.tv.data.*
import kotlinx.coroutines.launch

private enum class Section(val title: String, val glyph: String) {
    HOME("Início", "⌂"), LIVE("Ao vivo", "●"), MOVIES("Filmes", "▶"), SERIES("Séries", "▤"),
    FAVORITES("Minha lista", "♥"), HISTORY("Continuar", "↻"), SEARCH("Buscar", "⌕"), SETTINGS("Configurações", "⚙")
}

private data class PlaybackSession(val media: MediaEntry, val startAt: Long)

@Composable
fun DroplayApp(state: AppState, viewModel: DroplayViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<MediaEntry?>(null) }
    var episodes by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var loadingEpisodes by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<PlaybackSession?>(null) }
    var resumeChoice by remember { mutableStateOf<Pair<MediaEntry, WatchRecord>?>(null) }
    var selectedSection by remember { mutableStateOf(Section.HOME) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val currentPlayback = playing

    fun start(media: MediaEntry, at: Long) {
        if (state.playerEngine == PlayerEngine.VLC) {
            if (!launchVlc(context, media, at)) {
                Toast.makeText(context, "VLC não instalado. Abrindo com o player DROPLAY.", Toast.LENGTH_LONG).show()
                playing = PlaybackSession(media, at)
            }
        } else playing = PlaybackSession(media, at)
    }
    fun requestPlay(media: MediaEntry) {
        val record = state.history.firstOrNull { it.mediaId == media.id }
        if (record != null && record.positionMs > 10_000 && (record.durationMs <= 0 || record.positionMs < record.durationMs - 30_000)) {
            resumeChoice = media to record
        } else start(media, 0)
    }
    fun openDetails(media: MediaEntry) {
        detail = media
        episodes = emptyList()
        if (media.kind == MediaKind.SERIES && media.seriesId != null) {
            loadingEpisodes = true
            scope.launch {
                episodes = viewModel.episodes(media.seriesId)
                loadingEpisodes = false
            }
        }
    }

    BackHandler(enabled = playing != null || detail != null) {
        if (playing != null) playing = null else detail = null
    }

    when {
        currentPlayback != null -> PlayerScreen(
            media = currentPlayback.media, resumeAt = currentPlayback.startAt,
            favorite = currentPlayback.media.id in state.favorites,
            onFavorite = { viewModel.toggleFavorite(currentPlayback.media.id) },
            onProgress = { p, d -> viewModel.saveProgress(currentPlayback.media, p, d) },
            onBack = { playing = null },
        )
        detail != null -> DetailScreen(
            media = detail!!, episodes = episodes, loadingEpisodes = loadingEpisodes,
            state = state, onBack = { detail = null }, onPlay = ::requestPlay,
            onFavorite = { viewModel.toggleFavorite(detail!!.id) },
        )
        state.source == null && !state.loading -> OnboardingScreen(state.error, viewModel::dismissError, viewModel::connect)
        else -> CatalogScreen(
            state = state, onOpen = { if (it.kind == MediaKind.LIVE) requestPlay(it) else openDetails(it) },
            onFavorite = viewModel::toggleFavorite, onDisconnect = viewModel::disconnect,
            onPlayerEngine = viewModel::setPlayerEngine, section = selectedSection,
            onSection = { selectedSection = it; selectedCategory = null }, category = selectedCategory,
            onCategory = { selectedCategory = it }, query = searchQuery, onQuery = { searchQuery = it },
        )
    }

    if (state.loading) LoadingOverlay("Carregando sua biblioteca…")
    resumeChoice?.let { (media, record) ->
        ResumeDialog(media, record, onDismiss = { resumeChoice = null }, onResume = {
            resumeChoice = null; start(media, record.positionMs)
        }, onRestart = { resumeChoice = null; start(media, 0) })
    }
}

private fun launchVlc(context: Context, media: MediaEntry, position: Long): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(media.url), "video/*")
        setPackage("org.videolan.vlc")
        putExtra("title", media.name)
        putExtra("position", position)
        putExtra("from_start", position == 0L)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    true
} catch (_: ActivityNotFoundException) { false }

@Composable
private fun OnboardingScreen(error: String?, clearError: () -> Unit, connect: (PlaylistSource) -> Unit) {
    var method by remember { mutableIntStateOf(0) }
    var server by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }; var m3u by remember { mutableStateOf("") }
    val valid = if (method == 0) server.isNotBlank() && username.isNotBlank() && password.isNotBlank() else m3u.startsWith("http")
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF161E50), Navy), radius = 1000f))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 38.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Brand(); Text("Sua mídia. Sua tela.", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("Adicione uma fonte que você tem autorização para acessar.", color = Muted, fontSize = 16.sp)
                Text("Nenhum conteúdo acompanha o aplicativo.", color = Cyan, fontSize = 13.sp)
            }
            Surface(Modifier.width(500.dp), shape = RoundedCornerShape(24.dp), color = Color(0xE010152E)) {
                Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("Adicionar biblioteca", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(method == 0, { method = 0 }, { Text("Xtream Codes") })
                        FilterChip(method == 1, { method = 1 }, { Text("Link M3U") })
                    }
                    if (method == 0) {
                        TvField(server, { server = it }, "Servidor", "http://servidor:porta")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvField(username, { username = it }, "Usuário", modifier = Modifier.weight(1f))
                            TvField(password, { password = it }, "Senha", modifier = Modifier.weight(1f), secret = true)
                        }
                    } else TvField(m3u, { m3u = it }, "URL da lista M3U", "https://…", KeyboardType.Uri)
                    Button(onClick = { if (method == 0) connect(PlaylistSource.Xtream(server, username, password)) else connect(PlaylistSource.M3u(m3u)) },
                        enabled = valid, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("ENTRAR NO DROPLAY", fontWeight = FontWeight.Bold) }
                    error?.let { Text(it, color = Coral, fontSize = 13.sp, modifier = Modifier.clickable { clearError() }) }
                }
            }
        }
    }
}

@Composable private fun TvField(value: String, onValue: (String) -> Unit, label: String, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier, secret: Boolean = false) {
    OutlinedTextField(value, onValue, modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable
private fun CatalogScreen(
    state: AppState, onOpen: (MediaEntry) -> Unit, onFavorite: (String) -> Unit,
    onDisconnect: () -> Unit, onPlayerEngine: (PlayerEngine) -> Unit,
    section: Section, onSection: (Section) -> Unit, category: String?, onCategory: (String?) -> Unit,
    query: String, onQuery: (String) -> Unit,
) {
    Row(Modifier.fillMaxSize().background(Navy)) {
        NavigationRail(section, onSection, Modifier.width(178.dp))
        AnimatedContent(section, Modifier.weight(1f), label = "section") { selected ->
            when (selected) {
                Section.HOME -> HomeContent(state, onOpen, onFavorite)
                Section.SETTINGS -> SettingsContent(state.playerEngine, onPlayerEngine, onDisconnect)
                else -> CategoryContent(selected, state, query, onQuery, category, onCategory, onOpen, onFavorite)
            }
        }
    }
}

@Composable private fun NavigationRail(selected: Section, select: (Section) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxHeight().background(Color(0xFF080C22)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Brand(compact = true); Spacer(Modifier.height(17.dp))
        Section.entries.forEach { item ->
            var focus by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().onFocusChanged { focus = it.isFocused }.clip(RoundedCornerShape(10.dp))
                .background(if (item == selected || focus) Color(0xFF25204D) else Color.Transparent)
                .clickable { select(item) }.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.glyph, color = if (item == selected) Cyan else Color.White, fontSize = 17.sp)
                Text(item.title, Modifier.padding(start = 11.dp), fontSize = 13.sp, fontWeight = if (item == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable private fun HomeContent(state: AppState, open: (MediaEntry) -> Unit, favorite: (String) -> Unit) {
    val entries = state.catalog.entries
    val featured = entries.firstOrNull { it.kind != MediaKind.LIVE && !it.logo.isNullOrBlank() } ?: entries.firstOrNull()
    val recent = state.history.map { record -> entries.firstOrNull { it.id == record.mediaId } ?: record.asMediaEntry() }.filter { it.url.isNotBlank() }
    val groups = entries.filter { it.kind != MediaKind.LIVE }.groupBy { it.group }.entries.sortedByDescending { it.value.size }.take(6)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 50.dp), verticalArrangement = Arrangement.spacedBy(25.dp)) {
        item { featured?.let { NetflixHero(it, state.progress(it.id), it.id in state.favorites, { open(it) }, { favorite(it.id) }) } }
        if (recent.isNotEmpty()) item { PosterShelf("Continuar assistindo", recent.take(20), state, open, favorite) }
        groups.forEach { (name, items) -> item { PosterShelf(name, items.take(24), state, open, favorite) } }
        val live = entries.filter { it.kind == MediaKind.LIVE }.take(24)
        if (live.isNotEmpty()) item { PosterShelf("Ao vivo", live, state, open, favorite) }
    }
}

@Composable private fun NetflixHero(item: MediaEntry, progress: WatchRecord?, favorite: Boolean, play: () -> Unit, fav: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(330.dp).background(Color(0xFF11152A))) {
        AsyncImage(item.backdrop ?: item.logo, null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.72f), contentScale = ContentScale.Crop, alpha = .72f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Navy, Navy.copy(alpha = .78f), Color.Transparent))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 40.dp).width(530.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("DROPLAY DESTAQUE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(item.name, fontSize = 35.sp, fontWeight = FontWeight.Black, maxLines = 2)
            Text(item.description ?: item.group, color = Color(0xFFD4D7E4), maxLines = 3, overflow = TextOverflow.Ellipsis)
            progress?.let { Text("Assistido até ${timeLabel(it.positionMs)}", color = Cyan, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = play) { Text(if (progress != null) "▶  Continuar" else "▶  Assistir") }
                OutlinedButton(onClick = fav) { Text(if (favorite) "♥  Minha lista" else "+  Minha lista") }
            }
        }
    }
}

@Composable private fun PosterShelf(title: String, entries: List<MediaEntry>, state: AppState, open: (MediaEntry) -> Unit, favorite: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
            items(entries, key = { it.id }) { PosterCard(it, state.progress(it.id), it.id in state.favorites, { open(it) }, { favorite(it.id) }) }
        }
    }
}

@Composable private fun CategoryContent(
    section: Section, state: AppState, query: String, onQuery: (String) -> Unit,
    category: String?, onCategory: (String?) -> Unit, open: (MediaEntry) -> Unit, favorite: (String) -> Unit,
) {
    val all = when (section) {
        Section.LIVE -> state.catalog.entries.filter { it.kind == MediaKind.LIVE }
        Section.MOVIES -> state.catalog.entries.filter { it.kind == MediaKind.MOVIE }
        Section.SERIES -> state.catalog.entries.filter { it.kind == MediaKind.SERIES }
        Section.FAVORITES -> state.catalog.entries.filter { it.id in state.favorites }
        Section.HISTORY -> state.history.map { r -> state.catalog.entries.firstOrNull { it.id == r.mediaId } ?: r.asMediaEntry() }.filter { it.url.isNotBlank() }
        Section.SEARCH -> state.catalog.entries.filter { query.length > 1 && it.name.contains(query, true) }
        else -> emptyList()
    }
    val categories = remember(all) { all.map { it.group }.filter(String::isNotBlank).distinct().sorted() }
    val visible = if (category == null) all else all.filter { it.group == category }
    Column(Modifier.fillMaxSize().padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(section.title, fontSize = 30.sp, fontWeight = FontWeight.Black); Text("${visible.size} títulos", color = Muted) }
            if (section == Section.SEARCH) TvField(query, onQuery, "Buscar", modifier = Modifier.width(380.dp))
        }
        if (section in listOf(Section.LIVE, Section.MOVIES, Section.SERIES) && categories.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                item { CategoryTab("Todos", category == null) { onCategory(null) } }
                items(categories) { name -> CategoryTab(name, category == name) { onCategory(name) } }
            }
        }
        if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (section == Section.SEARCH && query.length < 2) "Digite ao menos 2 caracteres" else "Nada por aqui ainda.", color = Muted) }
        else LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp, 4.dp, 32.dp, 50.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(visible, key = { it.id }) { PosterCard(it, state.progress(it.id), it.id in state.favorites, { open(it) }, { favorite(it.id) }) }
        }
    }
}

@Composable private fun CategoryTab(text: String, selected: Boolean, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(Modifier.onFocusChanged { focused = it.isFocused }.clickable(onClick = click), shape = RoundedCornerShape(50),
        color = if (selected || focused) Color.White else Surface, contentColor = if (selected || focused) Navy else Color.White) {
        Text(text, Modifier.padding(horizontal = 17.dp, vertical = 9.dp), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable private fun PosterCard(item: MediaEntry, progress: WatchRecord?, favorite: Boolean, open: () -> Unit, fav: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(150.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(9.dp))
        .border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(9.dp)).background(Surface).clickable(onClick = open)) {
        Box(Modifier.fillMaxWidth().height(215.dp).background(Color(0xFF1B2341))) {
            if (!item.logo.isNullOrBlank()) AsyncImage(item.logo, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF27315B), Color(0xFF11162D)))), contentAlignment = Alignment.Center) { Text("▶", color = Cyan, fontSize = 30.sp) }
            if (item.kind == MediaKind.LIVE) Text("AO VIVO", Modifier.padding(7.dp).background(Coral, RoundedCornerShape(4.dp)).padding(5.dp, 2.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(if (favorite) "♥" else "♡", Modifier.align(Alignment.TopEnd).clickable(onClick = fav).padding(8.dp), color = if (favorite) Coral else Color.White, fontSize = 19.sp)
            progress?.takeIf { it.durationMs > 0 }?.let {
                LinearProgressIndicator(progress = { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp), color = Coral, trackColor = Color(0x99000000))
            }
        }
        Text(item.name, Modifier.padding(9.dp, 8.dp, 9.dp, 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(progress?.let { "Parou em ${timeLabel(it.positionMs)}" } ?: item.group, Modifier.padding(9.dp, 0.dp, 9.dp, 9.dp), color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun DetailScreen(media: MediaEntry, episodes: List<MediaEntry>, loadingEpisodes: Boolean, state: AppState, onBack: () -> Unit, onPlay: (MediaEntry) -> Unit, onFavorite: () -> Unit) {
    BackHandler(onBack = onBack)
    if (media.kind == MediaKind.SERIES) SeriesDetail(media, episodes, loadingEpisodes, state, onBack, onPlay, onFavorite)
    else MovieDetail(media, state.progress(media.id), media.id in state.favorites, onBack, { onPlay(media) }, onFavorite)
}

@Composable private fun MovieDetail(media: MediaEntry, progress: WatchRecord?, favorite: Boolean, back: () -> Unit, play: () -> Unit, fav: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Navy)) {
        AsyncImage(media.backdrop ?: media.logo, null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.7f), contentScale = ContentScale.Crop, alpha = .38f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Navy, Navy.copy(.94f), Color.Transparent))))
        Column(Modifier.fillMaxHeight().width(700.dp).padding(38.dp), verticalArrangement = Arrangement.Center) {
            TextButton(onClick = back) { Text("← VOLTAR") }
            Spacer(Modifier.height(20.dp)); Text(media.name, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text(media.group, color = Cyan, fontSize = 13.sp); Spacer(Modifier.height(13.dp))
            Text(media.description ?: "Pronto para assistir.", color = Color(0xFFD1D5E4), fontSize = 16.sp, lineHeight = 23.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            progress?.let { Text("Você parou em ${timeLabel(it.positionMs)} de ${timeLabel(it.durationMs)}", Modifier.padding(top = 14.dp), color = Cyan) }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = play) { Text(if (progress != null) "▶  Continuar assistindo" else "▶  Assistir") }
                OutlinedButton(onClick = fav) { Text(if (favorite) "♥  Na minha lista" else "+  Minha lista") }
            }
        }
    }
}

@Composable private fun SeriesDetail(series: MediaEntry, episodes: List<MediaEntry>, loading: Boolean, state: AppState, back: () -> Unit, play: (MediaEntry) -> Unit, fav: () -> Unit) {
    val seasons = episodes.mapNotNull { it.season }.distinct().sorted()
    val lastEpisode = state.history.firstOrNull { it.parentSeriesId == series.seriesId }?.let { h -> episodes.firstOrNull { it.id == h.mediaId } }
    var selectedSeason by remember(seasons, lastEpisode?.season) { mutableIntStateOf(lastEpisode?.season ?: seasons.firstOrNull() ?: 1) }
    val visible = episodes.filter { it.season == selectedSeason }
    Column(Modifier.fillMaxSize().background(Navy)) {
        Box(Modifier.fillMaxWidth().height(245.dp)) {
            AsyncImage(series.backdrop ?: series.logo, null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.65f), contentScale = ContentScale.Crop, alpha = .4f)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Navy, Navy.copy(.88f), Color.Transparent))))
            Column(Modifier.align(Alignment.CenterStart).padding(35.dp).width(650.dp)) {
                TextButton(onClick = back) { Text("← VOLTAR") }
                Text(series.name, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(series.description ?: "Escolha uma temporada e um episódio.", color = Color(0xFFD2D6E4), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    lastEpisode?.let { Button(onClick = { play(it) }) { Text("▶  Continuar ${it.name}") } }
                    OutlinedButton(onClick = fav) { Text(if (series.id in state.favorites) "♥  Na minha lista" else "+  Minha lista") }
                }
            }
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = Cyan); Text("Organizando temporadas…", Modifier.padding(top = 12.dp), color = Muted) } }
        else if (episodes.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum episódio encontrado.", color = Muted) }
        else Column(Modifier.fillMaxSize().padding(horizontal = 35.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 13.dp)) {
                items(seasons) { season -> CategoryTab("Temporada $season", selectedSeason == season) { selectedSeason = season } }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 45.dp)) {
                items(visible, key = { it.id }) { episode -> EpisodeRow(episode, state.progress(episode.id)) { play(episode) } }
            }
        }
    }
}

@Composable private fun EpisodeRow(episode: MediaEntry, progress: WatchRecord?, play: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(10.dp))
        .background(if (focused) Color(0xFF26305A) else Surface).border(if (focused) 2.dp else 0.dp, Color.White, RoundedCornerShape(10.dp))
        .clickable(onClick = play).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(150.dp, 84.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF1A2240))) {
            episode.logo?.let { AsyncImage(it, episode.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text("▶", Modifier.align(Alignment.Center), fontSize = 22.sp)
            progress?.takeIf { it.durationMs > 0 }?.let { LinearProgressIndicator(progress = { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp), color = Coral) }
        }
        Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
            Text("${episode.episode ?: ""}. ${episode.name}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(episode.description ?: progress?.let { "Parou em ${timeLabel(it.positionMs)}" } ?: "Episódio ${episode.episode ?: ""}", color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        Text(if (progress != null) "Continuar  ▶" else "Assistir  ▶", color = if (focused) Cyan else Color.White, fontSize = 12.sp)
    }
}

@Composable private fun SettingsContent(engine: PlayerEngine, setEngine: (PlayerEngine) -> Unit, disconnect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(42.dp), verticalArrangement = Arrangement.spacedBy(23.dp)) {
        Text("Configurações", fontSize = 32.sp, fontWeight = FontWeight.Black)
        Surface(shape = RoundedCornerShape(16.dp), color = Surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Player de vídeo", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Escolha o player padrão. O VLC precisa estar instalado na TV e será mantido atualizado pela loja.", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayerChoice("DROPLAY", "Player integrado, histórico e controles completos", engine == PlayerEngine.DROPLAY) { setEngine(PlayerEngine.DROPLAY) }
                    PlayerChoice("VLC", "Abre no VLC instalado; a retomada fica a cargo do VLC", engine == PlayerEngine.VLC) { setEngine(PlayerEngine.VLC) }
                }
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = Surface) {
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Conta e biblioteca", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("Sair para entrar com outra lista ou usuário.", color = Muted) }
                Button(onClick = disconnect, colors = ButtonDefaults.buttonColors(containerColor = Coral)) { Text("Sair / trocar usuário") }
            }
        }
        Text("DROPLAY 1.1.0  •  Nenhum conteúdo é fornecido pelo aplicativo.", color = Muted, fontSize = 12.sp)
    }
}

@Composable private fun PlayerChoice(title: String, subtitle: String, selected: Boolean, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(Modifier.width(310.dp).onFocusChanged { focused = it.isFocused }.clickable(onClick = click)
        .border(if (selected || focused) 2.dp else 1.dp, if (selected) Cyan else Color(0xFF343D62), RoundedCornerShape(12.dp)), shape = RoundedCornerShape(12.dp), color = if (selected) Color(0xFF202A51) else Navy) {
        Column(Modifier.padding(17.dp)) { Text((if (selected) "●  " else "○  ") + title, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 11.sp) }
    }
}

@Composable private fun ResumeDialog(media: MediaEntry, record: WatchRecord, onDismiss: () -> Unit, onResume: () -> Unit, onRestart: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Continuar assistindo?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(media.name, fontWeight = FontWeight.Bold)
            Text("Você parou em ${timeLabel(record.positionMs)}${if (record.durationMs > 0) " de ${timeLabel(record.durationMs)}" else ""}.", color = Muted)
            if (record.durationMs > 0) LinearProgressIndicator(progress = { (record.positionMs.toFloat() / record.durationMs).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = Coral)
        }
    }, confirmButton = { Button(onClick = onResume) { Text("Continuar") } }, dismissButton = { OutlinedButton(onClick = onRestart) { Text("Reiniciar") } })
}

private fun AppState.progress(id: String) = history.firstOrNull {
    it.mediaId == id && (it.durationMs <= 0 || it.positionMs < it.durationMs - 30_000)
}

@Composable private fun Brand(compact: Boolean = false) {
    if (!compact) { Image(painterResource(R.drawable.droplay_logo), "Logo DROPLAY", Modifier.size(180.dp)); return }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(Brush.linearGradient(listOf(Violet, Cyan)), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text("▶", color = Navy) }
        Text("DROPLAY", Modifier.padding(start = 8.dp), fontSize = 16.sp, fontWeight = FontWeight.Black)
    }
}

@Composable private fun LoadingOverlay(text: String) {
    Box(Modifier.fillMaxSize().background(Color(0xDD05081B)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(15.dp)) { CircularProgressIndicator(color = Cyan); Text(text) }
    }
}

private fun timeLabel(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
