package com.droplay.tv.data

import org.json.JSONArray
import org.json.JSONObject
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
            addAll(items("get_live_streams").map { o ->
                val id = o.optString("stream_id")
                MediaEntry("live:$id", o.optString("name", "Canal"), "$base/live/$user/$pass/$id.ts", MediaKind.LIVE,
                    liveCategories[o.optString("category_id")] ?: "Ao vivo", o.optString("stream_icon").takeIf(String::isNotBlank), o.optString("epg_channel_id").takeIf(String::isNotBlank))
            })
            addAll(items("get_vod_streams").map { o ->
                val id = o.optString("stream_id"); val ext = o.optString("container_extension", "mp4")
                MediaEntry("movie:$id", o.optString("name", "Filme"), "$base/movie/$user/$pass/$id.$ext", MediaKind.MOVIE,
                    vodCategories[o.optString("category_id")] ?: "Filmes", o.optString("stream_icon").takeIf(String::isNotBlank), description = o.optString("plot").takeIf(String::isNotBlank))
            })
            addAll(items("get_series").map { o ->
                val id = o.optString("series_id")
                MediaEntry("series:$id", o.optString("name", "Série"), "", MediaKind.SERIES,
                    seriesCategories[o.optString("category_id")] ?: "Séries", o.optString("cover").takeIf(String::isNotBlank),
                    description = o.optString("plot").takeIf(String::isNotBlank), backdrop = imageFrom(o, "backdrop_path"), seriesId = id)
            })
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
                    season = season.toIntOrNull(), episode = episodeNumber)
            }
        }.toList()
    }

    private fun categories(action: String) = items(action).associate { it.optString("category_id") to it.optString("category_name", "Outros") }
    private fun items(action: String): List<JSONObject> {
        val array = JSONArray(Network.text(api(action)))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun imageFrom(o: JSONObject, key: String): String? {
        val value = o.opt(key)
        return when (value) {
            is JSONArray -> value.optString(0).takeIf(String::isNotBlank)
            is String -> value.takeIf(String::isNotBlank)
            else -> null
        }
    }
    private companion object { fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()) }
}
