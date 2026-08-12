package com.droplay.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.droplay.tv.AppState
import com.droplay.tv.DroplayViewModel
import com.droplay.tv.R
import com.droplay.tv.data.*
import kotlinx.coroutines.launch

private enum class Section(val title: String, val glyph: String) {
    HOME("Início", "⌂"), LIVE("Ao vivo", "●"), MOVIES("Filmes", "▶"), SERIES("Séries", "▤"),
    FAVORITES("Favoritos", "♥"), HISTORY("Recentes", "↻"), SEARCH("Buscar", "⌕"), SETTINGS("Ajustes", "⚙")
}

@Composable
fun DroplayApp(state: AppState, viewModel: DroplayViewModel) {
    var playing by remember { mutableStateOf<MediaEntry?>(null) }
    var episodeList by remember { mutableStateOf<List<MediaEntry>?>(null) }
    val scope = rememberCoroutineScope()

    when {
        playing != null -> PlayerScreen(
            media = playing!!,
            resumeAt = state.history.firstOrNull { it.mediaId == playing!!.id }?.positionMs ?: 0,
            favorite = playing!!.id in state.favorites,
            onFavorite = { viewModel.toggleFavorite(playing!!.id) },
            onProgress = { p, d -> viewModel.saveProgress(playing!!.id, p, d) },
            onBack = { playing = null },
        )
        state.source == null && !state.loading -> OnboardingScreen(state.error, viewModel::dismissError, viewModel::connect)
        else -> CatalogScreen(
            state = state, onPlay = { media ->
                if (media.kind == MediaKind.SERIES && media.seriesId != null) {
                    scope.launch { episodeList = viewModel.episodes(media.seriesId) }
                } else playing = media
            },
            onFavorite = viewModel::toggleFavorite,
            onDisconnect = viewModel::disconnect,
        )
    }

    if (state.loading) LoadingOverlay("Carregando sua biblioteca…")
    episodeList?.let { episodes ->
        EpisodeDialog(episodes, onDismiss = { episodeList = null }, onPlay = { episodeList = null; playing = it })
    }
}

@Composable
private fun OnboardingScreen(error: String?, clearError: () -> Unit, connect: (PlaylistSource) -> Unit) {
    var method by remember { mutableIntStateOf(0) }
    var server by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }; var m3u by remember { mutableStateOf("") }
    val valid = if (method == 0) server.isNotBlank() && username.isNotBlank() && password.isNotBlank() else m3u.startsWith("http")

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF161E50), Navy), radius = 1000f))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 42.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Brand()
                Spacer(Modifier.height(12.dp))
                Text("Sua mídia. Sua tela.", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text("Player público e independente para Android TV.\nAdicione uma fonte que você tem autorização para acessar.", color = Muted, fontSize = 17.sp, lineHeight = 25.sp)
                Text("Nenhum conteúdo acompanha o aplicativo.", color = Cyan, fontSize = 13.sp)
            }
            Surface(Modifier.width(500.dp), shape = RoundedCornerShape(24.dp), color = Color(0xD910152E)) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Adicionar biblioteca", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChoiceChip("Xtream Codes", method == 0) { method = 0 }
                        ChoiceChip("Link M3U", method == 1) { method = 1 }
                    }
                    if (method == 0) {
                        TvField(server, { server = it }, "Servidor", "http://servidor:porta")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvField(username, { username = it }, "Usuário", modifier = Modifier.weight(1f))
                            TvField(password, { password = it }, "Senha", modifier = Modifier.weight(1f), secret = true)
                        }
                    } else TvField(m3u, { m3u = it }, "URL da lista M3U", "https://…", KeyboardType.Uri)
                    Button(
                        onClick = { if (method == 0) connect(PlaylistSource.Xtream(server, username, password)) else connect(PlaylistSource.M3u(m3u)) },
                        enabled = valid, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                    ) { Text("ENTRAR NO DROPLAY", fontWeight = FontWeight.Bold) }
                    error?.let {
                        Text(it, color = Coral, fontSize = 13.sp, modifier = Modifier.clickable { clearError() })
                    }
                }
            }
        }
    }
}

@Composable private fun TvField(value: String, onValue: (String) -> Unit, label: String, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier, secret: Boolean = false) {
    OutlinedTextField(value, onValue, modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, { Text(text) }, modifier = Modifier.height(42.dp))
}

