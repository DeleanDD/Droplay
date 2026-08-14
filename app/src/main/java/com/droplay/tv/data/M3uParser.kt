package com.droplay.tv.data

import java.io.Reader
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class M3uCatalogResult(val entries: List<MediaEntry>, val epgUrl: String?)

object M3uParser {
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val episodePattern = Regex("(?i)(?:^|\\s)S(\\d{1,3})E(\\d{1,4})(?=\\s|$|[-_.])")
    private val decorations = Regex("[\\p{So}\\p{Co}\\uFE0F\\u200B\\u200D]")

    fun parse(content: String): List<MediaEntry> = parseCatalog(content.reader()).entries

    fun parseCatalog(reader: Reader): M3uCatalogResult {
        val entries = mutableListOf<MediaEntry>()
        val series = LinkedHashMap<String, MediaEntry>()
        var metadata: String? = null
        var epgUrl: String? = null
        reader.buffered().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXTM3U", true) -> epgUrl = headerEpgUrl(line)
                    line.startsWith("#EXTINF", true) -> metadata = line
                    line.isNotBlank() && !line.startsWith("#") && metadata != null -> {
                        val meta = metadata!!
                        metadata = null
                        val quickName = cleanText(meta.substringAfterMetadataComma(""))
                        val episode = episodeParts(quickName)
                        if (episode == null) {
                            entries += parseLine(meta, line)
                        } else {
                            val title = seriesTitle(quickName)
                            val key = seriesKey(title)
                            if (key !in series) {
                                val parsed = parseLine(meta, line)
                                series[key] = MediaEntry(
                                    id = "m3u-series:$key", name = title, url = "", kind = MediaKind.SERIES,
                                    group = parsed.group, logo = parsed.logo, description = parsed.description,
                                    seriesId = key, year = parsed.year,
                                )
                            }
                        }
                    }
                }
            }
        }
        entries += series.values
        return M3uCatalogResult(entries.distinctBy(MediaEntry::id), epgUrl)
    }

    fun episodes(reader: Reader, seriesId: String): List<MediaEntry> {
        val result = mutableListOf<MediaEntry>()
        var metadata: String? = null
        reader.buffered().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF", true) -> metadata = line
                    line.isNotBlank() && !line.startsWith("#") && metadata != null -> {
                        val meta = metadata!!
                        metadata = null
                        val quickName = cleanText(meta.substringAfterMetadataComma(""))
                        val parts = episodeParts(quickName) ?: return@forEach
                        if (seriesKey(seriesTitle(quickName)) == seriesId) {
                            val parsed = parseLine(meta, line)
                            result += parsed.copy(
                                name = "Episódio ${parts.second}", kind = MediaKind.MOVIE,
                                group = "Temporada ${parts.first}", parentSeriesId = seriesId,
                                season = parts.first, episode = parts.second,
                            )
                        }
                    }
                }
            }
        }
        return result.distinctBy(MediaEntry::id).sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    }

    private fun parseLine(metadata: String, url: String): MediaEntry {
        val attrs = attribute.findAll(metadata).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val fallback = attrs["tvg-name"].orEmpty().ifBlank { "Sem título" }
        val name = cleanText(metadata.substringAfterMetadataComma(fallback)).ifBlank { cleanText(fallback) }
        val group = cleanText(attrs["group-title"].orEmpty()).ifBlank { "Outros" }
        val probe = "$group $name $url".lowercase(Locale.ROOT)
        val kind = when {
            episodeParts(name) != null -> MediaKind.SERIES
            listOf("filme", "movie", "vod", "série", "serie", "series", ".mp4", ".mkv", ".avi").any(probe::contains) -> MediaKind.MOVIE
            else -> MediaKind.LIVE
        }
        return MediaEntry(
            id = digest(url), name = name, url = url, kind = kind, group = group,
            logo = attrs["tvg-logo"], epgId = attrs["tvg-id"] ?: attrs["tvg-name"],
            description = attrs["tvg-description"] ?: attrs["description"],
            year = Regex("(?:19|20)\\d{2}").findAll(name).lastOrNull()?.value?.toIntOrNull(),
            durationMs = (attrs["tvg-duration"] ?: attrs["duration"])?.toLongOrNull()?.times(1_000L) ?: 0L,
            subtitles = listOfNotNull(attrs["tvg-subtitle"] ?: attrs["subtitle"] ?: attrs["sub-file"])
                .filter { it.isNotBlank() }.map { SubtitleTrack(it) },
        )
    }

    private fun String.substringAfterMetadataComma(fallback: String): String {
        var quoted = false
        for (index in indices) {
            when (this[index]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return substring(index + 1).trim()
            }
        }
        return fallback
    }

    private fun headerEpgUrl(header: String): String? =
        Regex("(?:x-tvg-url|url-tvg)=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(header)?.groupValues?.getOrNull(1)

    private fun episodeParts(name: String): Pair<Int, Int>? = episodePattern.find(name)?.let {
        it.groupValues[1].toIntOrNull()?.let { season -> it.groupValues[2].toIntOrNull()?.let { episode -> season to episode } }
    }

    private fun seriesTitle(name: String): String = cleanText(name.replace(episodePattern, " ")).trim(' ', '-', '.', '_')

    private fun seriesKey(title: String): String = digest(Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("\\s+"), " ").trim())

    internal fun cleanText(value: String): String = value.replace(decorations, " ")
        .replace(Regex("\\s+"), " ").trim(' ', '-', '|', '•', '❖', '➤')

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
}
