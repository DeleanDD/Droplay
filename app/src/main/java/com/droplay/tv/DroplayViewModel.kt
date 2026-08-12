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
    val loading: Boolean = false,
    val error: String? = null,
)

class DroplayViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DroplayRepository(application)
    private val _state = MutableStateFlow(AppState(favorites = repository.favorites(), history = repository.history()))
    val state = _state.asStateFlow()

    init { repository.savedSource()?.let(::connect) }

    fun connect(source: PlaylistSource) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.load(source) } }
                .onSuccess { _state.value = _state.value.copy(source = source, catalog = it, loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Falha ao carregar a lista.") }
        }
    }

    suspend fun episodes(seriesId: String): List<MediaEntry> {
        val source = _state.value.source ?: return emptyList()
        return withContext(Dispatchers.IO) { repository.loadEpisodes(source, seriesId) }
    }

    fun toggleFavorite(id: String) { _state.value = _state.value.copy(favorites = repository.toggleFavorite(id)) }
    fun saveProgress(id: String, position: Long, duration: Long) {
        repository.saveProgress(id, position, duration)
        _state.value = _state.value.copy(history = repository.history())
    }
    fun dismissError() { _state.value = _state.value.copy(error = null) }
    fun disconnect() { repository.clearSource(); _state.value = AppState(favorites = repository.favorites(), history = repository.history()) }
}
