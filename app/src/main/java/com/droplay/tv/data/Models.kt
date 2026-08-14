package com.droplay.tv.data

enum class MediaKind { LIVE, MOVIE, SERIES }

data class MediaEntry(
    val id: String,
    val name: String,
    val url: String,
    val kind: MediaKind,
    val group: String = "Outros",
    val logo: String? = null,
    val epgId: String? = null,
    val description: String? = null,
    val backdrop: String? = null,
    val seriesId: String? = null,
    val parentSeriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class EpgProgram(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val description: String = "",
)

sealed interface PlaylistSource {
    data class M3u(val url: String) : PlaylistSource
    data class Xtream(val server: String, val username: String, val password: String) : PlaylistSource
}

data class WatchRecord(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
    val name: String = "",
    val url: String = "",
    val kind: MediaKind = MediaKind.MOVIE,
    val group: String = "",
    val logo: String? = null,
    val parentSeriesId: String? = null,
) {
    fun asMediaEntry() = MediaEntry(
        id = mediaId, name = name.ifBlank { "Conteúdo recente" }, url = url,
        kind = kind, group = group, logo = logo, parentSeriesId = parentSeriesId,
    )
}

data class Catalog(
    val entries: List<MediaEntry> = emptyList(),
    val epg: Map<String, EpgProgram> = emptyMap(),
)
