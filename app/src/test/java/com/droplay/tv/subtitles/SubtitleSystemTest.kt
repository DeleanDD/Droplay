package com.droplay.tv.subtitles

import com.droplay.tv.data.MediaEntry
import com.droplay.tv.data.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SubtitleSystemTest {
    @Test fun `movie query prefers tmdb id`() {
        val request = SubtitleSearchQueryFactory.create(movie(tmdbId = 346698))
        assertEquals(mapOf("type" to "movie", "tmdb_id" to "346698"), request.parameters)
        assertFalse(request.approximate)
    }

    @Test fun `movie query falls back to clean title and year`() {
        val request = SubtitleSearchQueryFactory.create(movie(name = "Barbie [L]", year = 2023))
        assertEquals("Barbie", request.parameters["query"])
        assertEquals("2023", request.parameters["year"])
        assertTrue(request.approximate)
    }

    @Test fun `episode query uses parent tmdb season and episode`() {
        val media = movie(tmdbId = 1399).copy(parentSeriesId = "42", parentTitle = "Game of Thrones", season = 2, episode = 9)
        val request = SubtitleSearchQueryFactory.create(media)
        assertEquals("episode", request.parameters["type"])
        assertEquals("1399", request.parameters["parent_tmdb_id"])
        assertEquals("2", request.parameters["season_number"])
        assertEquals("9", request.parameters["episode_number"])
        assertNull(request.parameters["query"])
    }

    @Test fun `episode fallback includes title year season and episode`() {
        val media = movie(year = 2024).copy(parentSeriesId = "7", parentTitle = "Fallout", season = 1, episode = 3)
        val request = SubtitleSearchQueryFactory.create(media)
        assertEquals("Fallout", request.parameters["query"])
        assertEquals("2024", request.parameters["year"])
        assertTrue(request.approximate)
    }

    @Test fun `ranking prioritizes brazilian portuguese and removes duplicate file ids`() {
        val items = listOf(candidate(1, "en", rating = 10.0, downloads = 50_000), candidate(2, "pt-BR", rating = 7.0), candidate(2, "pob"))
        val ranked = SubtitleRanking.rank(items, "pt-br")
        assertEquals(listOf(2, 1), ranked.map { it.fileId })
        assertEquals("Português (Brasil)", SubtitleRanking.languageLabel(ranked.first().language))
    }

    @Test fun `ranking uses trusted rating and downloads inside language`() {
        val items = listOf(candidate(1, "pt-br", rating = 9.0), candidate(2, "pt-br", trusted = true), candidate(3, "pt-br", rating = 9.0, downloads = 100))
        assertEquals(listOf(2, 3, 1), SubtitleRanking.rank(items, "pt-br").map { it.fileId })
    }

    @Test fun `search response parsing expands files and marks approximate`() {
        val attributes = JSONObject().put("language", "pt-BR").put("ratings", 8.5).put("download_count", 12)
            .put("files", JSONArray().put(JSONObject().put("file_id", 91).put("file_name", "movie.srt")))
        val root = JSONObject().put("page", 2).put("total_pages", 4)
            .put("data", JSONArray().put(JSONObject().put("attributes", attributes)))
        val page = OpenSubtitlesClient.parseSearchPage(root, true)
        assertEquals(2, page.page); assertEquals(4, page.totalPages)
        assertEquals(91, page.candidates.single().fileId); assertTrue(page.candidates.single().approximate)
    }

    @Test fun `pager visits every page`() = runTest {
        val visited = mutableListOf<Int>()
        val result = SubtitlePager.load({ page -> visited += page; SubtitlePage(page, 3, listOf(candidate(page, "en"))) })
        assertEquals(listOf(1, 2, 3), visited)
        assertEquals(3, result.size)
    }

    @Test fun `pager is cancelled when content changes`() = runTest {
        val started = CompletableDeferred<Unit>()
        val job = launch { SubtitlePager.load(fetch = { started.complete(Unit); awaitCancellation() }) }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun `srt parser handles windows encoding and positive delay`() {
        val source = "1\r\n00:00:01,000 --> 00:00:03,000\r\nAção\r\n"
        val decoded = SubtitleText.decode(source.toByteArray(charset("windows-1252")))
        assertEquals(SubtitleFormat.SRT, SubtitleText.validate(decoded))
        assertTrue(SubtitleText.shift(decoded, 500).contains("00:00:01,500 --> 00:00:03,500"))
    }

    @Test fun `webvtt parser clamps negative delay at zero`() {
        val source = "WEBVTT\n\n00:01.000 --> 00:03.000\nHello\n"
        assertEquals(SubtitleFormat.WEBVTT, SubtitleText.validate(source))
        assertTrue(SubtitleText.shift(source, -2_000).contains("00:00.000 --> 00:01.000"))
    }

    @Test fun `cache reuses file expires entries and enforces limit`() {
        val root = createTempDirectory("droplay-subs-").toFile()
        var now = 1_000_000L
        try {
            val cache = SubtitleFileCache(root, { now }, expiryMs = 1_000, maxBytes = 80)
            val raw = cache.store(5, "1\n00:00:01,000 --> 00:00:02,000\nOi\n", SubtitleFormat.SRT)
            assertEquals(raw, cache.raw(5))
            assertEquals(cache.prepare(5, raw, 500), cache.playable(5, 500))
            now += 2_000
            assertNull(cache.raw(5))
            cache.prune()
            assertTrue(root.listFiles().orEmpty().sumOf(File::length) <= 80)
        } finally { root.deleteRecursively() }
    }

    @Test fun `friendly errors cover timeout and rate limit`() {
        assertTrue(friendlySubtitleError(OpenSubtitlesException(429, "rate", 9)).contains("9 segundos"))
        assertTrue(friendlySubtitleError(java.net.SocketTimeoutException()).contains("demorou"))
        assertEquals("Operação cancelada.", friendlySubtitleError(CancellationException()))
    }

    @Test fun `login base host is normalized to official api path`() {
        assertEquals("https://api.opensubtitles.com/api/v1", normalizeOpenSubtitlesBase("api.opensubtitles.com"))
        assertEquals("https://vip-api.opensubtitles.com/api/v1", normalizeOpenSubtitlesBase("https://vip-api.opensubtitles.com/api/v1/"))
        assertEquals(OpenSubtitlesConfiguration.DEFAULT_API_URL, normalizeOpenSubtitlesBase("http://example.com"))
    }

    private fun movie(name: String = "Movie", year: Int? = null, tmdbId: Int? = null) = MediaEntry(
        id = "movie:1", name = name, url = "https://example/video.mp4", kind = MediaKind.MOVIE, year = year, tmdbId = tmdbId,
    )

    private fun candidate(id: Int, language: String, rating: Double = 0.0, downloads: Int = 0, trusted: Boolean = false) = SubtitleCandidate(
        id, language, "Release", "$id.srt", rating, downloads, trusted, false, false, false, false,
    )
}
