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

    @Test fun migrationFrom2AddsCategoryFlagsWithoutLosingCatalog() {
        helper.createDatabase(DB_V2, 2).apply {
            execSQL("INSERT INTO live_categories(playlistId,categoryId,name,syncVersion,normalizedName,isBlocked,classificationVersion) VALUES('p','10','Infantil',1,'infantil',0,2)")
            execSQL("INSERT INTO favorites(playlistId,mediaId,createdAt) VALUES('p','live:1',10)")
            close()
        }
        helper.runMigrationsAndValidate(DB_V2, 3, true, CatalogDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT name,presentationOrder,isAdult,isKids FROM live_categories WHERE playlistId='p' AND categoryId='10'").use {
                it.moveToFirst()
                assertEquals("Infantil", it.getString(0))
                assertEquals(0, it.getInt(1))
                assertEquals(0, it.getInt(2))
                assertEquals(0, it.getInt(3))
            }
            db.query("SELECT COUNT(*) FROM favorites WHERE playlistId='p' AND mediaId='live:1'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_V2)
    }

    private companion object {
        const val DB = "classification-migration-test"
        const val DB_V2 = "category-migration-test"
    }
}
