package com.droplay.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droplay.tv.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppState(
    val source: PlaylistSource? = null,
    val catalog: Catalog = Catalog(),
    val favorites: Set<String> = emptySet(),
    val history: List<WatchRecord> = emptyList(),
    val refreshInterval: RefreshInterval = RefreshInterval.WEEKLY,
    val lastRefreshMs: Long = 0L,
    val showAdultContent: Boolean = false,
    val showCinemaContent: Boolean = false,
    val contentSort: ContentSort = ContentSort.YEAR_DESC,
    val playCounts: Map<String, Int> = emptyMap(),
    val preparedCatalog: PreparedCatalog = PreparedCatalog(),
    val loading: Boolean = false,
    val loadingMessage: String = "Abrindo sua biblioteca…",
    val error: String? = null,
    val syncStates: Map<CatalogSection, SectionSyncState> = emptyMap(),
)

class DroplayViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DroplayRepository(application)
    private var loadGeneration = 0
    private var epgRequested = false
    private var connectJob: Job? = null
    private var refreshJob: Job? = null
    private val sectionCatalogMutex = Mutex()
    private val _state = MutableStateFlow(AppState(
        favorites = repository.favorites(), history = repository.history(),
        refreshInterval = repository.refreshInterval(), lastRefreshMs = repository.lastRefresh(),
        showAdultContent = repository.showAdultContent(), showCinemaContent = repository.showCinemaContent(),
        contentSort = repository.contentSort(), playCounts = repository.playCounts(),
    ))
    val state = _state.asStateFlow()

    init { repository.savedSource()?.let { connect(it) } }

    fun connect(source: PlaylistSource, force: Boolean = false) {
        connectJob?.cancel()
        refreshJob?.cancel()
        val generation = ++loadGeneration
        val refreshDue = force || repository.isRefreshDue(source)
        val message = if (refreshDue) "Procurando uma biblioteca salva…" else "Abrindo biblioteca salva…"
        _state.value = _state.value.copy(loading = true, loadingMessage = message, error = null)
        connectJob = viewModelScope.launch {
            val preferences = _state.value
            val cached = withContext(Dispatchers.IO) { repository.cached(source) }
            if (generation != loadGeneration) return@launch

            if (cached != null) {
                val initial = withContext(Dispatchers.Default) { CatalogOrganizer.prepareInitial(cached.entries) }
                if (generation != loadGeneration) return@launch
                _state.value = _state.value.copy(
                    source = source, catalog = cached, preparedCatalog = initial,
                    loading = false, lastRefreshMs = repository.lastRefresh(), error = null,
                    syncStates = CatalogSection.entries.associateWith { SectionSyncState(SyncPhase.UsingCache) },
                )
                viewModelScope.launch(Dispatchers.Default) {
                    val prepared = CatalogOrganizer.prepare(cached.entries, preferences.showAdultContent, preferences.showCinemaContent)
                    if (generation == loadGeneration && _state.value.source == source) {
                        _state.value = _state.value.copy(preparedCatalog = prepared)
                    }
                }
                viewModelScope.launch(Dispatchers.IO) {
                    delay(10_000)
                    runCatching { repository.ensureFastCache(source, cached.entries) }
                    runCatching { repository.ensureRoomCache(source, cached.entries) }
                    val reclassified = runCatching { repository.reclassifyIfNeeded(source) }.getOrNull()
                    if (reclassified != null && generation == loadGeneration) {
                        repository.cached(source)?.let { updated ->
                            val refreshed = withContext(Dispatchers.Default) { CatalogOrganizer.prepare(updated.entries, preferences.showAdultContent, preferences.showCinemaContent) }
                            if (generation == loadGeneration && _state.value.source == source) _state.value = _state.value.copy(catalog = updated, preparedCatalog = refreshed)
                        }
                    }
                }
                if (refreshDue) refreshCatalogInBackground(source, generation, preferences, immediate = force)
                return@launch
            }

            _state.value = _state.value.copy(loading = true, loadingMessage = "Conectando ao servidor…")
            runCatching {
                val catalog = withContext(Dispatchers.IO) {
                    repository.load(source, force = refreshDue, refreshAll = force, progress = { step ->
                        if (generation == loadGeneration) _state.value = _state.value.copy(loadingMessage = step)
                    }, sectionState = ::updateSectionState, sectionCommitted = { kind, items -> applyCommittedSection(source, generation, kind, items) })
                }
                _state.value = _state.value.copy(loadingMessage = "Organizando sua biblioteca…")
                val prepared = withContext(Dispatchers.Default) {
                    CatalogOrganizer.prepare(catalog.entries, preferences.showAdultContent, preferences.showCinemaContent)
                }
                catalog to prepared
            }.onSuccess { (catalog, prepared) ->
                if (generation != loadGeneration) return@onSuccess
                _state.value = _state.value.copy(
                    source = source, catalog = catalog, preparedCatalog = prepared,
                    loading = false, lastRefreshMs = repository.lastRefresh(), error = null,
                )
            }.onFailure { error ->
                if (generation != loadGeneration) return@onFailure
                _state.value = _state.value.copy(
                    source = null, catalog = Catalog(), preparedCatalog = PreparedCatalog(), loading = false,
                    error = friendlyLoadError(error),
                )
            }
        }
    }

    private fun refreshCatalogInBackground(
        source: PlaylistSource,
        generation: Int,
        preferences: AppState,
        immediate: Boolean,
    ) {
        refreshJob = viewModelScope.launch {
            if (!immediate) delay(20_000)
            runCatching {
                val catalog = withContext(Dispatchers.IO) { repository.load(source, force = true, refreshAll = immediate,
                    sectionState = ::updateSectionState, sectionCommitted = { kind, items -> applyCommittedSection(source, generation, kind, items) }) }
                val prepared = withContext(Dispatchers.Default) {
                    CatalogOrganizer.prepare(catalog.entries, preferences.showAdultContent, preferences.showCinemaContent)
                }
                catalog to prepared
            }.onSuccess { (catalog, prepared) ->
                if (generation == loadGeneration && _state.value.source == source) {
                    _state.value = _state.value.copy(
                        catalog = catalog, preparedCatalog = prepared, lastRefreshMs = repository.lastRefresh(), error = null,
                    )
                }
            }
        }
    }

    private fun applyCommittedSection(source: PlaylistSource, generation: Int, kind: MediaKind, items: List<MediaEntry>) {
        viewModelScope.launch {
            sectionCatalogMutex.withLock {
                if (generation != loadGeneration || _state.value.source != source) return@withLock
                val current = _state.value
                val merged = Catalog(current.catalog.entries.filter { it.kind != kind } + items, current.catalog.epg)
                val prepared = withContext(Dispatchers.Default) { CatalogOrganizer.prepare(merged.entries, current.showAdultContent, current.showCinemaContent) }
                if (generation == loadGeneration && _state.value.source == source) _state.value = _state.value.copy(catalog = merged, preparedCatalog = prepared)
            }
        }
    }

    private fun updateSectionState(section: CatalogSection, phase: SyncPhase, message: String?) {
        val current = _state.value.syncStates[section] ?: SectionSyncState()
        _state.value = _state.value.copy(syncStates = _state.value.syncStates + (section to current.copy(
            phase = phase, message = message,
            lastSuccessfulSyncAt = if (phase == SyncPhase.Success) System.currentTimeMillis() else current.lastSuccessfulSyncAt,
        )))
    }

    fun ensureEpg() {
        if (epgRequested) return
        val source = _state.value.source ?: return
        epgRequested = true
        viewModelScope.launch {
            val epg = runCatching { withContext(Dispatchers.IO) { repository.refreshEpg() } }.getOrDefault(emptyMap())
            if (_state.value.source == source && epg.isNotEmpty()) {
                _state.value = _state.value.copy(catalog = _state.value.catalog.copy(epg = epg))
            }
        }
    }

    suspend fun episodes(seriesId: String): List<MediaEntry> {
        val source = _state.value.source ?: return emptyList()
        return runCatching { withContext(Dispatchers.IO) { repository.loadEpisodes(source, seriesId) } }.getOrDefault(emptyList())
    }

    suspend fun details(media: MediaEntry): MediaEntry {
        val source = _state.value.source ?: return media
        return runCatching { withContext(Dispatchers.IO) { repository.loadDetails(source, media) } }.getOrDefault(media)
    }

    fun playbackMedia(media: MediaEntry): MediaEntry = _state.value.source?.let { repository.playbackMedia(it, media) } ?: media

    fun toggleFavorite(id: String) {
        val changed = repository.toggleFavorite(id)
        _state.value = _state.value.copy(favorites = changed)
        viewModelScope.launch(Dispatchers.IO) { repository.mirrorFavorite(id, id in changed) }
    }
    fun setRefreshInterval(interval: RefreshInterval) {
        repository.setRefreshInterval(interval)
        _state.value = _state.value.copy(refreshInterval = interval)
    }
    fun setShowAdultContent(show: Boolean) {
        repository.setShowAdultContent(show)
        _state.value = _state.value.copy(showAdultContent = show)
        rebuildPreparedCatalog(showAdult = show)
    }
    fun setShowCinemaContent(show: Boolean) {
        repository.setShowCinemaContent(show)
        _state.value = _state.value.copy(showCinemaContent = show)
        rebuildPreparedCatalog(showCinema = show)
    }
    fun setContentSort(sort: ContentSort) {
        repository.setContentSort(sort)
        _state.value = _state.value.copy(contentSort = sort)
    }
    fun recordPlaybackStarted(id: String) {
        _state.value = _state.value.copy(playCounts = repository.recordPlaybackStarted(id))
    }

    private fun rebuildPreparedCatalog(
        showAdult: Boolean = _state.value.showAdultContent,
        showCinema: Boolean = _state.value.showCinemaContent,
    ) {
        val catalog = _state.value.catalog
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.Default) { CatalogOrganizer.prepare(catalog.entries, showAdult, showCinema) }
            val current = _state.value
            if (current.catalog === catalog && current.showAdultContent == showAdult && current.showCinemaContent == showCinema) {
                _state.value = current.copy(preparedCatalog = prepared)
            }
        }
    }
    fun refreshCatalog() { _state.value.source?.let { connect(it, force = true) } }
    fun saveProgress(media: MediaEntry, position: Long, duration: Long) {
        repository.saveProgress(media, position, duration)
        _state.value = _state.value.copy(history = repository.history())
        viewModelScope.launch(Dispatchers.IO) { repository.mirrorProgress(media.id, position, duration) }
    }
    fun dismissError() { _state.value = _state.value.copy(error = null) }
    fun disconnect() { loadGeneration++; connectJob?.cancel(); refreshJob?.cancel(); epgRequested = false; repository.clearSource(); _state.value = AppState(
        favorites = repository.favorites(), history = repository.history(), refreshInterval = repository.refreshInterval(),
        showAdultContent = repository.showAdultContent(), showCinemaContent = repository.showCinemaContent(),
        contentSort = repository.contentSort(), playCounts = repository.playCounts(),
    ) }

    private fun friendlyLoadError(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "O servidor demorou demais para responder. Confira a conexão e tente novamente."
        is java.net.ConnectException, is java.net.UnknownHostException -> "Não foi possível conectar ao servidor. Confira o endereço e a conexão da TV."
        else -> error.message?.let(CredentialSanitizer::sanitize)?.takeIf { it.isNotBlank() }
            ?: "Não foi possível carregar a biblioteca. Confira os dados de acesso e tente novamente."
    }
}
