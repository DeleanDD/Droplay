package com.droplay.tv.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private enum class Section(val title: String, val glyph: String) {
    SEARCH("Buscar", "⌕"), HOME("Início", "⌂"), KIDS("Infantil", "★"), MOVIES("Filmes", "▶"),
    SERIES("Séries", "▤"), LIVE("Ao vivo", "●"), NATIONAL("Nacional", "◆"),
    FAVORITES("Favoritos", "♥"), SETTINGS("Configurações", "⚙")
}

private data class PlaybackSession(val media: MediaEntry, val startAt: Long)

@Composable
fun DroplayApp(state: AppState, viewModel: DroplayViewModel) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<MediaEntry?>(null) }
    var episodes by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var loadingEpisodes by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<PlaybackSession?>(null) }
    var resumeChoice by remember { mutableStateOf<Pair<MediaEntry, WatchRecord>?>(null) }
    var variantChoice by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var selectedSection by remember { mutableStateOf(Section.HOME) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val currentPlayback = playing

    fun start(media: MediaEntry, at: Long) {
        playing = PlaybackSession(media, at)
        viewModel.recordPlaybackStarted(media.id)
    }
    fun requestPlay(media: MediaEntry, offerVariants: Boolean = true) {
        val variants = state.preparedCatalog.movieVariants[CatalogOrganizer.movieVariantKey(media)].orEmpty().ifEmpty { listOf(media) }
        if (offerVariants && variants.map(CatalogOrganizer::isSubtitled).distinct().size > 1) {
            variantChoice = variants
            return
        }
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
        } else if (media.kind == MediaKind.MOVIE) {
            scope.launch {
                val enriched = viewModel.details(media)
                if (detail?.id == media.id) detail = enriched
            }
        }
    }

    BackHandler(enabled = playing != null || detail != null || selectedCategory != null || selectedSection != Section.HOME) {
        when {
            playing != null -> playing = null
            detail != null -> detail = null
            selectedCategory != null -> selectedCategory = null
            selectedSection != Section.HOME -> selectedSection = Section.HOME
        }
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
            onRefreshInterval = viewModel::setRefreshInterval, onRefresh = viewModel::refreshCatalog,
            onAdultContent = viewModel::setShowAdultContent, onCinemaContent = viewModel::setShowCinemaContent,
            onContentSort = viewModel::setContentSort,
            section = selectedSection, onSection = { selectedSection = it; selectedCategory = null }, category = selectedCategory,
            onCategory = { selectedCategory = it }, query = searchQuery, onQuery = { searchQuery = it },
        )
    }

    if (state.loading) LoadingOverlay("Carregando sua biblioteca…")
    resumeChoice?.let { (media, record) ->
        ResumeDialog(media, record, onDismiss = { resumeChoice = null }, onResume = {
            resumeChoice = null; start(media, record.positionMs)
        }, onRestart = { resumeChoice = null; start(media, 0) })
    }
    variantChoice?.let { variants ->
        VariantDialog(variants, onDismiss = { variantChoice = null }) { chosen ->
            variantChoice = null
            requestPlay(chosen, offerVariants = false)
        }
    }
}

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
                        CategoryTab("Xtream Codes", method == 0) { method = 0 }
                        CategoryTab("Link M3U", method == 1) { method = 1 }
                    }
                    if (method == 0) {
                        TvField(server, { server = it }, "Servidor", "http://servidor:porta")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvField(username, { username = it }, "Usuário", modifier = Modifier.weight(1f))
                            TvField(password, { password = it }, "Senha", modifier = Modifier.weight(1f), secret = true)
                        }
                    } else TvField(m3u, { m3u = it }, "URL da lista M3U / M3U Plus", "https://…", KeyboardType.Uri)
                    ModernButton("ENTRAR NO DROPLAY", onClick = { if (method == 0) connect(PlaylistSource.Xtream(server, username, password)) else connect(PlaylistSource.M3u(m3u)) },
                        enabled = valid, modifier = Modifier.fillMaxWidth().height(50.dp))
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
    onDisconnect: () -> Unit, onRefreshInterval: (RefreshInterval) -> Unit, onRefresh: () -> Unit,
    onAdultContent: (Boolean) -> Unit, onCinemaContent: (Boolean) -> Unit, onContentSort: (ContentSort) -> Unit,
    section: Section, onSection: (Section) -> Unit, category: String?, onCategory: (String?) -> Unit,
    query: String, onQuery: (String) -> Unit,
) {
    Row(Modifier.fillMaxSize().background(Navy)) {
        NavigationRail(section, onSection, Modifier.width(178.dp))
        Box(Modifier.weight(1f)) {
            when (section) {
                Section.HOME -> HomeContent(state, onOpen, onFavorite)
                Section.KIDS -> KidsContent(state, onOpen, onFavorite)
                Section.NATIONAL -> NationalContent(state, onOpen, onFavorite)
                Section.SETTINGS -> SettingsContent(
                    state, onRefreshInterval, onRefresh, onAdultContent, onCinemaContent, onContentSort, onDisconnect
                )
                else -> CategoryContent(section, state, query, onQuery, category, onCategory, onOpen, onFavorite)
            }
        }
    }
}

