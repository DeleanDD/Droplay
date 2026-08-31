package com.droplay.tv.data

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.URLEncoder
import java.net.URI

class XtreamClient(source: PlaylistSource.Xtream) {
    private val base = source.server.trim().trimEnd('/')
    private val user = enc(source.username)
    private val pass = enc(source.password)
    private fun api(action: String, extra: String = "") = "$base/player_api.php?username=$user&password=$pass&action=$action$extra"

    fun validate(): XtreamAccountInfo {
        val userInfo = JSONObject(Network.text("$base/player_api.php?username=$user&password=$pass")).optJSONObject("user_info") ?: JSONObject()
        val auth = userInfo.optInt("auth", 0)
        require(auth == 1) { "Acesso Xtream recusado pelo servidor." }
        val status = userInfo.optString("status")
        require(status.lowercase() !in setOf("expired", "banned", "disabled")) { "A conta Xtream está ${status.lowercase()}." }
        return XtreamAccountInfo(auth == 1, status, userInfo.optString("exp_date").toLongOrNull())
    }

    fun liveBatch(): XtreamBatch = batch("get_live_categories", "get_live_streams", MediaKind.LIVE)
    fun vodBatch(): XtreamBatch = batch("get_vod_categories", "get_vod_streams", MediaKind.MOVIE)
    fun seriesBatch(): XtreamBatch = batch("get_series_categories", "get_series", MediaKind.SERIES)

