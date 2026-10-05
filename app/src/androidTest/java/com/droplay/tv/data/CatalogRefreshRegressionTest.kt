package com.droplay.tv.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogRefreshRegressionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun key(source: PlaylistSource.Xtream): String = MessageDigest.getInstance("SHA-256")
        .digest("xtream:${source.server}|${source.username}|${source.password}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Test fun reclassificationVisitsEveryOutdatedRowOnceAndTerminates() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).build()
        try {
            val source = PlaylistSource.Xtream("https://fixture.invalid", "fixture", "fixture")
            val id = key(source)
            val dao = db.catalogDao()
            dao.upsertLive((0..1000).map { n -> LiveStreamEntity(id, "$n", "kids", "Canal $n", "", "", null, null, 0, "ts", false, false, false, false, false, null, 2, 1) })
            dao.upsertVod((0..1000).map { n -> VodStreamEntity(id, "$n", "kids", "Filme $n", "", "", null, 0, "mp4", null, null, null, 0, n == 0, false, false, false, n == 0, null, 2, 1) })
            dao.upsertSeries((0..1000).map { n -> SeriesEntity(id, "$n", "kids", "Série $n", "", "", null, null, 0, null, null, null, false, false, false, false, false, null, 2, 1) })
            dao.upsertLiveCategories(listOf(LiveCategoryEntity(id, "kids", "Infantil", "infantil", false, 2, 1)))
            dao.upsertVodCategories(listOf(VodCategoryEntity(id, "kids", "Infantil", "infantil", false, 2, 1)))
            dao.upsertSeriesCategories(listOf(SeriesCategoryEntity(id, "kids", "Infantil", "infantil", false, 2, 1)))
            val repository = DroplayRepository(context, db)
            val metrics = withTimeout(20_000) { withContext(Dispatchers.IO) { repository.reclassifyIfNeeded(source) } }
            assertEquals(3003, metrics?.received)
            assertEquals(0, dao.outdatedLive(id, ContentClassificationEngine.VERSION))
            assertEquals(0, dao.outdatedVod(id, ContentClassificationEngine.VERSION))
            assertEquals(0, dao.outdatedSeries(id, ContentClassificationEngine.VERSION))
            assertTrue(dao.vod(id).first { it.streamId == "0" }.isAdult)
            assertFalse(dao.vod(id).first { it.streamId == "0" }.isKids)
            assertNull(repository.reclassifyIfNeeded(source))
        } finally { db.close() }
    }

    @Test fun warmCacheSkipsRequestsAndExpiredVodAddsChangesAndRemovesTitles() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).build()
        val server = MockWebServer()
        var updated = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = when (request.requestUrl?.queryParameter("action")) {
                    null -> """{"user_info":{"auth":1,"status":"Active"}}"""
                    "get_live_categories", "get_vod_categories", "get_series_categories" -> """[{"category_id":"1","category_name":"Ação"}]"""
                    "get_live_streams" -> """[{"stream_id":"1","category_id":"1","name":"Canal"}]"""
                    "get_series" -> """[{"series_id":"1","category_id":"1","name":"Série"}]"""
                    "get_vod_streams" -> if (updated)
                        """[{"stream_id":"2","category_id":"1","name":"Filme atualizado","added":"200"},{"stream_id":"3","category_id":"1","name":"Novidade","added":"300"}]"""
                    else """[{"stream_id":"1","category_id":"1","name":"Saiu do catálogo","added":"100"},{"stream_id":"2","category_id":"1","name":"Filme antigo","added":"100"}]"""
                    else -> "[]"
                }
                return MockResponse().setBody(json).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        try {
            val source = PlaylistSource.Xtream(server.url("/").toString(), "fixture", "fixture")
            val repository = DroplayRepository(context, db)
            val initial = withContext(Dispatchers.IO) { repository.load(source, save = false, force = true) }
            assertEquals(4, initial.entries.size)
            assertEquals(7, server.requestCount)
            assertFalse(repository.isRefreshDue(source))
            withContext(Dispatchers.IO) { repository.load(source, save = false, force = true) }
            assertEquals("Fresh sections must not even revalidate over HTTP", 7, server.requestCount)
            val dao = db.catalogDao()
            val metadata = dao.metadata(key(source)).first { it.section == "VOD" }
            dao.upsertMetadata(metadata.copy(lastSuccessfulSyncAt = 1))
            updated = true
            val refreshed = withContext(Dispatchers.IO) { repository.load(source, save = false, force = true) }
            assertEquals("Only authentication plus VOD categories/list should be requested", 10, server.requestCount)
            assertFalse(refreshed.entries.any { it.id == "movie:1" })
            assertEquals("Filme atualizado", refreshed.entries.first { it.id == "movie:2" }.name)
            assertTrue(refreshed.entries.any { it.id == "movie:3" })
            assertEquals(refreshed.entries, repository.cached(source)?.entries)
            assertEquals("movie:3", CatalogOrganizer.prepare(refreshed.entries, false, false).recentMovies.first().id)
        } finally { server.shutdown(); db.close() }
    }
}
