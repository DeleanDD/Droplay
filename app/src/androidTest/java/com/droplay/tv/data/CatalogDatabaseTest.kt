package com.droplay.tv.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogDatabaseTest {
    @Test fun upsertPrunesOnlyAfterValidBatchAndRollsBackInvalidBatch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CatalogDatabase::class.java).build()
        val dao = db.catalogDao(); val id = "playlist"; val now = 10L
        val first = LiveStreamEntity(id, "1", "news", "Canal 1", "canal 1", null, null, 0, "ts", 1)
        dao.replaceLive(id, listOf(LiveCategoryEntity(id, "news", "Notícias", 1)), listOf(first), SyncMetadataEntity(id, "LIVE", now, now, null, 1, 1, null, null, "Success"))
        runCatching { dao.replaceLive(id, emptyList(), emptyList(), SyncMetadataEntity(id, "LIVE", 20, 20, null, 2, 0, null, null, "Success")) }
        assertEquals(listOf(first), dao.live(id))
        val second = first.copy(streamId = "2", name = "Canal 2", syncVersion = 3)
        dao.replaceLive(id, listOf(LiveCategoryEntity(id, "news", "Notícias", 3)), listOf(second), SyncMetadataEntity(id, "LIVE", 30, 30, null, 3, 1, null, null, "Success"))
        assertEquals(listOf(second), dao.live(id))
        db.close()
    }
}
