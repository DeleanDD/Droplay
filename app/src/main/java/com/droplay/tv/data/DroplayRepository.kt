package com.droplay.tv.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class DroplayRepository(context: Context) {
    private val prefs = context.getSharedPreferences("droplay_local", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(context.filesDir, "catalog-v1.json"))

    fun savedSource(): PlaylistSource? = when (prefs.getString("source_type", null)) {
        "m3u" -> prefs.getString("m3u_url", null)?.let(PlaylistSource::M3u)
        "xtream" -> PlaylistSource.Xtream(
            prefs.getString("server", "").orEmpty(), prefs.getString("username", "").orEmpty(), prefs.getString("password", "").orEmpty()
        ).takeIf { it.server.isNotBlank() && it.username.isNotBlank() }
        else -> null
    }

    fun refreshInterval(): RefreshInterval = runCatching {
        RefreshInterval.valueOf(prefs.getString("refresh_interval", RefreshInterval.DAILY.name).orEmpty())
    }.getOrDefault(RefreshInterval.DAILY)

    fun setRefreshInterval(interval: RefreshInterval) {
        prefs.edit().putString("refresh_interval", interval.name).apply()
    }

    fun lastRefresh(): Long = prefs.getLong("last_catalog_refresh", 0L)

    fun load(source: PlaylistSource, save: Boolean = true, force: Boolean = false): Catalog {
        if (!force) cachedCatalog(source, requireFresh = true)?.let { return it }

        return try {
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
            prefs.edit().putString("epg_url", epgUrl).apply()
            saveCatalog(source, entries)
            Catalog(entries)
        } catch (error: Throwable) {
            if (force) throw error
            cachedCatalog(source, requireFresh = false) ?: throw error
        }
    }

    fun refreshEpg(): Map<String, EpgProgram> = prefs.getString("epg_url", null)
        ?.takeIf(String::isNotBlank)
        ?.let { EpgParser.parse(it) }
        .orEmpty()

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

    fun clearSource() {
        prefs.edit().remove("source_type").remove("m3u_url").remove("server").remove("username")
            .remove("password").remove("epg_url").remove("last_catalog_refresh").remove("catalog_source_key").apply()
        cache.delete()
    }

    private fun cachedCatalog(source: PlaylistSource, requireFresh: Boolean): Catalog? {
        val interval = refreshInterval()
        val age = System.currentTimeMillis() - lastRefresh()
        val sourceMatches = prefs.getString("catalog_source_key", null) == sourceKey(source)
        if (!sourceMatches) return null
        if (requireFresh && (interval == RefreshInterval.EVERY_LAUNCH || age < 0 || age >= interval.durationMs)) return null
        return runCatching {
            val root = cache.openRead().bufferedReader().use { JSONObject(it.readText()) }
            val array = root.getJSONArray("entries")
            Catalog((0 until array.length()).map { entryFromJson(array.getJSONObject(it)) })
        }.getOrNull()?.takeIf { it.entries.isNotEmpty() }
    }

    private fun saveCatalog(source: PlaylistSource, entries: List<MediaEntry>) {
        val root = JSONObject().put("version", 1).put("entries", JSONArray().apply { entries.forEach { put(entryToJson(it)) } })
        var stream: FileOutputStream? = null
        try {
            stream = cache.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            cache.finishWrite(stream)
            val now = System.currentTimeMillis()
            prefs.edit().putLong("last_catalog_refresh", now).putString("catalog_source_key", sourceKey(source)).apply()
        } catch (error: Throwable) {
            stream?.let(cache::failWrite)
            throw error
        }
    }

    private fun entryToJson(item: MediaEntry) = JSONObject()
        .put("id", item.id).put("name", item.name).put("url", item.url).put("kind", item.kind.name)
        .put("group", item.group).put("logo", item.logo).put("epgId", item.epgId)
        .put("description", item.description).put("backdrop", item.backdrop).put("seriesId", item.seriesId)
        .put("parentSeriesId", item.parentSeriesId).put("season", item.season).put("episode", item.episode)

    private fun entryFromJson(json: JSONObject) = MediaEntry(
        id = json.getString("id"), name = json.getString("name"), url = json.getString("url"),
        kind = runCatching { MediaKind.valueOf(json.getString("kind")) }.getOrDefault(MediaKind.MOVIE),
        group = json.optString("group", "Outros"), logo = json.textOrNull("logo"), epgId = json.textOrNull("epgId"),
        description = json.textOrNull("description"), backdrop = json.textOrNull("backdrop"),
        seriesId = json.textOrNull("seriesId"), parentSeriesId = json.textOrNull("parentSeriesId"),
        season = json.optInt("season").takeIf { json.has("season") && !json.isNull("season") },
        episode = json.optInt("episode").takeIf { json.has("episode") && !json.isNull("episode") },
    )

    private fun JSONObject.textOrNull(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }

    private fun sourceKey(source: PlaylistSource): String {
        val raw = when (source) {
            is PlaylistSource.M3u -> "m3u:${source.url}"
            is PlaylistSource.Xtream -> "xtream:${source.server}|${source.username}|${source.password}"
        }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun saveSource(source: PlaylistSource) = prefs.edit().apply {
        when (source) {
            is PlaylistSource.M3u -> putString("source_type", "m3u").putString("m3u_url", source.url)
            is PlaylistSource.Xtream -> putString("source_type", "xtream").putString("server", source.server)
                .putString("username", source.username).putString("password", source.password)
        }
    }.apply()
}