    fun episodes(seriesId: String): List<MediaEntry> {
        val result = ArrayList<MediaEntry>()
        var seriesTmdbId: Int? = null
        var seriesTitle: String? = null
        var seriesYear: Int? = null
        Network.open(api("get_series_info", "&series_id=${enc(seriesId)}")).use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val rootKey = reader.nextName()
                    if (rootKey == "info" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                        val info = reader.readObject()
                        seriesTmdbId = tmdbIdFrom(info)
                        seriesTitle = firstText(info, "name", "title")
                        seriesYear = yearFrom(info)
                    } else if (rootKey == "episodes" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val seasonLabel = reader.nextName()
                            val seasonNumber = seasonLabel.toIntOrNull() ?: Regex("\\d+").find(seasonLabel)?.value?.toIntOrNull()
                            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                                reader.skipValue()
                                continue
                            }
                            reader.beginArray()
                            var index = 0
                            while (reader.hasNext()) {
                                val o = reader.readObject()
                                val id = o.optString("id")
                                val ext = o.optString("container_extension", "mp4")
                                val info = o.optJSONObject("info") ?: JSONObject()
                                val episodeNumber = o.optInt("episode_num", index + 1)
                                result += MediaEntry(
                                    id = "episode:$id",
                                    name = o.optString("title", "Episódio $episodeNumber"),
                                    url = "",
                                    kind = MediaKind.MOVIE,
                                    group = "Temporada $seasonLabel",
                                    logo = firstText(info, "movie_image", "cover_big", "cover"),
                                    description = descriptionFrom(info) ?: descriptionFrom(o),
                                    parentSeriesId = seriesId,
                                    season = seasonNumber,
                                    episode = episodeNumber,
                                    durationMs = durationMillis(info).takeIf { it > 0 } ?: durationMillis(o),
                                    subtitles = subtitleTracks(info, o),
                                    streamId = id,
                                    containerExtension = ext,
                                )
                                index++
                            }
                            reader.endArray()
                        }
                        reader.endObject()
                    } else reader.skipValue()
                }
                reader.endObject()
            }
        }
        return result.map { it.copy(tmdbId = seriesTmdbId, parentTitle = seriesTitle, year = seriesYear) }
            .sortedWith(compareBy<MediaEntry> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })
    }

    fun details(media: MediaEntry): MediaEntry {
        if (media.kind != MediaKind.MOVIE || !media.id.startsWith("movie:")) return media
        val id = media.id.substringAfter(':')
        val root = JSONObject(Network.text(api("get_vod_info", "&vod_id=${enc(id)}")))
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data") ?: JSONObject()
        val freshId = firstText(data, "stream_id", "id") ?: id
        val currentExtension = media.containerExtension ?: "mp4"
        val freshExtension = firstText(data, "container_extension")?.trimStart('.')?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
            ?: currentExtension
        val directSource = firstText(data, "direct_source") ?: firstText(info, "direct_source")
        return media.copy(
            url = directSource?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }.orEmpty(),
            description = descriptionFrom(info) ?: descriptionFrom(data) ?: media.description,
            logo = firstText(info, "movie_image", "cover_big", "cover") ?: media.logo,
            backdrop = imageFrom(info, "backdrop_path") ?: media.backdrop,
            year = yearFrom(info) ?: yearFrom(data) ?: media.year,
            durationMs = durationMillis(info).takeIf { it > 0 } ?: durationMillis(data).takeIf { it > 0 } ?: media.durationMs,
            subtitles = subtitleTracks(info, data, root).ifEmpty { media.subtitles },
            tmdbId = tmdbIdFrom(info) ?: tmdbIdFrom(data) ?: tmdbIdFrom(root) ?: media.tmdbId,
            streamId = freshId,
            containerExtension = freshExtension,
        )
    }

    private fun batch(categoryAction: String, contentAction: String, kind: MediaKind): XtreamBatch {
        val batchStarted = System.nanoTime()
        val categories = categories(categoryAction)
        val categoryMap = categories.allowed
        var adultBlocked = 0
        var cinemaBlocked = 0
        var kids = 0
        var brazilian = 0
        var classificationNanos = 0L
        var received = 0
        val entries = buildList {
            forEachItem(contentAction) { o ->
                received++
                val id = o.optString(if (kind == MediaKind.SERIES) "series_id" else "stream_id")
                if (id.isBlank()) return@forEachItem
                val categoryId = o.optString("category_id")
                val extension = o.optString("container_extension", if (kind == MediaKind.LIVE) "ts" else "mp4")
                val name = o.optString("name", when (kind) { MediaKind.LIVE -> "Canal"; MediaKind.MOVIE -> "Filme"; MediaKind.SERIES -> "Série" })
                val group = categoryMap[categoryId] ?: "Outros"
                val started = System.nanoTime()
                val classification = ContentClassificationEngine.classify(ClassificationInput(
                    name, group, kind,
                    serverAdult = o.optBoolean("is_adult") || o.optBoolean("adult") || o.optInt("is_adult", 0) == 1 || o.optInt("adult", 0) == 1,
                    country = firstText(o, "country", "production_country", "origin_country"),
                    genre = firstText(o, "genre", "genres"), parentalRating = firstText(o, "age", "age_rating", "certification", "rated"),
                    categoryClassification = categories.classifiedById[categoryId],
                ))
                classificationNanos += System.nanoTime() - started
                if (classification.isAdult) adultBlocked++
                if (classification.isLowQualityCinema) cinemaBlocked++
                if (classification.isKids) kids++
                if (classification.isBrazilian) brazilian++
                add(MediaEntry(
                    id = when (kind) { MediaKind.LIVE -> "live:$id"; MediaKind.MOVIE -> "movie:$id"; MediaKind.SERIES -> "series:$id" },
                    name = name, url = "", kind = kind, group = group,
                    logo = o.optString(if (kind == MediaKind.SERIES) "cover" else "stream_icon").takeIf(String::isNotBlank),
                    epgId = o.optString("epg_channel_id").takeIf(String::isNotBlank), description = descriptionFrom(o),
                    backdrop = imageFrom(o, "backdrop_path"), seriesId = id.takeIf { kind == MediaKind.SERIES },
                    year = yearFrom(o), addedAt = epochMillis(o.optString("last_modified").ifBlank { o.optString("added") }),
                    durationMs = durationMillis(o), streamId = id, categoryId = categoryId,
                    containerExtension = extension, rating = ratingFrom(o),
                    normalizedName = classification.normalizedName, normalizedCategoryName = classification.normalizedCategoryName,
                    isAdult = classification.isAdult, isLowQualityCinema = classification.isLowQualityCinema,
                    isKids = classification.isKids, isBrazilian = classification.isBrazilian, isHidden = classification.isHidden,
                    classificationReason = classification.reason.name, classificationVersion = classification.version,
                ))
            }
        }
        val classificationMs = classificationNanos / 1_000_000L
        val totalMs = (System.nanoTime() - batchStarted) / 1_000_000L
        return XtreamBatch(kind, categoryMap, entries, ClassificationMetrics(received, adultBlocked, cinemaBlocked, kids, brazilian, classificationMs,
            networkAndParsingMs = (totalMs - classificationMs).coerceAtLeast(0L)), categories.classifiedById)
    }

    private fun categories(action: String): XtreamCategories {
        val allowed = LinkedHashMap<String, String>()
        val classified = LinkedHashMap<String, CategoryClassification>()
        forEachItem(action) {
            val id = it.optString("category_id")
            val name = it.optString("category_name", "Outros")
            allowed[id] = name
            classified[id] = ContentClassificationEngine.classifyCategory(name, it.optBoolean("is_adult") || it.optInt("is_adult", 0) == 1)
        }
        return XtreamCategories(allowed, classified)
    }

    /**
     * Xtream catalogs can contain tens of thousands of records. Reading each object
     * directly from the response avoids keeping the complete JSON response and a
     * second JSONArray copy in memory on low-memory Android TV devices.
     */
    private inline fun forEachItem(action: String, consume: (JSONObject) -> Unit) {
        Network.open(api(action)).use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) consume(reader.readObject())
                reader.endArray()
            }
        }
    }

    private fun JsonReader.readObject(): JSONObject {
        val result = JSONObject()
        beginObject()
        while (hasNext()) result.put(nextName(), readValue())
        endObject()
        return result
    }

    private fun JsonReader.readValue(): Any = when (peek()) {
        JsonToken.BEGIN_OBJECT -> readObject()
        JsonToken.BEGIN_ARRAY -> JSONArray().also { array ->
            beginArray()
            while (hasNext()) array.put(readValue())
            endArray()
        }
        JsonToken.STRING -> nextString()
        JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NULL -> { nextNull(); JSONObject.NULL }
        else -> { skipValue(); JSONObject.NULL }
    }
    private fun imageFrom(o: JSONObject, key: String): String? {
        val value = o.opt(key)
        return when (value) {
            is JSONArray -> value.optString(0).takeIf(String::isNotBlank)
            is String -> value.takeIf(String::isNotBlank)
            else -> null
        }
    }

    private fun subtitleTracks(vararg roots: JSONObject): List<SubtitleTrack> {
        val tracks = ArrayList<SubtitleTrack>()
        val keys = listOf("subtitles", "subtitle", "external_subtitles", "subtitles_list", "subtitle_url")
        roots.forEach { root -> keys.forEach { key -> if (root.has(key)) collectSubtitles(root.opt(key), key, tracks) } }
        return tracks.distinctBy { it.url }
    }

    private fun collectSubtitles(value: Any?, fallbackLabel: String?, output: MutableList<SubtitleTrack>) {
        when (value) {
            is JSONArray -> (0 until value.length()).forEach { collectSubtitles(value.opt(it), fallbackLabel, output) }
            is JSONObject -> {
                val rawUrl = firstText(value, "url", "file", "path", "src", "subtitle_url")
                if (rawUrl != null) {
                    resolvedSubtitleUrl(rawUrl)?.let { url ->
                        output += SubtitleTrack(url, firstText(value, "label", "title", "name") ?: fallbackLabel,
                            firstText(value, "language", "lang", "code"),
                            firstText(value, "mime_type", "mime", "format", "codec_name", "type"))
                    }
                } else {
                    value.keys().forEach { key -> collectSubtitles(value.opt(key), key, output) }
                }
            }
            is String -> {
                val text = value.trim()
                when {
                    text.startsWith('[') -> runCatching { JSONArray(text) }.getOrNull()?.let { collectSubtitles(it, fallbackLabel, output) }
                    text.startsWith('{') -> runCatching { JSONObject(text) }.getOrNull()?.let { collectSubtitles(it, fallbackLabel, output) }
                    else -> resolvedSubtitleUrl(text)?.let { output += SubtitleTrack(it, fallbackLabel?.takeUnless { label -> label.startsWith("subtitle") }) }
                }
            }
        }
    }

    private fun resolvedSubtitleUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.equals("null", true)) return null
        val looksLikeSubtitle = value.startsWith("http://", true) || value.startsWith("https://", true) || value.startsWith("//") || value.startsWith('/') ||
            Regex("(?i)\\.(srt|vtt|ass|ssa|ttml|dfxp)(?:\\?.*)?$").containsMatchIn(value)
        if (!looksLikeSubtitle) return null
        return runCatching { URI("$base/").resolve(value).toString() }.getOrDefault(value)
    }
    private companion object {
        fun firstText(o: JSONObject, vararg keys: String): String? = keys.asSequence()
            .map { o.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && it != "null" }
        fun descriptionFrom(o: JSONObject): String? = firstText(o, "plot", "description", "overview", "storyline", "tmdb_plot")
        fun tmdbIdFrom(o: JSONObject): Int? = sequenceOf("tmdb_id", "tmdb", "tmdbId")
            .mapNotNull { key -> o.optString(key).trim().substringBefore('.').toIntOrNull() }
            .firstOrNull { it > 0 }
        fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
        fun epochMillis(value: String): Long = value.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1_000L else it } ?: 0L
        fun yearFrom(o: JSONObject): Int? = sequenceOf(o.optString("year"), o.optString("releaseDate"), o.optString("releasedate"), o.optString("name"))
            .mapNotNull { Regex("(?:19|20)\\d{2}").find(it)?.value?.toIntOrNull() }.firstOrNull()
        fun durationMillis(o: JSONObject): Long {
            o.optLong("duration_secs", 0L).takeIf { it > 0 }?.let { return it * 1_000L }
            val value = o.optString("duration")
            if (value.isBlank()) return 0L
            val parts = value.split(':').mapNotNull(String::toLongOrNull)
            return when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1_000L
                2 -> (parts[0] * 60 + parts[1]) * 1_000L
                else -> value.toLongOrNull()?.times(1_000L) ?: 0L
            }
        }
        fun ratingFrom(o: JSONObject): Double? = sequenceOf("rating_5based", "rating", "vote_average")
            .mapNotNull { o.optString(it).replace(',', '.').toDoubleOrNull() }.firstOrNull()
    }
}

data class XtreamBatch(
    val kind: MediaKind,
    val categories: Map<String, String>,
    val entries: List<MediaEntry>,
    val classificationMetrics: ClassificationMetrics = ClassificationMetrics(),
    val categoryClassifications: Map<String, CategoryClassification> = emptyMap(),
)
data class XtreamAccountInfo(val authenticated: Boolean, val status: String, val expiresAtEpochSeconds: Long?)
private data class XtreamCategories(val allowed: Map<String, String>, val classifiedById: Map<String, CategoryClassification>)
data class ClassificationMetrics(val received: Int = 0, val adultBlocked: Int = 0, val cinemaBlocked: Int = 0, val kids: Int = 0, val brazilian: Int = 0, val classificationMs: Long = 0L, val networkAndParsingMs: Long = 0L, val persistenceMs: Long = 0L, val approximateMemoryBytes: Long = 0L)