@Composable private fun NavigationRail(selected: Section, select: (Section) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxHeight().background(Color(0xFF080C22)), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        item { Brand(compact = true); Spacer(Modifier.height(11.dp)) }
        items(Section.entries) { item ->
            var focus by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().onFocusChanged { focus = it.isFocused }.clip(RoundedCornerShape(10.dp))
                .background(when {
                    item == Section.NATIONAL && (item == selected || focus) -> Color(0xFF176B3A)
                    item == Section.KIDS && (item == selected || focus) -> Color(0xFF5D3AC7)
                    item == selected || focus -> Color(0xFF25204D)
                    else -> Color.Transparent
                })
                .clickable { select(item) }.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.glyph, color = when (item) { Section.NATIONAL -> Color(0xFFFFD43B); Section.KIDS -> Color(0xFFFF8FCC); else -> if (item == selected) Cyan else Color.White }, fontSize = 17.sp)
                Text(item.title, Modifier.padding(start = 11.dp), fontSize = 13.sp, fontWeight = if (item == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable private fun HomeContent(state: AppState, open: (MediaEntry) -> Unit, favorite: (String) -> Unit) {
    val entries = state.preparedCatalog.entries
    val featured = remember(entries) { entries.firstOrNull { it.kind != MediaKind.LIVE && !it.logo.isNullOrBlank() } ?: entries.firstOrNull() }
    val recent = remember(entries, state.history, state.catalog.entries, state.showAdultContent, state.showCinemaContent) { state.visibleHistory(entries) }
    val groups = state.preparedCatalog.homeShelves
    val live = state.preparedCatalog.live.take(24)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 50.dp), verticalArrangement = Arrangement.spacedBy(25.dp)) {
        item { featured?.let { NetflixHero(it, state.progress(it.id), it.id in state.favorites, { open(it) }, { favorite(it.id) }) } }
        if (recent.isNotEmpty()) item { PosterShelf("Continuar assistindo", recent.take(20), state, open, favorite) }
        groups.forEach { (name, items) -> item { PosterShelf(name, items.take(24), state, open, favorite) } }
        if (live.isNotEmpty()) item { PosterShelf("Ao vivo", live, state, open, favorite) }
    }
}

@Composable private fun KidsContent(state: AppState, open: (MediaEntry) -> Unit, favorite: (String) -> Unit) {
    val prepared = state.preparedCatalog
    val movies = prepared.kidsMovies
    val series = prepared.kidsSeries
    val cartoons = prepared.kidsCartoons
    val live = prepared.kidsLive
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF392080), Color(0xFF101B55), Navy))),
        contentPadding = PaddingValues(bottom = 50.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF7C4DFF), Color(0xFFFF5FA2), Color(0xFFFFC857)))).padding(34.dp)) {
                Text("MUNDO INFANTIL ★", fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text("Filmes, desenhos e canais para a família.", color = Color.White.copy(alpha = .9f))
            }
        }
        if (movies.isNotEmpty()) item { PosterShelf("🍿 Filmes", movies, state, open, favorite) }
        if (series.isNotEmpty()) item { PosterShelf("✨ Séries", series, state, open, favorite) }
        if (cartoons.isNotEmpty()) item { PosterShelf("🎨 Desenhos animados", cartoons, state, open, favorite) }
        if (live.isNotEmpty()) item { PosterShelf("📺 TV ao vivo infantil", live, state, open, favorite) }
        if (movies.isEmpty() && series.isEmpty() && cartoons.isEmpty() && live.isEmpty()) item { EmptyMessage("A lista não identificou conteúdo infantil.") }
    }
}

