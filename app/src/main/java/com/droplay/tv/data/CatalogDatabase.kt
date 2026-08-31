package com.droplay.tv.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "playlist_accounts", indices = [Index(value = ["sourceKey"], unique = true)])
data class PlaylistAccountEntity(@PrimaryKey val playlistId: String, val sourceKey: String, val baseUrl: String, val username: String, val credentialAlias: String, val createdAt: Long)

@Entity(tableName = "live_categories", primaryKeys = ["playlistId", "categoryId"], indices = [Index("playlistId"), Index(value = ["playlistId", "name"]), Index(value = ["playlistId", "normalizedName"]), Index(value = ["playlistId", "presentationOrder"])])
data class LiveCategoryEntity(val playlistId: String, val categoryId: String, val name: String, val normalizedName: String, val isBlocked: Boolean, val classificationVersion: Int, val syncVersion: Long, val presentationOrder: Int = 0, val isAdult: Boolean = false, val isLowQualityCinema: Boolean = false, val isKids: Boolean = false, val isBrazilian: Boolean = false, val isHidden: Boolean = false, val classificationReason: String? = null)
@Entity(tableName = "vod_categories", primaryKeys = ["playlistId", "categoryId"], indices = [Index("playlistId"), Index(value = ["playlistId", "name"]), Index(value = ["playlistId", "normalizedName"]), Index(value = ["playlistId", "presentationOrder"])])
data class VodCategoryEntity(val playlistId: String, val categoryId: String, val name: String, val normalizedName: String, val isBlocked: Boolean, val classificationVersion: Int, val syncVersion: Long, val presentationOrder: Int = 0, val isAdult: Boolean = false, val isLowQualityCinema: Boolean = false, val isKids: Boolean = false, val isBrazilian: Boolean = false, val isHidden: Boolean = false, val classificationReason: String? = null)
@Entity(tableName = "series_categories", primaryKeys = ["playlistId", "categoryId"], indices = [Index("playlistId"), Index(value = ["playlistId", "name"]), Index(value = ["playlistId", "normalizedName"]), Index(value = ["playlistId", "presentationOrder"])])
data class SeriesCategoryEntity(val playlistId: String, val categoryId: String, val name: String, val normalizedName: String, val isBlocked: Boolean, val classificationVersion: Int, val syncVersion: Long, val presentationOrder: Int = 0, val isAdult: Boolean = false, val isLowQualityCinema: Boolean = false, val isKids: Boolean = false, val isBrazilian: Boolean = false, val isHidden: Boolean = false, val classificationReason: String? = null)

