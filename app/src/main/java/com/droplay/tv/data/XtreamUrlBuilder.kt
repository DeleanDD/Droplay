package com.droplay.tv.data

import java.net.URLEncoder

object XtreamUrlBuilder {
    fun normalizeBase(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    fun live(source: PlaylistSource.Xtream, streamId: String, extension: String? = "ts") =
        build(source, "live", streamId, extension)

    fun movie(source: PlaylistSource.Xtream, streamId: String, extension: String? = "mp4") =
        build(source, "movie", streamId, extension)

    fun episode(source: PlaylistSource.Xtream, episodeId: String, extension: String? = "mp4") =
        build(source, "series", episodeId, extension)

    fun forMedia(source: PlaylistSource.Xtream, media: MediaEntry): String {
        val id = media.streamId ?: media.id.substringAfter(':')
        return when (media.kind) {
            MediaKind.LIVE -> live(source, id, media.containerExtension ?: "ts")
            MediaKind.MOVIE -> movie(source, id, media.containerExtension ?: "mp4")
            MediaKind.SERIES -> if (media.id.startsWith("episode:")) episode(source, id, media.containerExtension ?: "mp4") else ""
        }
    }

    private fun build(source: PlaylistSource.Xtream, segment: String, id: String, extension: String?): String {
        val suffix = extension?.trim()?.trimStart('.')?.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        return "${normalizeBase(source.server)}/$segment/${enc(source.username)}/${enc(source.password)}/${enc(id)}$suffix"
    }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

object CredentialSanitizer {
    private val urlCredentials = Regex("(?i)(username|password|token)=([^&\\s]+)")
    private val pathCredentials = Regex("(?i)/(live|movie|series)/[^/\\s]+/[^/\\s]+/")

    fun sanitize(value: String): String = value
        .replace(urlCredentials) { "${it.groupValues[1]}=***" }
        .replace(pathCredentials) { "/${it.groupValues[1]}/***/***/" }
}