@Composable private fun NationalContent(state: AppState, open: (MediaEntry) -> Unit, favorite: (String) -> Unit) {
    val movies = state.preparedCatalog.nationalMovies
    val series = state.preparedCatalog.nationalSeries
    val novels = state.preparedCatalog.nationalNovels
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF063D24), Navy))),
        contentPadding = PaddingValues(bottom = 50.dp), verticalArrangement = Arrangement.spacedBy(25.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF07883F), Color(0xFFF4C430)))).padding(34.dp)) {
                Text("DROPLAY NACIONAL", fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text("Produções brasileiras em destaque", color = Color(0xFF062D1C))
            }
        }
        if (movies.isNotEmpty()) item { PosterShelf("Filmes brasileiros", movies, state, open, favorite) }
        if (series.isNotEmpty()) item { PosterShelf("Séries brasileiras", series, state, open, favorite) }
        if (novels.isNotEmpty()) item { PosterShelf("Novelas", novels, state, open, favorite) }
        if (movies.isEmpty() && series.isEmpty() && novels.isEmpty()) item { EmptyMessage("A lista não identificou produções nacionais.") }
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
                ModernButton(if (progress != null) "▶  Continuar" else "▶  Assistir", play)
                ModernButton(if (favorite) "♥  Minha lista" else "+  Minha lista", fav, primary = false)
            }
        }
    }
}

@Composable private fun PosterShelf(title: String, entries: List<MediaEntry>, state: AppState, open: (MediaEntry) -> Unit, favorite: (String) -> Unit) {
    val shelf = remember(entries) { entries.take(120) }
    Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
            items(shelf, key = { it.id }) { PosterCard(it, state.progress(it.id), it.id in state.favorites, { open(it) }, { favorite(it.id) }) }
        }
    }
}

@Composable private fun CategoryContent(
    section: Section, state: AppState, query: String, onQuery: (String) -> Unit,
    category: String?, onCategory: (String?) -> Unit, open: (MediaEntry) -> Unit, favorite: (String) -> Unit,
) {
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(section) {
        if (section == Section.SEARCH) {
            delay(180)
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    var appliedQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) { delay(180); appliedQuery = query }
    val prepared = state.preparedCatalog
    val catalog = prepared.entries
    val all = remember(section, catalog, state.favorites, appliedQuery) {
        when (section) {
            Section.LIVE -> prepared.live
            Section.MOVIES -> prepared.movies
            Section.SERIES -> prepared.series
            Section.FAVORITES -> catalog.filter { it.id in state.favorites }
            Section.SEARCH -> catalog.filter { appliedQuery.length > 1 && (it.name.contains(appliedQuery, true) || it.group.contains(appliedQuery, true)) }
            else -> emptyList()
        }
    }
    val categories = remember(section, all) { when (section) {
        Section.MOVIES -> listOf(CatalogOrganizer.RECENT, "Lançamentos ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}") +
            prepared.categoryIndex[MediaKind.MOVIE].orEmpty().keys.sorted() + "Todos"
        Section.SERIES -> listOf(CatalogOrganizer.RELEASES) +
            prepared.categoryIndex[MediaKind.SERIES].orEmpty().keys.sortedWith(compareBy(::seriesCategoryRank, String::lowercase)) + "Todos"
        Section.LIVE -> listOf("Todos") + prepared.categoryIndex[MediaKind.LIVE].orEmpty().keys
            .sortedWith(compareBy({ if (it == CatalogOrganizer.FOOTBALL) 0 else 1 }, String::lowercase))
        Section.FAVORITES -> listOf("Filmes", "Séries", "TV ao vivo")
        else -> emptyList()
    }.distinct() }
    val activeCategory = category ?: when (section) {
        Section.MOVIES -> CatalogOrganizer.RECENT
        Section.SERIES -> CatalogOrganizer.RELEASES
        Section.FAVORITES -> "Filmes"
        Section.LIVE -> "Todos"
        else -> null
    }
    val selectedItems = remember(section, activeCategory, all) { when {
        activeCategory == null || activeCategory == "Todos" -> all
        section == Section.MOVIES && activeCategory == CatalogOrganizer.RECENT -> prepared.recentMovies
        section == Section.MOVIES && activeCategory.startsWith("Lançamentos ") -> prepared.releaseMovies
        section == Section.SERIES && activeCategory == CatalogOrganizer.RELEASES -> prepared.releaseSeries
        section == Section.FAVORITES && activeCategory == "Filmes" -> all.filter { it.kind == MediaKind.MOVIE }
        section == Section.FAVORITES && activeCategory == "Séries" -> all.filter { it.kind == MediaKind.SERIES }
        section == Section.FAVORITES && activeCategory == "TV ao vivo" -> all.filter { it.kind == MediaKind.LIVE }
        section == Section.MOVIES -> prepared.categoryIndex[MediaKind.MOVIE]?.get(activeCategory).orEmpty()
        section == Section.SERIES -> prepared.categoryIndex[MediaKind.SERIES]?.get(activeCategory).orEmpty()
        section == Section.LIVE -> prepared.categoryIndex[MediaKind.LIVE]?.get(activeCategory).orEmpty()
        else -> emptyList()
    } }
    val visible = remember(selectedItems, state.contentSort, state.playCounts) { CatalogOrganizer.sort(selectedItems, state.contentSort, state.playCounts) }
    Column(Modifier.fillMaxSize().padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(section.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black); Text("${visible.size} títulos", color = Muted) }
            if (section == Section.SEARCH) TvField(query, onQuery, "Buscar filmes, séries e canais", modifier = Modifier.width(420.dp).focusRequester(searchFocus))
        }
        if (section in listOf(Section.LIVE, Section.MOVIES, Section.SERIES, Section.FAVORITES) && categories.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(categories) { name -> CategoryTab(name, activeCategory == name, gold = name == CatalogOrganizer.FOOTBALL) { onCategory(name) } }
            }
        }
        if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (section == Section.SEARCH && query.length < 2) "Digite ao menos 2 caracteres" else "Nada por aqui ainda.", color = Muted) }
        else LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp, 4.dp, 32.dp, 50.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(visible, key = { it.id }) { PosterCard(it, state.progress(it.id), it.id in state.favorites, { open(it) }, { favorite(it.id) }, removable = section == Section.FAVORITES) }
        }
    }
}