@Composable
private fun CatalogScreen(state: AppState, onPlay: (MediaEntry) -> Unit, onFavorite: (String) -> Unit, onDisconnect: () -> Unit) {
    var section by remember { mutableStateOf(Section.HOME) }
    var query by remember { mutableStateOf("") }
    val byId = remember(state.catalog.entries) { state.catalog.entries.associateBy { it.id } }
    val recent = state.history.mapNotNull { byId[it.mediaId] }
    val filtered = remember(section, query, state.catalog.entries, state.favorites) {
        val base = when (section) {
            Section.LIVE -> state.catalog.entries.filter { it.kind == MediaKind.LIVE }
            Section.MOVIES -> state.catalog.entries.filter { it.kind == MediaKind.MOVIE }
            Section.SERIES -> state.catalog.entries.filter { it.kind == MediaKind.SERIES }
            Section.FAVORITES -> state.catalog.entries.filter { it.id in state.favorites }
            Section.HISTORY -> recent
            Section.SEARCH -> state.catalog.entries.filter { query.length > 1 && it.name.contains(query, true) }
            else -> state.catalog.entries
        }
        base
    }

    Row(Modifier.fillMaxSize().background(Navy)) {
        NavigationRail(section, { section = it }, Modifier.width(186.dp), onDisconnect)
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(section, label = "section") { selected ->
                if (selected == Section.HOME) HomeContent(state, recent, onPlay, onFavorite)
                else LibraryContent(selected.title, filtered, state, query, { query = it }, selected == Section.SEARCH, onPlay, onFavorite)
            }
        }
    }
}

@Composable
private fun NavigationRail(selected: Section, select: (Section) -> Unit, modifier: Modifier, onDisconnect: () -> Unit) {
    Column(modifier.fillMaxHeight().background(Color(0xFF080C22)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Brand(compact = true); Spacer(Modifier.height(18.dp))
        Section.entries.forEach { item -> NavItem(item, item == selected) { select(item) } }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDisconnect) { Text("Trocar lista", color = Muted, fontSize = 12.sp) }
    }
}

@Composable private fun NavItem(item: Section, selected: Boolean, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(12.dp))
        .background(if (selected || focused) Color(0xFF25204D) else Color.Transparent).clickable(onClick = click).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.glyph, color = if (selected) Cyan else Color.White, fontSize = 18.sp)
        Text(item.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
    }
}

@Composable
private fun HomeContent(state: AppState, recent: List<MediaEntry>, onPlay: (MediaEntry) -> Unit, onFavorite: (String) -> Unit) {
    val entries = state.catalog.entries
    val featured = entries.firstOrNull { it.kind != MediaKind.LIVE } ?: entries.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(34.dp, 28.dp, 34.dp, 60.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        item { featured?.let { Hero(it, state.epgFor(it), it.id in state.favorites, { onPlay(it) }, { onFavorite(it.id) }) } }
        if (recent.isNotEmpty()) item { MediaShelf("Continuar assistindo", recent, state, onPlay, onFavorite) }
        val live = entries.filter { it.kind == MediaKind.LIVE }.take(20)
        if (live.isNotEmpty()) item { MediaShelf("Agora na TV", live, state, onPlay, onFavorite) }
        val movies = entries.filter { it.kind == MediaKind.MOVIE }.take(20)
        if (movies.isNotEmpty()) item { MediaShelf("Filmes", movies, state, onPlay, onFavorite) }
        val series = entries.filter { it.kind == MediaKind.SERIES }.take(20)
        if (series.isNotEmpty()) item { MediaShelf("Séries", series, state, onPlay, onFavorite) }
    }
}

private fun AppState.epgFor(item: MediaEntry) = item.epgId?.let(catalog.epg::get)

@Composable private fun Hero(item: MediaEntry, epg: EpgProgram?, favorite: Boolean, play: () -> Unit, fav: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF302264), Color(0xFF0C1433))))) {
        item.backdrop?.let { AsyncImage(it, null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.55f), contentScale = ContentScale.Crop, alpha = .35f) }
        Column(Modifier.align(Alignment.CenterStart).padding(32.dp).width(580.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (item.kind == MediaKind.LIVE) "AGORA NO AR" else "EM DESTAQUE", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(item.name, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(epg?.title ?: item.description ?: item.group, color = Color(0xFFD0D5E6), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = play) { Text("▶  Assistir") }
                OutlinedButton(onClick = fav) { Text(if (favorite) "♥  Favorito" else "♡  Favoritar") }
            }
        }
    }
}

