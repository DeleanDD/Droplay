package com.droplay.tv.data

import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class XtreamArchitectureTest {
    private val source = PlaylistSource.Xtream("https://iptv.example/", "user name", "p@ss/word")

    @Test fun normalizesBaseAndBuildsAuthenticatedUrlsOnlyOnDemand() {
        assertEquals("https://iptv.example", XtreamUrlBuilder.normalizeBase(" https://iptv.example/// "))
        assertEquals("https://iptv.example/live/user%20name/p%40ss%2Fword/42.ts", XtreamUrlBuilder.live(source, "42"))
        assertEquals("https://iptv.example/movie/user%20name/p%40ss%2Fword/8.mkv", XtreamUrlBuilder.movie(source, "8", "mkv"))
        assertEquals("https://iptv.example/series/user%20name/p%40ss%2Fword/9.mp4", XtreamUrlBuilder.episode(source, "9"))
    }

    @Test fun removesCredentialsFromQueryAndPlaybackPaths() {
        val raw = "GET https://x/player_api.php?username=john&password=secret /live/john/secret/4.ts token=abc"
        val safe = CredentialSanitizer.sanitize(raw)
        assertFalse(safe.contains("secret")); assertFalse(safe.contains("john")); assertFalse(safe.contains("abc"))
        assertTrue(safe.contains("password=***")); assertTrue(safe.contains("/live/***/***/"))
    }

    @Test fun appliesSectionTtl() {
        val now = 1_000_000_000L
        assertFalse(SyncPolicy.isDue(now - SyncPolicy.LIVE_TTL_MS + 1, CatalogSection.LIVE, now))
        assertTrue(SyncPolicy.isDue(now - SyncPolicy.LIVE_TTL_MS, CatalogSection.LIVE, now))
        assertFalse(SyncPolicy.isDue(now - SyncPolicy.VOD_TTL_MS + 1, CatalogSection.VOD, now))
    }

    @Test fun retriesOnlyTransientFailuresAndNeverTlsOrAuth() {
        assertTrue(Network.isTransient(SocketTimeoutException()))
        assertTrue(Network.isTransient(HttpStatusException(503)))
        assertFalse(Network.isTransient(HttpStatusException(401)))
        assertFalse(Network.isTransient(SSLHandshakeException("invalid certificate")))
    }

    @Test fun searchIsLocalAccentInsensitiveAndRequiresThreeCharacters() {
        val item = MediaEntry("1", "Ação Total", "", MediaKind.MOVIE, "Lançamentos")
        assertTrue(SearchNormalizer.matches(item, "acao"))
        assertFalse(SearchNormalizer.matches(item, "aç".take(2)))
    }

    @Test fun largeLocalSearchIsBounded() {
        val catalog = (0 until 50_000).map { MediaEntry("$it", "Filme número $it", "", MediaKind.MOVIE) }
        val result = catalog.asSequence().filter { SearchNormalizer.matches(it, "numero 499") }.take(240).toList()
        assertTrue(result.isNotEmpty()); assertTrue(result.size <= 240)
    }

    @Test fun retriesTransientHttpButNotAuthentication() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setBody("[]"))
            assertEquals("[]", Network.text(server.url("/transient").toString()))
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { Network.text(server.url("/auth").toString()) }.exceptionOrNull() is HttpStatusException)
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

}