@Composable private fun CategoryTab(text: String, selected: Boolean, gold: Boolean = false, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(Modifier.onFocusChanged { focused = it.isFocused }.clickable(onClick = click), shape = RoundedCornerShape(50),
        color = when { gold -> Color(0xFFD4A017); selected || focused -> Color.White; else -> Surface },
        contentColor = if (gold || selected || focused) Navy else Color.White) {
        Text(text, Modifier.padding(horizontal = 17.dp, vertical = 9.dp), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable private fun PosterCard(item: MediaEntry, progress: WatchRecord?, favorite: Boolean, open: () -> Unit, fav: () -> Unit, removable: Boolean = false) {
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
        if (removable) TextButton(onClick = fav, Modifier.fillMaxWidth().height(34.dp), contentPadding = PaddingValues(2.dp)) {
            Text("REMOVER", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
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
            BackButton(back)
            Spacer(Modifier.height(20.dp)); Text(media.name, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text(listOfNotNull(CatalogOrganizer.category(media), media.year?.toString(), media.durationMs.takeIf { it > 0 }?.let(::timeLabel)).joinToString("  •  "), color = Cyan, fontSize = 13.sp)
            Spacer(Modifier.height(13.dp))
            Text(media.description ?: "Pronto para assistir.", color = Color(0xFFD1D5E4), fontSize = 16.sp, lineHeight = 23.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            progress?.let { Text("Você parou em ${timeLabel(it.positionMs)} de ${timeLabel(it.durationMs)}", Modifier.padding(top = 14.dp), color = Cyan) }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModernButton(if (progress != null) "▶  Continuar assistindo" else "▶  Assistir", play)
                ModernButton(if (favorite) "♥  Na minha lista" else "+  Minha lista", fav, primary = false)
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
                BackButton(back)
                Text(series.name, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text(series.description ?: "Escolha uma temporada e um episódio.", color = Color(0xFFD2D6E4), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    lastEpisode?.let { ModernButton("▶  Continuar ${it.name}", { play(it) }) }
                    ModernButton(if (series.id in state.favorites) "♥  Na minha lista" else "+  Minha lista", fav, primary = false)
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
            if (episode.durationMs > 0) Text("Duração: ${timeLabel(episode.durationMs)}", color = Cyan, fontSize = 11.sp)
        }
        Text(if (progress != null) "Continuar  ▶" else "Assistir  ▶", color = if (focused) Cyan else Color.White, fontSize = 12.sp)
    }
}

@Composable private fun SettingsContent(
    state: AppState,
    setRefreshInterval: (RefreshInterval) -> Unit,
    refresh: () -> Unit,
    setAdultContent: (Boolean) -> Unit,
    setCinemaContent: (Boolean) -> Unit,
    setContentSort: (ContentSort) -> Unit,
    disconnect: () -> Unit,
) {
    var askPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(42.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Configurações", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black) }
        item { Surface(shape = RoundedCornerShape(16.dp), color = Surface) {
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Player DROPLAY", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Media3 com HLS, DASH, RTSP e H.265/HEVC via hardware, com fallback entre decodificadores.", color = Muted)
                }
                Text("ATIVO", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        } }
        item { Surface(shape = RoundedCornerShape(16.dp), color = Surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("Proteção de conteúdo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Conteúdo adulto (+18) fica oculto por padrão. A senha para liberar a exibição é 0000.", color = Muted)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.showAdultContent) "+18 visível" else "+18 bloqueado", Modifier.weight(1f), color = if (state.showAdultContent) Coral else Cyan, fontWeight = FontWeight.Bold)
                    ModernButton(if (state.showAdultContent) "Ocultar +18" else "Desbloquear +18", {
                        if (state.showAdultContent) setAdultContent(false) else { pin = ""; pinError = false; askPin = true }
                    }, primary = false)
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Filmes identificados como CINEMA", fontWeight = FontWeight.Bold); Text("Ocultos por padrão por normalmente terem baixa qualidade.", color = Muted, fontSize = 12.sp) }
                    ModernButton(if (state.showCinemaContent) "Ocultar" else "Exibir", { setCinemaContent(!state.showCinemaContent) }, primary = false)
                }
            }
        } }
        item { Surface(shape = RoundedCornerShape(16.dp), color = Surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("Organização do catálogo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Mais assistidos usa a quantidade de reproduções registrada neste aparelho.", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CategoryTab("Ano (mais novos)", state.contentSort == ContentSort.YEAR_DESC) { setContentSort(ContentSort.YEAR_DESC) }
                    CategoryTab("Ordem alfabética", state.contentSort == ContentSort.ALPHABETICAL) { setContentSort(ContentSort.ALPHABETICAL) }
                    CategoryTab("Mais assistidos", state.contentSort == ContentSort.MOST_WATCHED) { setContentSort(ContentSort.MOST_WATCHED) }
                }
            }
        } }
        item { Surface(shape = RoundedCornerShape(16.dp), color = Surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("Atualização da biblioteca", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Por padrão, a lista é baixada uma vez ao dia. O catálogo salvo abre imediatamente.", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CategoryTab("Toda abertura", state.refreshInterval == RefreshInterval.EVERY_LAUNCH) { setRefreshInterval(RefreshInterval.EVERY_LAUNCH) }
                    CategoryTab("1x ao dia", state.refreshInterval == RefreshInterval.DAILY) { setRefreshInterval(RefreshInterval.DAILY) }
                    CategoryTab("1x por semana", state.refreshInterval == RefreshInterval.WEEKLY) { setRefreshInterval(RefreshInterval.WEEKLY) }
                    CategoryTab("1x por mês", state.refreshInterval == RefreshInterval.MONTHLY) { setRefreshInterval(RefreshInterval.MONTHLY) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.lastRefreshMs > 0) "Última atualização: ${formatDate(state.lastRefreshMs)}" else "Ainda não atualizado", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                    ModernButton("↻  Atualizar agora", refresh, primary = false)
                }
            }
        } }
        item { Surface(shape = RoundedCornerShape(16.dp), color = Surface) {
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Conta e biblioteca", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("Sair para entrar com outra lista ou usuário.", color = Muted) }
                ModernButton("Sair / trocar usuário", disconnect, danger = true)
            }
        } }
        item { Text("DROPLAY 1.2.2  •  Nenhum conteúdo é fornecido pelo aplicativo.", color = Muted, fontSize = 12.sp) }
    }
    if (askPin) AlertDialog(onDismissRequest = { askPin = false }, title = { Text("Liberar conteúdo +18") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Digite a senha de quatro dígitos.", color = Muted)
            TvField(pin, { pin = it.filter(Char::isDigit).take(4); pinError = false }, "Senha", keyboard = KeyboardType.NumberPassword, secret = true)
            if (pinError) Text("Senha incorreta.", color = Coral)
        }
    }, confirmButton = { ModernButton("Liberar", {
        if (pin == "0000") { setAdultContent(true); askPin = false } else pinError = true
    }, enabled = pin.length == 4) }, dismissButton = { ModernButton("Cancelar", { askPin = false }, primary = false) })
}

