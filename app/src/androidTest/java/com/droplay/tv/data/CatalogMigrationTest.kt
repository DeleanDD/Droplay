package com.droplay.tv.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), CatalogDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun migrationFrom1PreservesFavoritesAndProgress() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO favorites(playlistId,mediaId,createdAt) VALUES('p','movie:1',10)")
            execSQL("INSERT INTO watch_progress(playlistId,mediaId,positionMs,durationMs,watchedAt) VALUES('p','movie:1',100,1000,20)")
            close()
        }
        helper.runMigrationsAndValidate(DB, 2, true, CatalogDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT COUNT(*) FROM favorites WHERE playlistId='p' AND mediaId='movie:1'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.query("SELECT positionMs FROM watch_progress WHERE playlistId='p' AND mediaId='movie:1'").use { it.moveToFirst(); assertEquals(100L, it.getLong(0)) }
        }
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB)
    }

    private companion object { const val DB = "classification-migration-test" }
}