@Entity(tableName = "live_streams", primaryKeys = ["playlistId", "streamId"], indices = [Index(value = ["playlistId", "categoryId"]), Index(value = ["playlistId", "normalizedName"]), Index(value = ["playlistId", "addedAt"]), Index(value = ["playlistId", "isHidden"]), Index(value = ["playlistId", "isKids", "isHidden"]), Index(value = ["playlistId", "isBrazilian", "isHidden"]), Index(value = ["playlistId", "isAdult", "isLowQualityCinema"]), Index(value = ["playlistId", "isKids", "isAdult", "isLowQualityCinema"]), Index(value = ["playlistId", "isBrazilian", "isAdult", "isLowQualityCinema"])])
data class LiveStreamEntity(val playlistId: String, val streamId: String, val categoryId: String, val name: String, val normalizedName: String, val normalizedCategoryName: String, val icon: String?, val epgId: String?, val addedAt: Long, val extension: String, val isAdult: Boolean, val isLowQualityCinema: Boolean, val isKids: Boolean, val isBrazilian: Boolean, val isHidden: Boolean, val classificationReason: String?, val classificationVersion: Int, val syncVersion: Long)
@Entity(tableName = "vod_streams", primaryKeys = ["playlistId", "streamId"], indices = [Index(value = ["playlistId", "categoryId"]), Index(value = ["playlistId", "normalizedName"]), Index(value = ["playlistId", "addedAt"]), Index(value = ["playlistId", "isHidden"]), Index(value = ["playlistId", "isKids", "isHidden"]), Index(value = ["playlistId", "isBrazilian", "isHidden"]), Index(value = ["playlistId", "isAdult", "isLowQualityCinema"]), Index(value = ["playlistId", "isKids", "isAdult", "isLowQualityCinema"]), Index(value = ["playlistId", "isBrazilian", "isAdult", "isLowQualityCinema"])])
data class VodStreamEntity(val playlistId: String, val streamId: String, val categoryId: String, val name: String, val normalizedName: String, val normalizedCategoryName: String, val icon: String?, val addedAt: Long, val extension: String, val year: Int?, val rating: Double?, val description: String?, val durationMs: Long, val isAdult: Boolean, val isLowQualityCinema: Boolean, val isKids: Boolean, val isBrazilian: Boolean, val isHidden: Boolean, val classificationReason: String?, val classificationVersion: Int, val syncVersion: Long)
@Entity(tableName = "series", primaryKeys = ["playlistId", "seriesId"], indices = [Index(value = ["playlistId", "categoryId"]), Index(value = ["playlistId", "normalizedName"]), Index(value = ["playlistId", "addedAt"]), Index(value = ["playlistId", "isHidden"]), Index(value = ["playlistId", "isKids", "isHidden"]), Index(value = ["playlistId", "isBrazilian", "isHidden"]), Index(value = ["playlistId", "isAdult", "isLowQualityCinema"]), Index(value = ["playlistId", "isKids", "isAdult", "isLowQualityCinema"]), Index(value = ["playlistId", "isBrazilian", "isAdult", "isLowQualityCinema"])])
data class SeriesEntity(val playlistId: String, val seriesId: String, val categoryId: String, val name: String, val normalizedName: String, val normalizedCategoryName: String, val cover: String?, val backdrop: String?, val addedAt: Long, val year: Int?, val rating: Double?, val description: String?, val isAdult: Boolean, val isLowQualityCinema: Boolean, val isKids: Boolean, val isBrazilian: Boolean, val isHidden: Boolean, val classificationReason: String?, val classificationVersion: Int, val syncVersion: Long)
@Entity(tableName = "episodes", primaryKeys = ["playlistId", "episodeId"], indices = [Index(value = ["playlistId", "seriesId"]), Index(value = ["playlistId", "seriesId", "season", "episode"])])
data class EpisodeEntity(val playlistId: String, val episodeId: String, val seriesId: String, val name: String, val season: Int?, val episode: Int?, val extension: String, val icon: String?, val durationMs: Long, val cachedAt: Long)
@Entity(tableName = "media_details", primaryKeys = ["playlistId", "mediaId"], indices = [Index(value = ["playlistId", "cachedAt"])])
data class MediaDetailEntity(val playlistId: String, val mediaId: String, val description: String?, val logo: String?, val backdrop: String?, val year: Int?, val durationMs: Long, val subtitlesJson: String, val tmdbId: Int?, val streamId: String?, val extension: String?, val cachedAt: Long)

@Entity(tableName = "sync_metadata", primaryKeys = ["playlistId", "section"])
data class SyncMetadataEntity(val playlistId: String, val section: String, val lastAttemptAt: Long, val lastSuccessfulSyncAt: Long, val lastError: String?, val syncVersion: Long, val itemCount: Int, val etag: String?, val lastModified: String?, val state: String)
@Entity(tableName = "favorites", primaryKeys = ["playlistId", "mediaId"], indices = [Index("playlistId")])
data class FavoriteEntity(val playlistId: String, val mediaId: String, val createdAt: Long)
@Entity(tableName = "watch_progress", primaryKeys = ["playlistId", "mediaId"], indices = [Index(value = ["playlistId", "watchedAt"])])
data class WatchProgressEntity(val playlistId: String, val mediaId: String, val positionMs: Long, val durationMs: Long, val watchedAt: Long)