@Composable private fun ResumeDialog(media: MediaEntry, record: WatchRecord, onDismiss: () -> Unit, onResume: () -> Unit, onRestart: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Continuar assistindo?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(media.name, fontWeight = FontWeight.Bold)
            Text("Você parou em ${timeLabel(record.positionMs)}${if (record.durationMs > 0) " de ${timeLabel(record.durationMs)}" else ""}.", color = Muted)
            if (record.durationMs > 0) LinearProgressIndicator(progress = { (record.positionMs.toFloat() / record.durationMs).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = Coral)
        }
    }, confirmButton = { ModernButton("Continuar", onResume) }, dismissButton = { ModernButton("Reiniciar", onRestart, primary = false) })
}

@Composable private fun VariantDialog(variants: List<MediaEntry>, onDismiss: () -> Unit, choose: (MediaEntry) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Como deseja assistir?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(CatalogOrganizer.movieTitleKey(variants.first()).replaceFirstChar(Char::uppercase), fontWeight = FontWeight.Bold)
            Text("Este título possui mais de uma versão.", color = Muted)
            variants.sortedBy(CatalogOrganizer::isSubtitled).forEach { item ->
                ModernButton(if (CatalogOrganizer.isSubtitled(item)) "CC  Legendado" else "◉  Dublado", { choose(item) },
                    primary = !CatalogOrganizer.isSubtitled(item), modifier = Modifier.fillMaxWidth())
            }
        }
    }, confirmButton = {}, dismissButton = { ModernButton("Cancelar", onDismiss, primary = false) })
}

