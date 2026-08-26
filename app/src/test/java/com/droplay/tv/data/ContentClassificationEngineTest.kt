package com.droplay.tv.data

import org.junit.Assert.*
import org.junit.Test

class ContentClassificationEngineTest {
    private fun classify(name: String, category: String = "Filmes", kind: MediaKind = MediaKind.MOVIE, country: String? = null, genre: String? = null, rating: String? = null) =
        ContentClassificationEngine.classify(ClassificationInput(name, category, kind, country = country, genre = genre, parentalRating = rating))

    @Test fun blocksExplicitAdultCategoryButNotOrdinary18RatedMovie() {
        assertTrue(classify("Título", "XXX Adultos").isAdult)
        assertTrue(ContentClassificationEngine.isBlockedCategory("Filmes +18"))
        assertFalse(classify("Drama", "Lançamentos", rating = "18 anos").isAdult)
        assertFalse(classify("Drama classificação 18 anos").isAdult)
    }

    @Test fun blocksCinemaCapturesAndKeepsGoodReleases() {
        assertTrue(classify("Filme 2026 HDCAM").isHidden)
        assertTrue(classify("Filme 2026 CAM").isLowQualityCinema)
        assertTrue(classify("Filme 2026 TS 1080p").isLowQualityCinema)
        assertFalse(classify("Filme 2026 WEB-DL 1080p").isLowQualityCinema)
        assertFalse(classify("Filme BluRay 2160p HDR").isLowQualityCinema)
        assertFalse(classify("Cambridge: uma história").isLowQualityCinema)
        assertFalse(classify("Agentes TS").isLowQualityCinema)
        assertFalse(classify("Código TC").isLowQualityCinema)
        assertFalse(classify("Canal TS", "TV aberta", MediaKind.LIVE).isLowQualityCinema)
        assertFalse(classify("Clássicos Telecine 2026").isLowQualityCinema)
    }

    @Test fun identifiesBrazilianProductionsWithoutTreatingDubbedAsNational() {
        assertFalse(classify("Filme americano dublado", "Dublados").isBrazilian)
        assertTrue(classify("Filme", country = "Brasil").isBrazilian)
        assertTrue(classify("Coprodução", country = "Estados Unidos, Brazil").isBrazilian)
        assertFalse(classify("Filme português", country = "Portugal").isBrazilian)
        assertTrue(classify("Cidade", "Cinema Nacional").isBrazilian)
    }

    @Test fun kidsRequiresPositiveEvidenceAndNeverIncludesHiddenOrAdultAnimation() {
        assertTrue(classify("Aventura", "Filmes Infantis").isKids)
        assertFalse(classify("Family Guy", "Desenhos").isKids)
        assertFalse(classify("Animação adulta", "Animação", genre = "adult swim").isKids)
        assertFalse(classify("Aventura HDCAM", "Filmes Infantis").isKids)
        assertFalse(classify("XXX Brasil", "Infantil Nacional", country = "Brasil").isKids)
        assertFalse(classify("XXX Brasil", "Infantil Nacional", country = "Brasil").isBrazilian)
        val both = classify("Turma", "Filmes Infantis Nacionais", country = "Brasil")
        assertTrue(both.isKids); assertTrue(both.isBrazilian)
    }

    @Test fun normalizationAndRulesAreVersioned() {
        val value = classify("[BR] Filme_Infantil - 1080p", "Kids")
        assertEquals("br filme infantil 1080p", value.normalizedName)
        assertEquals(ContentClassificationEngine.VERSION, value.version)
    }

    @Test fun classifiesLargeCatalogInSinglePass() {
        val started = System.nanoTime()
        val results = (0 until 100_000).asSequence().map { classify("Filme $it WEB-DL 1080p", if (it % 20 == 0) "Infantil" else "Ação") }.toList()
        assertEquals(5_000, results.count(ContentClassification::isKids))
        assertEquals(0, results.count(ContentClassification::isHidden))
        assertTrue((System.nanoTime() - started) / 1_000_000L < 20_000L)
    }
}
