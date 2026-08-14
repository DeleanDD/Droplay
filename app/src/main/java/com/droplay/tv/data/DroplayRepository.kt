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
    private val cache = AtomicFile(File(context.filesDir, "catalog-v3.jsonl"))
    private val rawPlaylist = AtomicFile(File(context.filesDir, "playlist-v1.m3u"))

    init {
        File(context.filesDir, "catalog-v1.json").delete()
        File(context.filesDir, "catalog-v2.json").delete()
        // Migração executada uma única vez: impede que uma M3U gigante salva nas
        // versões antigas volte a bloquear a abertura. Novas M3U escolhidas
        // explicitamente pelo usuário continuam suportadas como fonte secundária.
        if (!prefs.getBoolean("legacy_m3u_migration_done", false)) {
            if (prefs.getString("source_type", null) == "m3u") {
                prefs.edit().remove("source_type").remove("m3u_url").remove("last_catalog_refresh")
                    .remove("catalog_source_key").apply()
                cache.delete()
                rawPlaylist.delete()
            }
            prefs.edit().putBoolean("legacy_m3u_migration_done", true).apply()
        }
    }

    fun savedSource(): PlaylistSource? = when (prefs.getString("source_type", null)) {
        "m3u" -> prefs.getString("m3u_url", null)?.takeIf(String::isNotBlank)?.let(PlaylistSource::M3u)
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

    fun isRefreshDue(source: PlaylistSource): Boolean {
        val interval = refreshInterval()
        val age = System.currentTimeMillis() - lastRefresh()
        val sourceMatches = prefs.getString("catalog_source_key", null) == sourceKey(source)
        return !sourceMatches || interval == RefreshInterval.EVERY_LAUNCH || age < 0 || age >= interval.durationMs || !cache.baseFile.exists()
    }

    fun showAdultContent(): Boolean = prefs.getBoolean("show_adult_content", false)
    fun setShowAdultContent(show: Boolean) { prefs.edit().putBoolean("show_adult_content", show).apply() }
    fun showCinemaContent(): Boolean = prefs.getBoolean("show_cinema_content", false)
    fun setShowCinemaContent(show: Boolean) { prefs.edit().putBoolean("show_cinema_content", show).apply() }
    fun contentSort(): ContentSort = runCatching {
        ContentSort.valueOf(prefs.getString("content_sort", ContentSort.YEAR_DESC.name).orEmpty())
    }.getOrDefault(ContentSort.YEAR_DESC)
    fun setContentSort(sort: ContentSort) { prefs.edit().putString("content_sort", sort.name).apply() }
    fun playCounts(): Map<String, Int> {
        val json = runCatching { JSONObject(prefs.getString("play_counts", "{}").orEmpty()) }.getOrDefault(JSONObject())
        return json.keys().asSequence().associateWith { json.optInt(it) }
    }
    fun recordPlaybackStarted(id: String): Map<String, Int> {
        val counts = playCounts().toMutableMap()
        counts[id] = (counts[id] ?: 0) + 1
        val json = JSONObject(); counts.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString("play_counts", json.toString()).apply()
        return counts
    }

    fun load(source: PlaylistSource, save: Boolean = true, force: Boolean = false): Catalog {
        if (!force) cachedCatalog(source, requireFresh = true)?.let { return it }

        return try {
            val entries: List<MediaEntry>
            val epgUrl: String?
            when (source) {
                is PlaylistSource.M3u -> {
                    downloadPlaylist(m3uPlusUrl(source.url))
                    val parsed = rawPlaylist.openRead().bufferedReader().use(M3uParser::parseCatalog)
                    entries = parsed.entries
                    epgUrl = parsed.epgUrl
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

    fun loadEpisodes(source: PlaylistSource, seriesId: String): List<MediaEntry> = when (source) {
        is PlaylistSource.Xtream -> XtreamClient(source).episodes(seriesId)
        is PlaylistSource.M3u -> runCatching { rawPlaylist.openRead().bufferedReader().use { M3uParser.episodes(it, seriesId) } }.getOrDefault(emptyList())
    }

    fun loadDetails(source: PlaylistSource, media: MediaEntry): MediaEntry =
        if (source is PlaylistSource.Xtream) XtreamClient(source).details(media) else media

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
        rawPlaylist.delete()
    }

    private fun cachedCatalog(source: PlaylistSource, requireFresh: Boolean): Catalog? {
        val interval = refreshInterval()
        val age = System.currentTimeMillis() - lastRefresh()
        val sourceMatches = prefs.getString("catalog_source_key", null) == sourceKey(source)
        if (!sourceMatches) return null
        if (requireFresh && (interval == RefreshInterval.EVERY_LAUNCH || age < 0 || age >= interval.durationMs)) return null
        return runCatching {
            val entries = ArrayList<MediaEntry>()
            cache.openRead().bufferedReader().use { reader ->
                require(reader.readLine() == "DROPLAY-CATALOG-3") { "Cache incompatível." }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) entries += entryFromJson(JSONObject(line))
                }
            }
            Catalog(entries)
        }.getOrNull()?.takeIf { it.entries.isNotEmpty() }
    }

    private fun saveCatalog(source: PlaylistSource, entries: List<MediaEntry>) {
        var stream: FileOutputStream? = null
        try {
            stream = cache.startWrite()
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            writer.appendLine("DROPLAY-CATALOG-3")
            entries.forEach { writer.appendLine(entryToJson(it).toString()) }
            writer.flush()
            cache.finishWrite(stream)
            stream = null
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
        .put("year", item.year).put("addedAt", item.addedAt).put("durationMs", item.durationMs)

    private fun entryFromJson(json: JSONObject) = MediaEntry(
        id = json.getString("id"), name = json.getString("name"), url = json.getString("url"),
        kind = runCatching { MediaKind.valueOf(json.getString("kind")) }.getOrDefault(MediaKind.MOVIE),
        group = json.optString("group", "Outros"), logo = json.textOrNull("logo"), epgId = json.textOrNull("epgId"),
        description = json.textOrNull("description"), backdrop = json.textOrNull("backdrop"),
        seriesId = json.textOrNull("seriesId"), parentSeriesId = json.textOrNull("parentSeriesId"),
        season = json.optInt("season").takeIf { json.has("season") && !json.isNull("season") },
        episode = json.optInt("episode").takeIf { json.has("episode") && !json.isNull("episode") },
        year = json.optInt("year").takeIf { json.has("year") && !json.isNull("year") },
        addedAt = json.optLong("addedAt"), durationMs = json.optLong("durationMs"),
    )

    private fun JSONObject.textOrNull(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }

    private fun sourceKey(source: PlaylistSource): String {
        val raw = when (source) {
            is PlaylistSource.M3u -> "m3u:${source.url}"
            is PlaylistSource.Xtream -> "xtream:${source.server}|${source.username}|${source.password}"
        }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    internal fun m3uPlusUrl(url: String): String {
        if (!url.contains("get.php", ignoreCase = true) || Regex("[?&]type=", RegexOption.IGNORE_CASE).containsMatchIn(url)) return url
        val separator = if ('?' in url) '&' else '?'
        return "$url${separator}type=m3u_plus&output=ts"
    }

    private fun downloadPlaylist(url: String) {
        var output: FileOutputStream? = null
        try {
            Network.open(url).buffered().use { input ->
                input.mark(8_192)
                val headerBuffer = ByteArray(8_192)
                val read = input.read(headerBuffer)
                val header = if (read > 0) String(headerBuffer, 0, read, Charsets.UTF_8).lineSequence().firstOrNull().orEmpty().trimStart('\uFEFF') else ""
                require(header.startsWith("#EXTM3U", true)) { "A URL não retornou uma lista M3U válida." }
                input.reset()
                output = rawPlaylist.startWrite()
                input.copyTo(output!!, DEFAULT_BUFFER_SIZE)
                rawPlaylist.finishWrite(output!!)
                output = null
            }
        } catch (error: Throwable) {
            output?.let(rawPlaylist::failWrite)
            throw error
        }
    }

    private fun saveSource(source: PlaylistSource) = prefs.edit().apply {
        when (source) {
            is PlaylistSource.M3u -> putString("source_type", "m3u").putString("m3u_url", source.url)
            is PlaylistSource.Xtream -> putString("source_type", "xtream").putString("server", source.server)
                .putString("username", source.username).putString("password", source.password)
        }
    }.apply()
}
