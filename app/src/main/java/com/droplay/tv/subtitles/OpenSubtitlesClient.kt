package com.droplay.tv.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

data class OpenSubtitlesLogin(val token: String, val baseUrl: String)
data class OpenSubtitlesDownload(val link: String, val fileName: String)

class OpenSubtitlesClient {
    suspend fun login(configuration: OpenSubtitlesConfiguration, password: String): OpenSubtitlesLogin = withContext(Dispatchers.IO) {
        require(configuration.canSearch && configuration.username.isNotBlank() && password.isNotBlank()) { "Preencha API Key, User-Agent, usuário e senha." }
        val body = JSONObject().put("username", configuration.username).put("password", password).toString().toByteArray()
        val json = requestJson("${OpenSubtitlesConfiguration.DEFAULT_API_URL}/login", "POST", configuration, body, bearer = false)
        OpenSubtitlesLogin(json.getString("token"), json.optString("base_url", "api.opensubtitles.com"))
    }

    suspend fun searchPage(configuration: OpenSubtitlesConfiguration, request: SubtitleSearchRequest, page: Int): SubtitlePage = withContext(Dispatchers.IO) {
        require(configuration.canSearch) { "Configure o OpenSubtitles nas configurações." }
        val params = LinkedHashMap(request.parameters).apply { put("page", page.toString()) }
        val url = "${configuration.baseUrl}/subtitles?" + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val root = requestJson(url, "GET", configuration, null, bearer = configuration.token.isNotBlank())
        parseSearchPage(root, request.approximate)
    }

    suspend fun requestDownload(configuration: OpenSubtitlesConfiguration, fileId: Int): OpenSubtitlesDownload = withContext(Dispatchers.IO) {
        require(configuration.canDownload) { "Entre na conta do OpenSubtitles nas configurações para baixar." }
        val body = JSONObject().put("file_id", fileId).put("sub_format", "srt").toString().toByteArray()
        val json = requestJson("${configuration.baseUrl}/download", "POST", configuration, body, bearer = true)
        OpenSubtitlesDownload(json.getString("link"), json.optString("file_name", "$fileId.srt"))
    }

    suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = open(url, "GET", emptyMap(), null)
        try {
            checkResponse(connection)
            val input = BufferedInputStream(connection.inputStream)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_SUBTITLE_BYTES) { "Arquivo de legenda maior que o permitido." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } finally { connection.disconnect() }
    }

    private fun requestJson(url: String, method: String, configuration: OpenSubtitlesConfiguration, body: ByteArray?, bearer: Boolean): JSONObject {
        val headers = linkedMapOf("Api-Key" to configuration.apiKey, "User-Agent" to configuration.userAgent,
            "Accept" to "application/json", "Content-Type" to "application/json")
        if (bearer) headers["Authorization"] = "Bearer ${configuration.token}"
        val connection = open(url, method, headers, body)
        return try {
            checkResponse(connection)
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun open(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        headers.forEach(connection::setRequestProperty)
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }
        return connection
    }

    private fun checkResponse(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status in 200..299) return
        val retry = connection.getHeaderField("Retry-After")?.toLongOrNull()
        throw OpenSubtitlesException(status, "OpenSubtitles HTTP $status", retry)
    }

    companion object {
        const val MAX_SUBTITLE_BYTES = 10 * 1024 * 1024
        fun parseSearchPage(root: JSONObject, approximate: Boolean): SubtitlePage {
            val data = root.optJSONArray("data")
            val candidates = buildList {
                if (data != null) for (i in 0 until data.length()) {
                    val attributes = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                    val files = attributes.optJSONArray("files") ?: continue
                    for (n in 0 until files.length()) {
                        val file = files.optJSONObject(n) ?: continue
                        val fileId = file.optInt("file_id")
                        if (fileId <= 0) continue
                        add(SubtitleCandidate(
                            fileId = fileId, language = attributes.optString("language", "und"),
                            release = attributes.optString("release"), fileName = file.optString("file_name", "$fileId.srt"),
                            rating = attributes.optDouble("ratings", 0.0),
                            downloads = attributes.optInt("download_count") + attributes.optInt("new_download_count"),
                            trusted = attributes.optBoolean("from_trusted"), movieHashMatch = attributes.optBoolean("moviehash_match"),
                            hearingImpaired = attributes.optBoolean("hearing_impaired"), aiTranslated = attributes.optBoolean("ai_translated"),
                            machineTranslated = attributes.optBoolean("machine_translated"), approximate = approximate,
                        ))
                    }
                }
            }
            return SubtitlePage(root.optInt("page", 1), root.optInt("total_pages", 1).coerceAtLeast(1), candidates)
        }

        private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
