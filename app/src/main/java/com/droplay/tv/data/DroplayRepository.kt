package com.droplay.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DroplayRepository(context: Context) {
    private val prefs = context.getSharedPreferences("droplay_local", Context.MODE_PRIVATE)

    fun savedSource(): PlaylistSource? = when (prefs.getString("source_type", null)) {
        "m3u" -> prefs.getString("m3u_url", null)?.let(PlaylistSource::M3u)
        "xtream" -> PlaylistSource.Xtream(
            prefs.getString("server", "").orEmpty(), prefs.getString("username", "").orEmpty(), prefs.getString("password", "").orEmpty()
        ).takeIf { it.server.isNotBlank() && it.username.isNotBlank() }
        else -> null
    }

    fun load(source: PlaylistSource, save: Boolean = true): Catalog {
        val entries: List<MediaEntry>
        val epgUrl: String?
        when (source) {
            is PlaylistSource.M3u -> {
                val body = Network.text(source.url)
                require(body.lineSequence().firstOrNull()?.trim()?.startsWith("#EXTM3U", true) == true) { "A URL não retornou uma lista M3U válida." }
                entries = M3uParser.parse(body)
                epgUrl = Regex("(?:x-tvg-url|url-tvg)=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                    .find(body.lineSequence().first())?.groupValues?.getOrNull(1)
            }
            is PlaylistSource.Xtream -> {
                entries = XtreamClient(source).load()
                val base = source.server.trim().trimEnd('/')
                epgUrl = "$base/xmltv.php?username=${source.username}&password=${source.password}"
            }
        }
        require(entries.isNotEmpty()) { "Nenhum item reproduzível foi encontrado." }
        if (save) saveSource(source)
        val epg = epgUrl?.let { runCatching { EpgParser.parse(it) }.getOrDefault(emptyMap()) }.orEmpty()
        return Catalog(entries, epg)
    }

    fun loadEpisodes(source: PlaylistSource, seriesId: String) =
        if (source is PlaylistSource.Xtream) XtreamClient(source).episodes(seriesId) else emptyList()

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet().orEmpty()
    fun toggleFavorite(id: String): Set<String> {
        val changed = favorites().toMutableSet().apply { if (!add(id)) remove(id) }
        prefs.edit().putStringSet("favorites", changed).apply()
        return changed
    }

    fun history(): List<WatchRecord> {
        val json = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrDefault(JSONArray())
        return (0 until json.length()).mapNotNull { i ->
            runCatching { json.getJSONObject(i) }.getOrNull()?.let {
                WatchRecord(
                    it.optString("id"), it.optLong("position"), it.optLong("duration"), it.optLong("at"),
                    it.optString("name"), it.optString("url"),
                    runCatching { MediaKind.valueOf(it.optString("kind", MediaKind.MOVIE.name)) }.getOrDefault(MediaKind.MOVIE),
                    it.optString("group"), it.optString("logo").takeIf(String::isNotBlank),
                    it.optString("parentSeriesId").takeIf(String::isNotBlank),
                )
            }
        }
    }

    fun saveProgress(media: MediaEntry, positionMs: Long, durationMs: Long) {
        if (media.kind == MediaKind.LIVE || media.id.isBlank() || positionMs < 2_000) return
        val records = history().filterNot { it.mediaId == media.id }.toMutableList()
        records.add(0, WatchRecord(media.id, positionMs, durationMs, System.currentTimeMillis(), media.name, media.url,
            media.kind, media.group, media.logo, media.parentSeriesId))
        val json = JSONArray()
        records.take(60).forEach { json.put(JSONObject().put("id", it.mediaId).put("position", it.positionMs)
            .put("duration", it.durationMs).put("at", it.watchedAt).put("name", it.name).put("url", it.url)
            .put("kind", it.kind.name).put("group", it.group).put("logo", it.logo ?: "")
            .put("parentSeriesId", it.parentSeriesId ?: "")) }
        prefs.edit().putString("history", json.toString()).apply()
    }

    fun clearSource() = prefs.edit().remove("source_type").remove("m3u_url").remove("server").remove("username").remove("password").apply()

    private fun saveSource(source: PlaylistSource) = prefs.edit().apply {
        when (source) {
            is PlaylistSource.M3u -> putString("source_type", "m3u").putString("m3u_url", source.url)
            is PlaylistSource.Xtream -> putString("source_type", "xtream").putString("server", source.server)
                .putString("username", source.username).putString("password", source.password)
        }
    }.apply()
}
