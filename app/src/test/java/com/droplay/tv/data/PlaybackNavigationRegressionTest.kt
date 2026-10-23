package com.droplay.tv.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackNavigationRegressionTest {
    @Test fun episodeUsesSeriesEndpointEvenWhenRepresentedAsMovie() {
        val source = PlaylistSource.Xtream("https://example.com", "user", "pass")
        val episode = MediaEntry("episode:123", "Episódio", "", MediaKind.MOVIE, streamId = "123", containerExtension = "mkv")
        assertEquals("https://example.com/series/user/pass/123.mkv", XtreamUrlBuilder.forMedia(source, episode))
    }

    @Test fun numberedCategoryDuplicatesMergeWithoutRemovingSequelNames() {
        assertEquals("Terror", CatalogOrganizer.cleanCategory("2 Terror", MediaKind.MOVIE))
        assertEquals("Copmedia", CatalogOrganizer.cleanCategory("2 Copmedia", MediaKind.MOVIE))
        assertEquals("Terror", CatalogOrganizer.cleanCategory("Filmes 2 Terror", MediaKind.MOVIE))
        assertEquals("Ação 2", CatalogOrganizer.cleanCategory("Filmes Ação 2", MediaKind.MOVIE))
        assertEquals("Ação", CatalogOrganizer.cleanCategory("Filmes Ação²", MediaKind.MOVIE))
        assertEquals("2026", CatalogOrganizer.cleanCategory("Filmes 2026", MediaKind.MOVIE))
    }

    @Test fun nationalNewsIncludesNetworksWithoutBrazilianCategoryTag() {
        val news = MediaEntry("live:1", "GloboNews HD", "", MediaKind.LIVE, "Globo")
        val prepared = CatalogOrganizer.prepare(listOf(news), false, false)
        assertEquals(listOf(news), prepared.nationalNews)
        assertTrue(prepared.categoryIndex[MediaKind.LIVE].orEmpty().containsKey("Notícias"))
        assertFalse(CatalogOrganizer.isNationalNews(news.copy(name = "Globo SP")))
    }
}