@Composable private fun ModernButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = true,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when { danger -> Coral; primary -> Color.White; else -> Color(0x331AFFFFFF) }
    val content = if (primary && !danger) Navy else Color.White
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.graphicsLayer {
            scaleX = if (focused) 1.04f else 1f; scaleY = if (focused) 1.04f else 1f
        }.border(if (focused) 2.dp else 0.dp, Cyan, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content,
            disabledContainerColor = Color(0xFF31374E), disabledContentColor = Muted),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1) }
}

@Composable private fun BackButton(onClick: () -> Unit) {
    ModernButton("←  VOLTAR", onClick, primary = false)
}

private fun AppState.progress(id: String) = history.firstOrNull {
    it.mediaId == id && (it.durationMs <= 0 || it.positionMs < it.durationMs - 30_000)
}

private fun AppState.visibleHistory(visibleCatalog: List<MediaEntry>): List<MediaEntry> {
    val visibleById = visibleCatalog.associateBy(MediaEntry::id)
    val hiddenSeriesIds = catalog.entries.asSequence()
        .filter { it.kind == MediaKind.SERIES && it.id !in visibleById }
        .mapNotNull { it.seriesId }
        .toSet()
    return history.mapNotNull { record ->
        visibleById[record.mediaId] ?: record.asMediaEntry().takeIf { fallback ->
            fallback.url.isNotBlank() && record.parentSeriesId !in hiddenSeriesIds &&
                CatalogOrganizer.visibleEntries(listOf(fallback), showAdultContent, showCinemaContent).isNotEmpty()
        }
    }
}

private fun seriesCategoryRank(value: String): Int {
    val text = value.lowercase()
    return when {
        listOf("disney", "hbo", "max", "netflix", "prime", "amazon", "apple", "paramount").any(text::contains) -> 0
        text.contains("dorama") -> 1
        text.contains("novela") -> 2
        else -> 3
    }
}

@Composable private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { Text(text, color = Muted) }
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

private fun formatDate(timeMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timeMs))
