package com.droplay.tv.subtitles

import com.droplay.tv.data.MediaEntry

data class OpenSubtitlesConfiguration(
    val apiKey: String = "",
    val userAgent: String = "DROPLAY v1.2.17",
    val username: String = "",
    val token: String = "",
    val baseUrl: String = DEFAULT_API_URL,
) {
    val canSearch get() = apiKey.isNotBlank() && userAgent.isNotBlank()
    val canDownload get() = canSearch && token.isNotBlank()

    companion object { const val DEFAULT_API_URL = "https://api.opensubtitles.com/api/v1" }
}

data class SubtitleSearchRequest(val parameters: Map<String, String>, val approximate: Boolean)

object SubtitleSearchQueryFactory {
    fun create(media: MediaEntry): SubtitleSearchRequest {
        val episode = media.parentSeriesId != null && media.season != null && media.episode != null
        val params = linkedMapOf<String, String>()
        if (episode) {
            params["type"] = "episode"
            media.tmdbId?.takeIf { it > 0 }?.let { params["parent_tmdb_id"] = it.toString() }
            params["season_number"] = media.season.toString()
            params["episode_number"] = media.episode.toString()
            if (media.tmdbId == null) {
                params["query"] = media.parentTitle?.takeIf(String::isNotBlank) ?: media.name
                media.year?.let { params["year"] = it.toString() }
            }
        } else {
            params["type"] = "movie"
            media.tmdbId?.takeIf { it > 0 }?.let { params["tmdb_id"] = it.toString() }
            if (media.tmdbId == null) {
                params["query"] = media.name.cleanedTitle()
                media.year?.let { params["year"] = it.toString() }
            }
        }
        return SubtitleSearchRequest(params, media.tmdbId == null)
    }

    private fun String.cleanedTitle() = replace(Regex("(?i)\\s*\\[(?:L|LEG|DUB)\\]\\s*"), " ")
        .replace(Regex("\\s+"), " ").trim()
}

data class SubtitleCandidate(
    val fileId: Int,
    val language: String,
    val release: String,
    val fileName: String,
    val rating: Double,
    val downloads: Int,
    val trusted: Boolean,
    val movieHashMatch: Boolean,
    val hearingImpaired: Boolean,
    val aiTranslated: Boolean,
    val machineTranslated: Boolean,
    val approximate: Boolean = false,
)

data class SubtitlePage(val page: Int, val totalPages: Int, val candidates: List<SubtitleCandidate>)

object SubtitleRanking {
    fun normalizeLanguage(value: String): String = when (value.lowercase().replace('_', '-')) {
        "pob", "por-br", "pt-br" -> "pt-br"
        "por", "pt", "pt-pt" -> "pt-pt"
        else -> value.lowercase().replace('_', '-')
    }

    fun languageLabel(code: String): String = when (normalizeLanguage(code)) {
        "pt-br" -> "Português (Brasil)"
        "pt-pt" -> "Português"
        "en" -> "Inglês"
        "es" -> "Espanhol"
        "fr" -> "Francês"
        "de" -> "Alemão"
        "it" -> "Italiano"
        else -> code.uppercase()
    }

    fun rank(items: List<SubtitleCandidate>, preferredLanguage: String, releaseHint: String = ""): List<SubtitleCandidate> {
        val preferred = normalizeLanguage(preferredLanguage)
        val hintTokens = releaseHint.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }.toSet()
        return items.distinctBy { it.fileId }.sortedWith(
            compareByDescending<SubtitleCandidate> { normalizeLanguage(it.language) == preferred }
                .thenByDescending { it.movieHashMatch }
                .thenByDescending { it.trusted }
                .thenByDescending { releaseScore(it.release, hintTokens) }
                .thenByDescending { it.rating }
                .thenByDescending { it.downloads }
                .thenBy { it.aiTranslated || it.machineTranslated }
                .thenBy { it.hearingImpaired }
                .thenBy { it.fileId }
        )
    }

    fun group(items: List<SubtitleCandidate>, preferredLanguage: String): Map<String, List<SubtitleCandidate>> =
        rank(items, preferredLanguage).groupBy { normalizeLanguage(it.language) }

    private fun releaseScore(release: String, tokens: Set<String>): Int {
        if (tokens.isEmpty()) return 0
        val releaseTokens = release.lowercase().split(Regex("[^a-z0-9]+" )).toSet()
        return tokens.count(releaseTokens::contains)
    }
}

sealed interface SubtitleUiState {
    data object Idle : SubtitleUiState
    data class Searching(val page: Int, val totalPages: Int?) : SubtitleUiState
    data class Results(val groups: Map<String, List<SubtitleCandidate>>, val approximate: Boolean) : SubtitleUiState
    data class Downloading(val candidate: SubtitleCandidate) : SubtitleUiState
    data class Ready(val candidate: SubtitleCandidate, val localUrl: String) : SubtitleUiState
    data class Error(val message: String) : SubtitleUiState
}

class OpenSubtitlesException(val status: Int, message: String, val retryAfterSeconds: Long? = null) : Exception(message)

fun friendlySubtitleError(error: Throwable): String = when (error) {
    is OpenSubtitlesException -> when (error.status) {
        401 -> "A sessão do OpenSubtitles expirou. Entre novamente nas configurações."
        403 -> "A chave ou a conta do OpenSubtitles não autorizou esta operação."
        406 -> "O limite diário de downloads do OpenSubtitles foi atingido."
        429 -> "Muitas solicitações ao OpenSubtitles. Tente novamente${error.retryAfterSeconds?.let { " em $it segundos" } ?: " mais tarde"}."
        else -> "OpenSubtitles respondeu com erro ${error.status}."
    }
    is java.net.SocketTimeoutException -> "O OpenSubtitles demorou demais para responder."
    is java.net.UnknownHostException, is java.net.ConnectException -> "Sem conexão com o OpenSubtitles."
    is kotlinx.coroutines.CancellationException -> "Operação cancelada."
    else -> error.message?.takeIf(String::isNotBlank) ?: "Não foi possível carregar as legendas."
}
