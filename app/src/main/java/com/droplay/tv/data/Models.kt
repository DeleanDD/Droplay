package com.droplay.tv.data

enum class MediaKind { LIVE, MOVIE, SERIES }

enum class RefreshInterval(val durationMs: Long) {
    EVERY_LAUNCH(0L),
    DAILY(24L * 60 * 60 * 1000),
    WEEKLY(7L * 24 * 60 * 60 * 1000),
    MONTHLY(30L * 24 * 60 * 60 * 1000),
}

enum class ContentSort { YEAR_DESC, ALPHABETICAL, MOST_WATCHED }

data class SubtitleTrack(
    val url: String,
    val label: String? = null,
    val language: String? = null,
)

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
    val year: Int? = null,
    val addedAt: Long = 0L,
    val durationMs: Long = 0L,
    val subtitles: List<SubtitleTrack> = emptyList(),
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
    val subtitles: List<SubtitleTrack> = emptyList(),
) {
    fun asMediaEntry() = MediaEntry(
        id = mediaId, name = name.ifBlank { "Conteúdo recente" }, url = url,
        kind = kind, group = group, logo = logo, parentSeriesId = parentSeriesId, subtitles = subtitles,
    )
}

data class Catalog(
    val entries: List<MediaEntry> = emptyList(),
    val epg: Map<String, EpgProgram> = emptyMap(),
)
