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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update

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
    private var prepareJob: Job? = null
    private var maintenanceJob: Job? = null
    private var catalogRevision = 0
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
        prepareJob?.cancel()
        maintenanceJob?.cancel()
        val generation = ++loadGeneration
        _state.update { it.copy(loading = true, loadingMessage = "Abrindo biblioteca salva…", error = null) }
        connectJob = viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { repository.cached(source) }
                if (generation != loadGeneration) return@launch
                if (cached != null) {
                    publishCatalog(source, generation, cached)
                    _state.update { it.copy(syncStates = SyncPolicy.catalogSections.associateWith { SectionSyncState(SyncPhase.UsingCache) }) }
                } else {
                    _state.update { it.copy(loadingMessage = "Conectando ao servidor…") }
                    val catalog = withContext(Dispatchers.IO) {
                        repository.load(source, force = true, refreshAll = force,
                            progress = { step -> if (generation == loadGeneration) _state.update { it.copy(loadingMessage = step) } },
                            sectionState = { section, phase, message -> updateSectionState(generation, section, phase, message) })
                    }
                    publishCatalog(source, generation, catalog)
                }
                if (generation != loadGeneration) return@launch
                refreshCatalogInBackground(source, generation, force && cached != null)
                maintenanceJob = viewModelScope.launch {
                    // Never compete with the first frame. Reclassification only visits outdated rows.
                    delay(5_000)
                    val revision = catalogRevision
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            repository.ensureRoomCache(source, _state.value.catalog.entries)
                            if (repository.reclassifyIfNeeded(source) != null) repository.cached(source) else null
                        }
                        if (updated != null && generation == loadGeneration && revision == catalogRevision) {
                            publishCatalog(source, generation, updated)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The saved catalog stays usable; the next launch can retry maintenance.
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == loadGeneration) _state.update { it.copy(loading = false, error = friendlyLoadError(error)) }
            }
        }
    }

    /** Publish a usable catalog first, then replace its derived indexes once per revision. */
    private suspend fun publishCatalog(source: PlaylistSource, generation: Int, catalog: Catalog) {
        if (generation != loadGeneration) return
        prepareJob?.cancel()
        val revision = ++catalogRevision
        val preferences = _state.value
        val initial = if (preferences.source == source && preferences.preparedCatalog.entries.isNotEmpty()) {
            // Keep the current tabs and focus while a refresh builds replacement indexes.
            preferences.preparedCatalog
        } else withContext(Dispatchers.Default) {
            CatalogOrganizer.prepareInitial(catalog.entries, preferences.showAdultContent, preferences.showCinemaContent)
        }
        if (generation != loadGeneration || revision != catalogRevision) return
        if (preferences.showAdultContent != _state.value.showAdultContent || preferences.showCinemaContent != _state.value.showCinemaContent) {
            publishCatalog(source, generation, catalog)
            return
        }
        _state.update { it.copy(source = source, catalog = catalog, preparedCatalog = initial,
            loading = false, lastRefreshMs = repository.lastRefresh(), error = null) }
        rebuildPreparedCatalog()
    }

    private fun refreshCatalogInBackground(source: PlaylistSource, generation: Int, immediate: Boolean) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            var forceAll = immediate
            while (isActive && generation == loadGeneration) {
                try {
                    // Pick up work committed by WorkManager while the application was in the background.
                    if (repository.lastRefresh() != _state.value.lastRefreshMs) {
                        withContext(Dispatchers.IO) { repository.cached(source) }?.let {
                            publishCatalog(source, generation, it)
                        }
                    }
                    if (forceAll || withContext(Dispatchers.IO) { repository.isRefreshDue(source) }) {
                        val catalog = withContext(Dispatchers.IO) {
                            repository.load(source, force = true, refreshAll = forceAll,
                                sectionState = { section, phase, message -> updateSectionState(generation, section, phase, message) })
                        }
                        publishCatalog(source, generation, catalog)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (generation == loadGeneration) _state.update { it.copy(error = friendlyLoadError(error)) }
                }
                forceAll = false
                delay(60_000)
            }
        }
    }

    private fun updateSectionState(generation: Int, section: CatalogSection, phase: SyncPhase, message: String?) {
        if (generation != loadGeneration) return
        _state.update { state ->
            val current = state.syncStates[section] ?: SectionSyncState()
            state.copy(syncStates = state.syncStates + (section to current.copy(
                phase = phase, message = message,
                lastSuccessfulSyncAt = if (phase == SyncPhase.Success) System.currentTimeMillis() else current.lastSuccessfulSyncAt,
            )))
        }
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
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                CatalogOrganizer.prepare(catalog.entries, showAdult, showCinema) { context.ensureActive() }
            }
            val current = _state.value
            if (current.catalog === catalog && current.showAdultContent == showAdult && current.showCinemaContent == showCinema) {
                _state.value = current.copy(preparedCatalog = prepared)
            }
        }
    }
    fun refreshCatalog() { _state.value.source?.let { refreshCatalogInBackground(it, loadGeneration, immediate = true) } }
    fun saveProgress(media: MediaEntry, position: Long, duration: Long) {
        repository.saveProgress(media, position, duration)
        _state.value = _state.value.copy(history = repository.history())
        viewModelScope.launch(Dispatchers.IO) { repository.mirrorProgress(media.id, position, duration) }
    }
    fun dismissError() { _state.value = _state.value.copy(error = null) }
    fun disconnect() { loadGeneration++; connectJob?.cancel(); refreshJob?.cancel(); prepareJob?.cancel(); maintenanceJob?.cancel(); epgRequested = false; repository.clearSource(); _state.value = AppState(
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
