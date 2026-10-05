package com.droplay.tv.data

import android.util.Log
import kotlin.system.measureTimeMillis
import org.junit.Assert.*
import org.junit.Test

class CatalogPerformanceTest {
    @Test fun preparesAndSearchesFiftyThousandEntriesOnAndroidTv() {
        val entries = (0 until 50_000).map { index ->
            MediaEntry("movie:$index", "Filme Ágil $index", "", MediaKind.MOVIE, "Filmes Ação",
                year = 2026, addedAt = index.toLong(), normalizedName = "filme agil $index",
                normalizedCategoryName = "filmes acao", classificationVersion = ContentClassificationEngine.VERSION)
        }
        val initialMs = measureTimeMillis { assertEquals(50_000, CatalogOrganizer.prepareInitial(entries).movies.size) }
        lateinit var prepared: PreparedCatalog
        val prepareMs = measureTimeMillis { prepared = CatalogOrganizer.prepare(entries, false, false) }
        val searchMs = measureTimeMillis {
            assertEquals("movie:49999", SearchNormalizer.search(prepared.entries, "agil 49999").single().id)
        }
        val sortMs = measureTimeMillis {
            assertEquals("movie:49999", CatalogOrganizer.sort(prepared.movies, ContentSort.YEAR_DESC, emptyMap()).first().id)
        }
        assertEquals(50_000, prepared.categoryIndex[MediaKind.MOVIE]?.get("Ação")?.size)
        Log.i("DroplayPerformance", "items=50000 initialMs=$initialMs prepareMs=$prepareMs searchMs=$searchMs sortMs=$sortMs")
    }
}
