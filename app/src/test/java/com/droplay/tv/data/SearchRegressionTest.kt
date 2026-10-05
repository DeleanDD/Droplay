package com.droplay.tv.data

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SearchRegressionTest {
    private fun entry(id: String, name: String, group: String = "Filmes") =
        MediaEntry(id, name, "", MediaKind.MOVIE, group,
            normalizedName = ContentClassificationEngine.normalize(name),
            normalizedCategoryName = ContentClassificationEngine.normalize(group))

    @Test fun findsAccentsPunctuationAndSeparatedWords() {
        val entries = listOf(entry("1", "Missão: Impossível - Acerto Final"), entry("2", "Super Girl [L]"))
        assertEquals(listOf("1"), SearchNormalizer.search(entries, "missao final").map { it.id })
        assertEquals(listOf("1"), SearchNormalizer.search(entries, "MISSÃO: IMPOSSÍVEL").map { it.id })
        assertEquals(listOf("2"), SearchNormalizer.search(entries, "supergirl").map { it.id })
        assertTrue(SearchNormalizer.search(entries, "su").isEmpty())
        assertTrue(SearchNormalizer.search(entries, "---").isEmpty())
    }

    @Test fun categoryMatchesCannotHideTitleAtEndOfLargeCatalog() {
        val entries = (0 until 50_000).map { entry("$it", "Título $it", "Ação") } + entry("wanted", "Ação Total")
        val results = SearchNormalizer.search(entries, "acao", limit = 240)
        assertEquals("wanted", results.first().id)
        assertEquals(240, results.size)
    }

    @Test fun cancelledSearchDoesNotKeepScanning() {
        val entries = (0 until 10_000).map { entry("$it", "Título $it") }
        var checks = 0
        try {
            SearchNormalizer.search(entries, "inexistente") {
                if (++checks == 2) throw CancellationException()
            }
            fail("Search should propagate cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, checks)
        }
    }

    @Test fun moviesUseVodTimestampIncludingLegacyMetadata() {
        val now = 1_000_000_000L
        fun metadata(section: String, at: Long) = SyncMetadataEntity("p", section, at, at, null, at, 1, null, null, "Success")
        assertEquals(CatalogSection.VOD, SyncPolicy.section(MediaKind.MOVIE))
        assertEquals(now, SyncPolicy.lastSuccess(listOf(metadata("MOVIE", now)), CatalogSection.VOD))
        assertEquals(now, SyncPolicy.lastSuccess(listOf(metadata("MOVIE", now - 1), metadata("VOD", now)), CatalogSection.VOD))
        assertFalse(SyncPolicy.isDue(now, CatalogSection.VOD, now + 100))
    }
}