@Composable private fun MediaShelf(title: String, entries: List<MediaEntry>, state: AppState, play: (MediaEntry) -> Unit, favorite: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries, key = { it.id }) { MediaCard(it, state.epgFor(it), it.id in state.favorites, { play(it) }, { favorite(it.id) }) }
        }
    }
}

@Composable private fun LibraryContent(title: String, entries: List<MediaEntry>, state: AppState, query: String, onQuery: (String) -> Unit, searching: Boolean, play: (MediaEntry) -> Unit, favorite: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(34.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold); Text("${entries.size} itens", color = Muted) }
            if (searching) TvField(query, onQuery, "Digite para buscar", modifier = Modifier.width(390.dp))
        }
        if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (searching && query.length < 2) "Digite ao menos 2 caracteres" else "Nada por aqui ainda.", color = Muted) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 50.dp)) {
            items(entries, key = { it.id }) { item ->
                MediaListItem(item, state.epgFor(item), item.id in state.favorites, { play(item) }, { favorite(item.id) })
            }
        }
    }
}

@Composable private fun MediaCard(item: MediaEntry, epg: EpgProgram?, favorite: Boolean, play: () -> Unit, fav: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(210.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(16.dp))
        .border(if (focused) 3.dp else 1.dp, if (focused) Cyan else Color(0xFF222A4A), RoundedCornerShape(16.dp))
        .background(Surface).clickable(onClick = play).padding(10.dp)) {
        Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF171F43))) {
            item.logo?.let { AsyncImage(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(if (item.kind == MediaKind.LIVE) "AO VIVO" else item.kind.name, Modifier.align(Alignment.TopStart).padding(7.dp).background(Color(0xC905081B), RoundedCornerShape(6.dp)).padding(5.dp, 2.dp), color = if (item.kind == MediaKind.LIVE) Coral else Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(if (favorite) "♥" else "♡", Modifier.align(Alignment.TopEnd).clickable(onClick = fav).padding(8.dp), fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp)); Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        Text(epg?.title ?: item.group, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun MediaListItem(item: MediaEntry, epg: EpgProgram?, favorite: Boolean, play: () -> Unit, fav: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(14.dp)).background(if (focused) Color(0xFF252D55) else Surface)
        .border(if (focused) 2.dp else 0.dp, Cyan, RoundedCornerShape(14.dp)).clickable(onClick = play).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(86.dp, 52.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF1B2447)), contentAlignment = Alignment.Center) {
            item.logo?.let { AsyncImage(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("▶", color = Cyan)
        }
        Column(Modifier.padding(horizontal = 16.dp).weight(1f)) { Text(item.name, fontWeight = FontWeight.Bold); Text(epg?.title ?: item.group, color = Muted, fontSize = 12.sp) }
        Text(if (favorite) "♥" else "♡", Modifier.clickable(onClick = fav).padding(14.dp), color = if (favorite) Coral else Color.White, fontSize = 22.sp)
    }
}

@Composable private fun Brand(compact: Boolean = false) {
    if (!compact) {
        Image(painterResource(R.drawable.droplay_logo), "Logo DROPLAY", Modifier.size(190.dp))
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(if (compact) 34.dp else 50.dp).background(Brush.linearGradient(listOf(Violet, Cyan)), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("▶", color = Navy, fontSize = if (compact) 16.sp else 24.sp) }
        Text("DROPLAY", fontSize = if (compact) 17.sp else 27.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
    }
}

@Composable private fun LoadingOverlay(text: String) {
    Box(Modifier.fillMaxSize().background(Color(0xCC05081B)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) { CircularProgressIndicator(color = Cyan); Text(text) }
    }
}

@Composable private fun EpisodeDialog(episodes: List<MediaEntry>, onDismiss: () -> Unit, onPlay: (MediaEntry) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Episódios") }, text = {
        if (episodes.isEmpty()) Text("Nenhum episódio disponível.") else LazyColumn(Modifier.heightIn(max = 440.dp)) {
            items(episodes, key = { it.id }) { ep -> TextButton(onClick = { onPlay(ep) }, Modifier.fillMaxWidth()) { Text(ep.name, Modifier.fillMaxWidth()) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } })
}
