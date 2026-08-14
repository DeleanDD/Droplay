package com.droplay.tv.data

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.URLEncoder
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class XtreamClient(source: PlaylistSource.Xtream) {
    private val base = source.server.trim().trimEnd('/')
    private val user = enc(source.username)
    private val pass = enc(source.password)
    private fun api(action: String, extra: String = "") = "$base/player_api.php?username=$user&password=$pass&action=$action$extra"

    fun validate() {
        val auth = JSONObject(Network.text("$base/player_api.php?username=$user&password=$pass"))
            .optJSONObject("user_info")?.optInt("auth", 0) ?: 0
        require(auth == 1) { "Acesso Xtream recusado pelo servidor." }
    }

    suspend fun load(progress: (String) -> Unit = {}): List<MediaEntry> = coroutineScope {
        progress("Validando o acesso Xtream…")
        validate()
        progress("Carregando categorias Xtream…")
        val liveCategories = async(Dispatchers.IO) { categories("get_live_categories") }
        val vodCategories = async(Dispatchers.IO) { categories("get_vod_categories") }
        val seriesCategories = async(Dispatchers.IO) { categories("get_series_categories") }
        progress("Baixando canais, filmes e séries…")
        listOf(
            async(Dispatchers.IO) { buildList {
                val categories = liveCategories.await()
            forEachItem("get_live_streams") { o ->
                val id = o.optString("stream_id")
                add(MediaEntry("live:$id", o.optString("name", "Canal"), "$base/live/$user/$pass/$id.ts", MediaKind.LIVE,
                    categories[o.optString("category_id")] ?: "Ao vivo", o.optString("stream_icon").takeIf(String::isNotBlank), o.optString("epg_channel_id").takeIf(String::isNotBlank)))
            }
            } },
            async(Dispatchers.IO) { buildList {
                val categories = vodCategories.await()
            forEachItem("get_vod_streams") { o ->
                val id = o.optString("stream_id"); val ext = o.optString("container_extension", "mp4")
                add(MediaEntry("movie:$id", o.optString("name", "Filme"), "$base/movie/$user/$pass/$id.$ext", MediaKind.MOVIE,
                    categories[o.optString("category_id")] ?: "Filmes", o.optString("stream_icon").takeIf(String::isNotBlank),
                    description = descriptionFrom(o), year = yearFrom(o),
                    addedAt = epochMillis(o.optString("added")), durationMs = durationMillis(o)))
            }
            } },
            async(Dispatchers.IO) { buildList {
                val categories = seriesCategories.await()
            forEachItem("get_series") { o ->
                val id = o.optString("series_id")
                add(MediaEntry("series:$id", o.optString("name", "Série"), "", MediaKind.SERIES,
                    categories[o.optString("category_id")] ?: "Séries", o.optString("cover").takeIf(String::isNotBlank),
                    description = descriptionFrom(o), backdrop = imageFrom(o, "backdrop_path"), seriesId = id,
                    year = yearFrom(o), addedAt = epochMillis(o.optString("last_modified").ifBlank { o.optString("added") })))
            }
            } },
        ).awaitAll().flatten()
    }

    fun episodes(seriesId: String): List<MediaEntry> {
        val result = ArrayList<MediaEntry>()
        Network.open(api("get_series_info", "&series_id=${enc(seriesId)}")).use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "episodes" && reader.peek() == JsonToken.BEGIN_OBJECT) {
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
                                    url = "$base/series/$user/$pass/$id.$ext",
                                    kind = MediaKind.MOVIE,
                                    group = "Temporada $seasonLabel",
                                    logo = firstText(info, "movie_image", "cover_big", "cover"),
                                    description = descriptionFrom(info) ?: descriptionFrom(o),
                                    parentSeriesId = seriesId,
                                    season = seasonNumber,
                                    episode = episodeNumber,
                                    durationMs = durationMillis(info).takeIf { it > 0 } ?: durationMillis(o),
                                    subtitles = subtitleTracks(info, o),
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
        return result.sortedWith(compareBy<MediaEntry> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })
    }

    fun details(media: MediaEntry): MediaEntry {
        if (media.kind != MediaKind.MOVIE || !media.id.startsWith("movie:")) return media
        val id = media.id.substringAfter(':')
        val root = JSONObject(Network.text(api("get_vod_info", "&vod_id=${enc(id)}")))
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data") ?: JSONObject()
        return media.copy(
            description = descriptionFrom(info) ?: descriptionFrom(data) ?: media.description,
            logo = firstText(info, "movie_image", "cover_big", "cover") ?: media.logo,
            backdrop = imageFrom(info, "backdrop_path") ?: media.backdrop,
            year = yearFrom(info) ?: yearFrom(data) ?: media.year,
            durationMs = durationMillis(info).takeIf { it > 0 } ?: durationMillis(data).takeIf { it > 0 } ?: media.durationMs,
            subtitles = subtitleTracks(info, data, root).ifEmpty { media.subtitles },
        )
    }

    private fun categories(action: String): Map<String, String> = buildMap {
        forEachItem(action) { put(it.optString("category_id"), it.optString("category_name", "Outros")) }
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
                            firstText(value, "language", "lang", "code"))
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
        val looksLikeSubtitle = value.startsWith("http://", true) || value.startsWith("https://", true) || value.startsWith('/') ||
            Regex("(?i)\\.(srt|vtt|ass|ssa|ttml|dfxp)(?:\\?.*)?$").containsMatchIn(value)
        if (!looksLikeSubtitle) return null
        return runCatching { URI("$base/").resolve(value).toString() }.getOrDefault(value)
    }
    private companion object {
        fun firstText(o: JSONObject, vararg keys: String): String? = keys.asSequence()
            .map { o.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && it != "null" }
        fun descriptionFrom(o: JSONObject): String? = firstText(o, "plot", "description", "overview", "storyline", "tmdb_plot")
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
    }
}
