package com.droplay.tv.data

import java.security.MessageDigest

object M3uParser {
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parse(content: String): List<MediaEntry> {
        val lines = content.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val result = mutableListOf<MediaEntry>()
        var metadata: String? = null
        for (line in lines) {
            if (line.startsWith("#EXTINF", true)) {
                metadata = line
            } else if (!line.startsWith("#") && metadata != null) {
                val meta = metadata!!
                val attrs = attribute.findAll(meta).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                val name = meta.substringAfter(',', attrs["tvg-name"] ?: "Sem título").trim()
                val group = attrs["group-title"].orEmpty().ifBlank { "Outros" }
                val probe = "$group $name $line".lowercase()
                val kind = when {
                    listOf("série", "series", "temporada", "season").any(probe::contains) -> MediaKind.SERIES
                    listOf("filme", "movie", "vod", ".mp4", ".mkv", ".avi").any(probe::contains) -> MediaKind.MOVIE
                    else -> MediaKind.LIVE
                }
                result += MediaEntry(
                    id = digest(line), name = name, url = line, kind = kind, group = group,
                    logo = attrs["tvg-logo"], epgId = attrs["tvg-id"] ?: attrs["tvg-name"],
                )
                metadata = null
            }
        }
        return result.distinctBy { it.id }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
}
