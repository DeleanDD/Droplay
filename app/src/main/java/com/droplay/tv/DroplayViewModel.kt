package com.droplay.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droplay.tv.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppState(
    val source: PlaylistSource? = null,
    val catalog: Catalog = Catalog(),
    val favorites: Set<String> = emptySet(),
    val history: List<WatchRecord> = emptyList(),
    val refreshInterval: RefreshInterval = RefreshInterval.DAILY,
    val lastRefreshMs: Long = 0L,
    val showAdultContent: Boolean = false,
    val showCinemaContent: Boolean = false,
    val contentSort: ContentSort = ContentSort.YEAR_DESC,
    val playCounts: Map<String, Int> = emptyMap(),
    val preparedCatalog: PreparedCatalog = PreparedCatalog(),
    val loading: Boolean = false,
    val loadingMessage: String = "Abrindo sua biblioteca…",
    val error: String? = null,
)

class DroplayViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DroplayRepository(application)
    private val _state = MutableStateFlow(AppState(
        favorites = repository.favorites(), history = repository.history(),
        refreshInterval = repository.refreshInterval(), lastRefreshMs = repository.lastRefresh(),
        showAdultContent = repository.showAdultContent(), showCinemaContent = repository.showCinemaContent(),
        contentSort = repository.contentSort(), playCounts = repository.playCounts(),
    ))
    val state = _state.asStateFlow()

    init { repository.savedSource()?.let { connect(it) } }

    fun connect(source: PlaylistSource, force: Boolean = false) {
        val message = if (force || repository.isRefreshDue(source)) "Atualizando sua biblioteca…" else "Abrindo biblioteca salva…"
        _state.value = _state.value.copy(loading = true, loadingMessage = message, error = null)
        viewModelScope.launch {
            val preferences = _state.value
            runCatching { withContext(Dispatchers.IO) {
                val catalog = repository.load(source, force = force)
                catalog to CatalogOrganizer.prepare(catalog.entries, preferences.showAdultContent, preferences.showCinemaContent)
            } }.onSuccess { (catalog, prepared) ->
                    _state.value = _state.value.copy(source = source, catalog = catalog, preparedCatalog = prepared,
                        loading = false, lastRefreshMs = repository.lastRefresh())
                    refreshEpgInBackground(source)
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Falha ao carregar a lista.") }
        }
    }

    private fun refreshEpgInBackground(source: PlaylistSource) {
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

    fun toggleFavorite(id: String) { _state.value = _state.value.copy(favorites = repository.toggleFavorite(id)) }
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
    }
    fun dismissError() { _state.value = _state.value.copy(error = null) }
    fun disconnect() { repository.clearSource(); _state.value = AppState(
        favorites = repository.favorites(), history = repository.history(), refreshInterval = repository.refreshInterval(),
        showAdultContent = repository.showAdultContent(), showCinemaContent = repository.showCinemaContent(),
        contentSort = repository.contentSort(), playCounts = repository.playCounts(),
    ) }
}
