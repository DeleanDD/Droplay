package com.droplay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogOrganizerTest {
    @Test fun filtersProtectedAndAdministrativeContentByDefault() {
        val entries = listOf(
            entry("Filme comum", MediaKind.MOVIE, "Ação"),
            entry("Conteúdo XXX", MediaKind.MOVIE, "Adultos"),
            entry("FILME CINEMA 2026", MediaKind.MOVIE, "Lançamentos"),
            entry("CHECKLIST", MediaKind.LIVE, "Canais do cliente"),
            entry("Série Brasil Paralelo", MediaKind.SERIES, "Documentários"),
        )
        val visible = CatalogOrganizer.visibleEntries(entries, showAdult = false, showCinema = false)
        assertEquals(listOf("Filme comum"), visible.map(MediaEntry::name))
    }

    @Test fun hidesCamMoviesAndRecognizesBrazilianGroups() {
        assertTrue(CatalogOrganizer.isCinema(entry("Zootopia 2 [CAM]", MediaKind.MOVIE, "Cinema")))
        assertTrue(CatalogOrganizer.isNational(entry("Minha Novela", MediaKind.SERIES, "Novelas BR")))
        assertEquals("Lançamentos", CatalogOrganizer.cleanCategory("❖ Lancamentos²", MediaKind.MOVIE))
    }

    @Test fun mergesCategoriesAndDubbedSubtitledVariants() {
        assertEquals("Ação", CatalogOrganizer.cleanCategory("FILMES | Ação²", MediaKind.MOVIE))
        assertEquals("Disney Plus", CatalogOrganizer.cleanCategory("Séries Disney Plus", MediaKind.SERIES))
        val dubbed = entry("Meu Filme", MediaKind.MOVIE, "Filmes Ação")
        val subtitled = entry("Meu Filme [L]", MediaKind.MOVIE, "Filmes Legendados")
        assertEquals(1, CatalogOrganizer.collapseMovieVariants(listOf(subtitled, dubbed)).size)
        assertEquals(2, CatalogOrganizer.variantsFor(dubbed, listOf(subtitled, dubbed)).size)
        assertFalse(CatalogOrganizer.isSubtitled(dubbed))
        assertTrue(CatalogOrganizer.isSubtitled(subtitled))
    }

    @Test fun classifiesKidsNationalAndLiveCategories() {
        assertTrue(CatalogOrganizer.isKids(entry("Discovery Kids", MediaKind.LIVE, "Canais infantis")))
        assertTrue(CatalogOrganizer.isNational(entry("Cidade de Deus", MediaKind.MOVIE, "Cinema nacional")))
        assertEquals(CatalogOrganizer.FOOTBALL, CatalogOrganizer.category(entry("Jogo de hoje", MediaKind.LIVE, "Futebol ao vivo")))
        assertEquals("Globo", CatalogOrganizer.category(entry("Globo Sudeste", MediaKind.LIVE, "Globo Sul")))
        assertEquals(CatalogOrganizer.FOOTBALL, CatalogOrganizer.category(entry("PPV 01", MediaKind.LIVE, "PayPerView")))
        assertEquals(CatalogOrganizer.FOOTBALL, CatalogOrganizer.category(entry("Canal 01", MediaKind.LIVE, "Jogos Principais")))
        assertEquals("Filmes", CatalogOrganizer.category(entry("Canal Cinema", MediaKind.LIVE, "Canais Filmes")))
    }

    @Test fun blocksBrasilParareloEverywhereAndPreparesViewsOnce() {
        val blockedLive = entry("Canal de teste", MediaKind.LIVE, "CANAIS | BRASIL PARARELO")
        val dubbed = entry("Meu Filme 2026", MediaKind.MOVIE, "Ação").copy(year = 2026)
        val subtitled = entry("Meu Filme 2026 [L]", MediaKind.MOVIE, "Legendados").copy(year = 2026)
        val prepared = CatalogOrganizer.prepare(listOf(blockedLive, dubbed, subtitled), showAdult = false, showCinema = false)
        assertEquals(listOf(dubbed.id), prepared.entries.map(MediaEntry::id))
        assertEquals(2, prepared.movieVariants[CatalogOrganizer.movieVariantKey(dubbed)]?.size)
    }

    @Test fun separatesBrazilianNovelsFromOtherSeries() {
        assertTrue(CatalogOrganizer.isNovel(entry("Minha Novela", MediaKind.SERIES, "Novelas BR")))
        assertFalse(CatalogOrganizer.isNovel(entry("Drama Turco", MediaKind.SERIES, "Novelas Turcas")))
    }

    private fun entry(name: String, kind: MediaKind, group: String) = MediaEntry(name, name, "https://example.test/$name", kind, group)
}
