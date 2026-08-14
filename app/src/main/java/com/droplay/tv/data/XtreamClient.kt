package com.droplay.tv.data

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.URLEncoder

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

    fun load(): List<MediaEntry> {
        validate()
        val liveCategories = categories("get_live_categories")
        val vodCategories = categories("get_vod_categories")
        val seriesCategories = categories("get_series_categories")
        return buildList {
            forEachItem("get_live_streams") { o ->
                val id = o.optString("stream_id")
                add(MediaEntry("live:$id", o.optString("name", "Canal"), "$base/live/$user/$pass/$id.ts", MediaKind.LIVE,
                    liveCategories[o.optString("category_id")] ?: "Ao vivo", o.optString("stream_icon").takeIf(String::isNotBlank), o.optString("epg_channel_id").takeIf(String::isNotBlank)))
            }
            forEachItem("get_vod_streams") { o ->
                val id = o.optString("stream_id"); val ext = o.optString("container_extension", "mp4")
                add(MediaEntry("movie:$id", o.optString("name", "Filme"), "$base/movie/$user/$pass/$id.$ext", MediaKind.MOVIE,
                    vodCategories[o.optString("category_id")] ?: "Filmes", o.optString("stream_icon").takeIf(String::isNotBlank),
                    description = o.optString("plot").takeIf(String::isNotBlank), year = yearFrom(o),
                    addedAt = epochMillis(o.optString("added")), durationMs = durationMillis(o)))
            }
            forEachItem("get_series") { o ->
                val id = o.optString("series_id")
                add(MediaEntry("series:$id", o.optString("name", "Série"), "", MediaKind.SERIES,
                    seriesCategories[o.optString("category_id")] ?: "Séries", o.optString("cover").takeIf(String::isNotBlank),
                    description = o.optString("plot").takeIf(String::isNotBlank), backdrop = imageFrom(o, "backdrop_path"), seriesId = id,
                    year = yearFrom(o), addedAt = epochMillis(o.optString("last_modified").ifBlank { o.optString("added") })))
            }
        }
    }

    fun episodes(seriesId: String): List<MediaEntry> {
        val root = JSONObject(Network.text(api("get_series_info", "&series_id=${enc(seriesId)}")))
        val seasons = root.optJSONObject("episodes") ?: return emptyList()
        return seasons.keys().asSequence().flatMap { season ->
            val array = seasons.optJSONArray(season) ?: JSONArray()
            (0 until array.length()).asSequence().map { i ->
                val o = array.getJSONObject(i); val id = o.optString("id"); val ext = o.optString("container_extension", "mp4")
                val info = o.optJSONObject("info")
                val episodeNumber = o.optInt("episode_num", i + 1)
                MediaEntry("episode:$id", o.optString("title", "Episódio $episodeNumber"), "$base/series/$user/$pass/$id.$ext",
                    MediaKind.MOVIE, "Temporada $season", logo = info?.optString("movie_image")?.takeIf(String::isNotBlank),
                    description = info?.optString("plot")?.takeIf(String::isNotBlank), parentSeriesId = seriesId,
                    season = season.toIntOrNull(), episode = episodeNumber, durationMs = durationMillis(info ?: o))
            }
        }.toList()
    }

    fun details(media: MediaEntry): MediaEntry {
        if (media.kind != MediaKind.MOVIE || !media.id.startsWith("movie:")) return media
        val id = media.id.substringAfter(':')
        val root = JSONObject(Network.text(api("get_vod_info", "&vod_id=${enc(id)}")))
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data") ?: JSONObject()
        return media.copy(
            description = info.optString("plot").takeIf(String::isNotBlank) ?: media.description,
            logo = info.optString("movie_image").takeIf(String::isNotBlank) ?: media.logo,
            backdrop = imageFrom(info, "backdrop_path") ?: media.backdrop,
            year = yearFrom(info) ?: yearFrom(data) ?: media.year,
            durationMs = durationMillis(info).takeIf { it > 0 } ?: durationMillis(data).takeIf { it > 0 } ?: media.durationMs,
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
    private companion object {
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
