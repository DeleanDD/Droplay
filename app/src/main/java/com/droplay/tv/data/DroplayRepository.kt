package com.droplay.tv.data

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest

class DroplayRepository(context: Context) {
    private val prefs = context.getSharedPreferences("droplay_local", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(context.filesDir, "catalog-v3.jsonl"))
    private val fastCache = AtomicFile(File(context.filesDir, "catalog-v4.bin"))
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
        RefreshInterval.valueOf(prefs.getString("refresh_interval", RefreshInterval.WEEKLY.name).orEmpty())
    }.getOrDefault(RefreshInterval.WEEKLY)

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

    fun cached(source: PlaylistSource): Catalog? = cachedCatalog(source, requireFresh = false)

    suspend fun load(
        source: PlaylistSource,
        save: Boolean = true,
        force: Boolean = false,
        progress: (String) -> Unit = {},
    ): Catalog {
        if (!force) cachedCatalog(source, requireFresh = true)?.let { return it }

        return try {
            val entries: List<MediaEntry>
            val epgUrl: String?
            when (source) {
                is PlaylistSource.M3u -> {
                    progress("Baixando a lista M3U Plus…")
                    downloadPlaylist(m3uPlusUrl(source.url))
                    progress("Lendo a lista de reprodução…")
                    val parsed = rawPlaylist.openRead().bufferedReader().use(M3uParser::parseCatalog)
                    entries = parsed.entries
                    epgUrl = parsed.epgUrl
                }
                is PlaylistSource.Xtream -> {
                    entries = XtreamClient(source).load(progress)
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
                    subtitlesFromJson(it.optJSONArray("subtitles")),
                )
            }
        }
    }

    fun saveProgress(media: MediaEntry, positionMs: Long, durationMs: Long) {
        if (media.kind == MediaKind.LIVE || media.id.isBlank() || positionMs < 2_000) return
        val records = history().filterNot { it.mediaId == media.id }.toMutableList()
        records.add(0, WatchRecord(media.id, positionMs, durationMs, System.currentTimeMillis(), media.name, media.url,
            media.kind, media.group, media.logo, media.parentSeriesId, media.subtitles))
        val json = JSONArray()
        records.take(60).forEach { json.put(JSONObject().put("id", it.mediaId).put("position", it.positionMs)
            .put("duration", it.durationMs).put("at", it.watchedAt).put("name", it.name).put("url", it.url)
            .put("kind", it.kind.name).put("group", it.group).put("logo", it.logo ?: "")
            .put("parentSeriesId", it.parentSeriesId ?: "").put("subtitles", subtitlesToJson(it.subtitles))) }
        prefs.edit().putString("history", json.toString()).apply()
    }

    fun clearSource() {
        prefs.edit().remove("source_type").remove("m3u_url").remove("server").remove("username")
            .remove("password").remove("epg_url").remove("last_catalog_refresh").remove("catalog_source_key").apply()
        cache.delete()
        fastCache.delete()
        rawPlaylist.delete()
    }

    private fun cachedCatalog(source: PlaylistSource, requireFresh: Boolean): Catalog? {
        val interval = refreshInterval()
        val age = System.currentTimeMillis() - lastRefresh()
        val sourceMatches = prefs.getString("catalog_source_key", null) == sourceKey(source)
        if (!sourceMatches) return null
        if (requireFresh && (interval == RefreshInterval.EVERY_LAUNCH || age < 0 || age >= interval.durationMs)) return null
        readFastCatalog()?.let { return it }
        return readLegacyCatalog()
    }

    private fun saveCatalog(source: PlaylistSource, entries: List<MediaEntry>) {
        writeFastCatalog(entries)
        cache.delete()
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_catalog_refresh", now).putString("catalog_source_key", sourceKey(source)).apply()
    }

    @Synchronized
    fun ensureFastCache(source: PlaylistSource, entries: List<MediaEntry>) {
        if (fastCache.baseFile.exists() || prefs.getString("catalog_source_key", null) != sourceKey(source)) return
        writeFastCatalog(entries)
        cache.delete()
    }

    private fun readLegacyCatalog(): Catalog? = runCatching {
        if (!cache.baseFile.exists()) return@runCatching null
        val input = cache.openRead()
        val textReader = InputStreamReader(input, Charsets.UTF_8).buffered()
        require(textReader.readLine() == "DROPLAY-CATALOG-3") { "Cache incompatível." }
        val entries = ArrayList<MediaEntry>()
        JsonReader(textReader).use { reader ->
            reader.isLenient = true
            while (reader.peek() != JsonToken.END_DOCUMENT) entries += reader.readMediaEntry()
        }
        Catalog(entries)
    }.getOrNull()?.takeIf { it.entries.isNotEmpty() }

    private fun readFastCatalog(): Catalog? = runCatching {
        if (!fastCache.baseFile.exists()) return@runCatching null
        DataInputStream(fastCache.openRead().buffered()).use { input ->
            require(input.readInt() == FAST_CACHE_MAGIC && input.readInt() == FAST_CACHE_SCHEMA) { "Cache rápido incompatível." }
            val count = input.readInt()
            require(count in 1..500_000) { "Quantidade inválida no cache." }
            Catalog(ArrayList<MediaEntry>(count).apply { repeat(count) { add(input.readMediaEntry()) } })
        }
    }.getOrNull()

    @Synchronized
    private fun writeFastCatalog(entries: List<MediaEntry>) {
        var stream: FileOutputStream? = null
        try {
            stream = fastCache.startWrite()
            val output = DataOutputStream(stream.buffered())
            output.writeInt(FAST_CACHE_MAGIC)
            output.writeInt(FAST_CACHE_SCHEMA)
            output.writeInt(entries.size)
            entries.forEach { output.writeMediaEntry(it) }
            output.flush()
            fastCache.finishWrite(stream)
            stream = null
        } catch (error: Throwable) {
            stream?.let(fastCache::failWrite)
            throw error
        }
    }

    private fun subtitlesToJson(tracks: List<SubtitleTrack>) = JSONArray().apply {
        tracks.forEach { put(JSONObject().put("url", it.url).put("label", it.label).put("language", it.language).put("mimeType", it.mimeType)) }
    }

    private fun subtitlesFromJson(array: JSONArray?): List<SubtitleTrack> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                item.textOrNull("url")?.let { SubtitleTrack(it, item.textOrNull("label"), item.textOrNull("language"), item.textOrNull("mimeType")) }
            }
        }

    private fun JSONObject.textOrNull(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }

    private fun DataOutputStream.writeMediaEntry(item: MediaEntry) {
        writeText(item.id); writeText(item.name); writeText(item.url); writeText(item.kind.name)
        writeText(item.group); writeNullableText(item.logo); writeNullableText(item.epgId)
        writeNullableText(item.description); writeNullableText(item.backdrop); writeNullableText(item.seriesId)
        writeNullableText(item.parentSeriesId); writeNullableInt(item.season); writeNullableInt(item.episode)
        writeNullableInt(item.year); writeLong(item.addedAt); writeLong(item.durationMs)
        writeInt(item.subtitles.size)
        item.subtitles.forEach { subtitle ->
            writeText(subtitle.url); writeNullableText(subtitle.label); writeNullableText(subtitle.language); writeNullableText(subtitle.mimeType)
        }
    }

    private fun DataInputStream.readMediaEntry(): MediaEntry = MediaEntry(
        id = readText(), name = readText(), url = readText(),
        kind = runCatching { MediaKind.valueOf(readText()) }.getOrDefault(MediaKind.MOVIE),
        group = readText(), logo = readNullableText(), epgId = readNullableText(),
        description = readNullableText(), backdrop = readNullableText(), seriesId = readNullableText(),
        parentSeriesId = readNullableText(), season = readNullableInt(), episode = readNullableInt(),
        year = readNullableInt(), addedAt = readLong(), durationMs = readLong(),
        subtitles = List(readInt().also { require(it in 0..100) }) {
            SubtitleTrack(readText(), readNullableText(), readNullableText(), readNullableText())
        },
    )

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataOutputStream.writeNullableText(value: String?) {
        if (value == null) writeInt(-1) else writeText(value)
    }
    private fun DataInputStream.readText(): String {
        val size = readInt(); require(size in 0..20_000_000) { "Texto inválido no cache." }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
    private fun DataInputStream.readNullableText(): String? {
        val size = readInt()
        require(size in -1..20_000_000) { "Texto inválido no cache." }
        if (size == -1) return null
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
    private fun DataOutputStream.writeNullableInt(value: Int?) { if (value == null) writeBoolean(false) else { writeBoolean(true); writeInt(value) } }
    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

    private fun JsonReader.readMediaEntry(): MediaEntry {
        var id = ""; var name = ""; var url = ""; var kind = MediaKind.MOVIE; var group = "Outros"
        var logo: String? = null; var epgId: String? = null; var description: String? = null; var backdrop: String? = null
        var seriesId: String? = null; var parentSeriesId: String? = null; var season: Int? = null; var episode: Int? = null
        var year: Int? = null; var addedAt = 0L; var durationMs = 0L; var subtitles = emptyList<SubtitleTrack>()
        beginObject()
        while (hasNext()) when (nextName()) {
            "id" -> id = nextText().orEmpty(); "name" -> name = nextText().orEmpty(); "url" -> url = nextText().orEmpty()
            "kind" -> kind = runCatching { MediaKind.valueOf(nextText().orEmpty()) }.getOrDefault(MediaKind.MOVIE)
            "group" -> group = nextText() ?: "Outros"; "logo" -> logo = nextText(); "epgId" -> epgId = nextText()
            "description" -> description = nextText(); "backdrop" -> backdrop = nextText(); "seriesId" -> seriesId = nextText()
            "parentSeriesId" -> parentSeriesId = nextText(); "season" -> season = nextIntOrNull(); "episode" -> episode = nextIntOrNull()
            "year" -> year = nextIntOrNull(); "addedAt" -> addedAt = nextLongOrNull() ?: 0L; "durationMs" -> durationMs = nextLongOrNull() ?: 0L
            "subtitles" -> subtitles = readSubtitles()
            else -> skipValue()
        }
        endObject()
        return MediaEntry(id, name, url, kind, group, logo, epgId, description, backdrop, seriesId, parentSeriesId,
            season, episode, year, addedAt, durationMs, subtitles)
    }

    private fun JsonReader.readSubtitles(): List<SubtitleTrack> {
        if (peek() == JsonToken.NULL) { nextNull(); return emptyList() }
        val result = ArrayList<SubtitleTrack>()
        beginArray()
        while (hasNext()) {
            var url = ""; var label: String? = null; var language: String? = null; var mimeType: String? = null
            beginObject()
            while (hasNext()) when (nextName()) {
                "url" -> url = nextText().orEmpty(); "label" -> label = nextText(); "language" -> language = nextText(); "mimeType" -> mimeType = nextText()
                else -> skipValue()
            }
            endObject()
            if (url.isNotBlank()) result += SubtitleTrack(url, label, language, mimeType)
        }
        endArray()
        return result
    }

    private fun JsonReader.nextText(): String? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
    private fun JsonReader.nextIntOrNull(): Int? = nextLongOrNull()?.toInt()
    private fun JsonReader.nextLongOrNull(): Long? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextString().toLongOrNull()

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

    private companion object {
        const val FAST_CACHE_MAGIC = 0x44525034
        const val FAST_CACHE_SCHEMA = 1
    }
}
