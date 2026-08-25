package com.droplay.tv.subtitles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droplay.tv.data.MediaEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubtitleViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SubtitleRepository(application)
    private val _state = MutableStateFlow<SubtitleUiState>(SubtitleUiState.Idle)
    val state = _state.asStateFlow()
    private var searchJob: Job? = null
    private var downloadJob: Job? = null
    private var mediaId: String? = null
    private var lastGroups: Map<String, List<SubtitleCandidate>> = emptyMap()
    private var lastApproximate = false

    fun search(media: MediaEntry, force: Boolean = false) {
        if (!force && mediaId == media.id && (_state.value is SubtitleUiState.Searching || _state.value is SubtitleUiState.Results)) return
        mediaId = media.id
        searchJob?.cancel()
        downloadJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = SubtitleUiState.Searching(0, null)
            runCatching {
                repository.search(media) { page, total -> _state.value = SubtitleUiState.Searching(page, total) }
            }.onSuccess { (items, approximate) ->
                val groups = SubtitleRanking.group(items, repository.settings.appearance().preferredLanguage)
                lastGroups = groups
                lastApproximate = approximate
                _state.value = SubtitleUiState.Results(groups, approximate)
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) _state.value = SubtitleUiState.Error(friendlySubtitleError(error))
            }
        }
    }

    fun download(candidate: SubtitleCandidate, delayMs: Long) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.value = SubtitleUiState.Downloading(candidate)
            runCatching { repository.download(candidate, delayMs) }
                .onSuccess { _state.value = SubtitleUiState.Ready(candidate, it) }
                .onFailure { error -> if (error !is kotlinx.coroutines.CancellationException) _state.value = SubtitleUiState.Error(friendlySubtitleError(error)) }
        }
    }

    fun retime(candidate: SubtitleCandidate, delayMs: Long) {
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) { repository.prepareCached(candidate, delayMs) }
            if (url != null) _state.value = SubtitleUiState.Ready(candidate, url)
        }
    }

    fun consumeReady() {
        val current = _state.value
        if (current is SubtitleUiState.Ready) _state.value = SubtitleUiState.Results(
            lastGroups.ifEmpty { mapOf(SubtitleRanking.normalizeLanguage(current.candidate.language) to listOf(current.candidate)) }, lastApproximate)
    }

    override fun onCleared() { searchJob?.cancel(); downloadJob?.cancel() }
}