@Dao
interface CatalogDao {
    @Query("SELECT COUNT(*) FROM live_streams WHERE playlistId=:playlistId") suspend fun liveCount(playlistId: String): Int
    @Query("SELECT COUNT(*) FROM vod_streams WHERE playlistId=:playlistId") suspend fun vodCount(playlistId: String): Int
    @Query("SELECT COUNT(*) FROM series WHERE playlistId=:playlistId") suspend fun seriesCount(playlistId: String): Int
    @Query("SELECT * FROM live_categories WHERE playlistId=:playlistId ORDER BY presentationOrder") suspend fun liveCategories(playlistId: String): List<LiveCategoryEntity>
    @Query("SELECT * FROM vod_categories WHERE playlistId=:playlistId ORDER BY presentationOrder") suspend fun vodCategories(playlistId: String): List<VodCategoryEntity>
    @Query("SELECT * FROM series_categories WHERE playlistId=:playlistId ORDER BY presentationOrder") suspend fun seriesCategories(playlistId: String): List<SeriesCategoryEntity>
    @Query("SELECT * FROM live_streams WHERE playlistId=:playlistId") suspend fun live(playlistId: String): List<LiveStreamEntity>
    @Query("SELECT * FROM vod_streams WHERE playlistId=:playlistId") suspend fun vod(playlistId: String): List<VodStreamEntity>
    @Query("SELECT * FROM series WHERE playlistId=:playlistId") suspend fun series(playlistId: String): List<SeriesEntity>
    @Query("SELECT * FROM live_streams WHERE playlistId=:playlistId ORDER BY streamId LIMIT :limit OFFSET :offset") suspend fun liveBatch(playlistId: String, limit: Int, offset: Int): List<LiveStreamEntity>
    @Query("SELECT * FROM vod_streams WHERE playlistId=:playlistId ORDER BY streamId LIMIT :limit OFFSET :offset") suspend fun vodBatch(playlistId: String, limit: Int, offset: Int): List<VodStreamEntity>
    @Query("SELECT * FROM series WHERE playlistId=:playlistId ORDER BY seriesId LIMIT :limit OFFSET :offset") suspend fun seriesBatch(playlistId: String, limit: Int, offset: Int): List<SeriesEntity>
    @Query("SELECT * FROM live_streams WHERE playlistId=:playlistId AND isHidden=0 AND normalizedName LIKE '%' || :query || '%' LIMIT :limit OFFSET :offset") suspend fun searchLive(playlistId: String, query: String, limit: Int, offset: Int): List<LiveStreamEntity>
    @Query("SELECT * FROM vod_streams WHERE playlistId=:playlistId AND isHidden=0 AND normalizedName LIKE '%' || :query || '%' LIMIT :limit OFFSET :offset") suspend fun searchVod(playlistId: String, query: String, limit: Int, offset: Int): List<VodStreamEntity>
    @Query("SELECT * FROM series WHERE playlistId=:playlistId AND isHidden=0 AND normalizedName LIKE '%' || :query || '%' LIMIT :limit OFFSET :offset") suspend fun searchSeries(playlistId: String, query: String, limit: Int, offset: Int): List<SeriesEntity>
    @Query("SELECT * FROM vod_streams WHERE playlistId=:playlistId AND isKids=1 AND isHidden=0 LIMIT :limit OFFSET :offset") suspend fun kidsVod(playlistId: String, limit: Int, offset: Int): List<VodStreamEntity>
    @Query("SELECT * FROM series WHERE playlistId=:playlistId AND isKids=1 AND isHidden=0 LIMIT :limit OFFSET :offset") suspend fun kidsSeries(playlistId: String, limit: Int, offset: Int): List<SeriesEntity>
    @Query("SELECT * FROM vod_streams WHERE playlistId=:playlistId AND isBrazilian=1 AND isHidden=0 LIMIT :limit OFFSET :offset") suspend fun brazilianVod(playlistId: String, limit: Int, offset: Int): List<VodStreamEntity>
    @Query("SELECT * FROM series WHERE playlistId=:playlistId AND isBrazilian=1 AND isHidden=0 LIMIT :limit OFFSET :offset") suspend fun brazilianSeries(playlistId: String, limit: Int, offset: Int): List<SeriesEntity>
    @Query("SELECT * FROM sync_metadata WHERE playlistId=:playlistId") suspend fun metadata(playlistId: String): List<SyncMetadataEntity>
    @Query("SELECT * FROM episodes WHERE playlistId=:playlistId AND seriesId=:seriesId ORDER BY season, episode") suspend fun episodes(playlistId: String, seriesId: String): List<EpisodeEntity>
    @Query("SELECT * FROM media_details WHERE playlistId=:playlistId AND mediaId=:mediaId") suspend fun detail(playlistId: String, mediaId: String): MediaDetailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAccount(value: PlaylistAccountEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLiveCategories(values: List<LiveCategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertVodCategories(values: List<VodCategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSeriesCategories(values: List<SeriesCategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLive(values: List<LiveStreamEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertVod(values: List<VodStreamEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSeries(values: List<SeriesEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEpisodes(values: List<EpisodeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMetadata(value: SyncMetadataEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDetail(value: MediaDetailEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFavorite(value: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE playlistId=:playlistId AND mediaId=:mediaId") suspend fun deleteFavorite(playlistId: String, mediaId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProgress(value: WatchProgressEntity)
    @Update suspend fun updateLiveClassification(values: List<LiveStreamEntity>)
    @Update suspend fun updateVodClassification(values: List<VodStreamEntity>)
    @Update suspend fun updateSeriesClassification(values: List<SeriesEntity>)
    @Query("SELECT COUNT(*) FROM live_streams WHERE playlistId=:playlistId AND classificationVersion < :version") suspend fun outdatedLive(playlistId: String, version: Int): Int
    @Query("SELECT COUNT(*) FROM vod_streams WHERE playlistId=:playlistId AND classificationVersion < :version") suspend fun outdatedVod(playlistId: String, version: Int): Int
    @Query("SELECT COUNT(*) FROM series WHERE playlistId=:playlistId AND classificationVersion < :version") suspend fun outdatedSeries(playlistId: String, version: Int): Int
    @Query("DELETE FROM live_categories WHERE playlistId=:id AND syncVersion != :version") suspend fun pruneLiveCategories(id: String, version: Long)
    @Query("DELETE FROM vod_categories WHERE playlistId=:id AND syncVersion != :version") suspend fun pruneVodCategories(id: String, version: Long)
    @Query("DELETE FROM series_categories WHERE playlistId=:id AND syncVersion != :version") suspend fun pruneSeriesCategories(id: String, version: Long)
    @Query("DELETE FROM live_streams WHERE playlistId=:id AND syncVersion != :version") suspend fun pruneLive(id: String, version: Long)
    @Query("DELETE FROM vod_streams WHERE playlistId=:id AND syncVersion != :version") suspend fun pruneVod(id: String, version: Long)
    @Query("DELETE FROM series WHERE playlistId=:id AND syncVersion != :version") suspend fun pruneSeries(id: String, version: Long)

    @Transaction suspend fun replaceLive(id: String, categories: List<LiveCategoryEntity>, streams: List<LiveStreamEntity>, meta: SyncMetadataEntity) {
        require(streams.isNotEmpty())
        upsertLiveCategories(categories); upsertLive(streams); pruneLiveCategories(id, meta.syncVersion); pruneLive(id, meta.syncVersion); upsertMetadata(meta)
    }
    @Transaction suspend fun replaceVod(id: String, categories: List<VodCategoryEntity>, streams: List<VodStreamEntity>, meta: SyncMetadataEntity) {
        require(streams.isNotEmpty())
        upsertVodCategories(categories); upsertVod(streams); pruneVodCategories(id, meta.syncVersion); pruneVod(id, meta.syncVersion); upsertMetadata(meta)
    }
    @Transaction suspend fun replaceSeries(id: String, categories: List<SeriesCategoryEntity>, streams: List<SeriesEntity>, meta: SyncMetadataEntity) {
        require(streams.isNotEmpty())
        upsertSeriesCategories(categories); upsertSeries(streams); pruneSeriesCategories(id, meta.syncVersion); pruneSeries(id, meta.syncVersion); upsertMetadata(meta)
    }
}

@Database(entities = [PlaylistAccountEntity::class, LiveCategoryEntity::class, VodCategoryEntity::class, SeriesCategoryEntity::class, LiveStreamEntity::class, VodStreamEntity::class, SeriesEntity::class, EpisodeEntity::class, MediaDetailEntity::class, SyncMetadataEntity::class, FavoriteEntity::class, WatchProgressEntity::class], version = 3, exportSchema = true)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    companion object {
        @Volatile private var instance: CatalogDatabase? = null
        fun get(context: Context): CatalogDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CatalogDatabase::class.java, "droplay-catalog.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("live_categories", "vod_categories", "series_categories").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN normalizedName TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isBlocked INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN classificationVersion INTEGER NOT NULL DEFAULT 0")
                }
                listOf("live_streams", "vod_streams", "series").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN normalizedCategoryName TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isAdult INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isLowQualityCinema INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isKids INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isBrazilian INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN classificationReason TEXT")
                    db.execSQL("ALTER TABLE $table ADD COLUMN classificationVersion INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_playlistId_isHidden ON $table (playlistId, isHidden)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_playlistId_isKids_isHidden ON $table (playlistId, isKids, isHidden)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_playlistId_isBrazilian_isHidden ON $table (playlistId, isBrazilian, isHidden)")
                }
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("live_categories", "vod_categories", "series_categories").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN presentationOrder INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isAdult INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isLowQualityCinema INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isKids INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isBrazilian INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN classificationReason TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_playlistId_normalizedName ON $table (playlistId, normalizedName)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_playlistId_presentationOrder ON $table (playlistId, presentationOrder)")
                }
                listOf("live_streams", "vod_streams", "series").forEach { table ->
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_playlistId_isAdult_isLowQualityCinema ON $table (playlistId, isAdult, isLowQualityCinema)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_playlistId_isKids_isAdult_isLowQualityCinema ON $table (playlistId, isKids, isAdult, isLowQualityCinema)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_playlistId_isBrazilian_isAdult_isLowQualityCinema ON $table (playlistId, isBrazilian, isAdult, isLowQualityCinema)")
                }
            }
        }
    }
}
