package com.droplay.tv.data

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DroplayRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("droplay_local", Context.MODE_PRIVATE)
    private val dao = CatalogDatabase.get(context).catalogDao()
    private val credentialVault = CredentialVault(context)
    private val cache = AtomicFile(File(context.filesDir, "catalog-v3.jsonl"))
    private val fastCache = AtomicFile(File(context.filesDir, "catalog-v4.bin"))
    private val rawPlaylist = AtomicFile(File(context.filesDir, "playlist-v1.m3u"))

    init {
        File(context.filesDir, "catalog-v1.json").delete()
        File(context.filesDir, "catalog-v2.json").delete()
        // Migração executada uma única vez: impede que uma M3U gigante salva nas
        // versões antigas volte a bloquear a abertura. Novas M3U escolhidas
        // explicitamente pelo usuário continuam suportadas como fonte secundária.
        if (!prefs.getBoolean("legacy_m3u_migration_done", false)) {
            if (prefs.getString("source_type", null) == "m3u") {
                prefs.edit().remove("source_type").remove("m3u_url").remove("last_catalog_refresh")
                    .remove("catalog_source_key").apply()
                cache.delete()
                rawPlaylist.delete()
            }
            prefs.edit().putBoolean("legacy_m3u_migration_done", true).apply()
        }
        prefs.getString("password", null)?.let { legacyPassword ->
            credentialVault.put(CREDENTIAL_ALIAS, legacyPassword)
            prefs.edit().remove("password").apply()
        }
    }

    fun savedSource(): PlaylistSource? = when (prefs.getString("source_type", null)) {
        "m3u" -> prefs.getString("m3u_url", null)?.takeIf(String::isNotBlank)?.let(PlaylistSource::M3u)
        "xtream" -> PlaylistSource.Xtream(
            prefs.getString("server", "").orEmpty(), prefs.getString("username", "").orEmpty(), credentialVault.get(CREDENTIAL_ALIAS).orEmpty()
        ).takeIf { it.server.isNotBlank() && it.username.isNotBlank() }
        else -> null
    }

    fun refreshInterval(): RefreshInterval = runCatching {
        RefreshInterval.valueOf(prefs.getString("refresh_interval", RefreshInterval.WEEKLY.name).orEmpty())
    }.getOrDefault(RefreshInterval.WEEKLY)

    fun setRefreshInterval(interval: RefreshInterval) {
        prefs.edit().putString("refresh_interval", interval.name).apply()
    }

    fun lastRefresh(): Long = prefs.getLong("last_catalog_refresh", 0L)

    fun isRefreshDue(source: PlaylistSource): Boolean {
        val interval = refreshInterval()
        val age = System.currentTimeMillis() - lastRefresh()
        val sourceMatches = prefs.getString("catalog_source_key", null) == sourceKey(source)
        val sectionDue = source is PlaylistSource.Xtream && listOf(CatalogSection.LIVE, CatalogSection.VOD, CatalogSection.SERIES)
            .any { SyncPolicy.isDue(prefs.getLong("last_sync_${it.name}", 0L), it) }
        return !sourceMatches || interval == RefreshInterval.EVERY_LAUNCH || sectionDue || age < 0 ||
            (source !is PlaylistSource.Xtream && age >= interval.durationMs) ||
            (!prefs.getBoolean("room_catalog_ready", false) && !fastCache.baseFile.exists() && !cache.baseFile.exists())
    }

    fun showAdultContent(): Boolean = prefs.getBoolean("show_adult_content", false)
    fun setShowAdultContent(show: Boolean) { prefs.edit().putBoolean("show_adult_content", show).apply() }
    fun showCinemaContent(): Boolean = prefs.getBoolean("show_cinema_content", false)
    fun setShowCinemaContent(show: Boolean) { prefs.edit().putBoolean("show_cinema_content", show).apply() }
    fun contentSort(): ContentSort = runCatching {
        ContentSort.valueOf(prefs.getString("content_sort", ContentSort.YEAR_DESC.name).orEmpty())
    }.getOrDefault(ContentSort.YEAR_DESC)
    fun setContentSort(sort: ContentSort) { prefs.edit().putString("content_sort", sort.name).apply() }
    fun playCounts(): Map<String, Int> {
        val json = runCatching { JSONObject(prefs.getString("play_counts", "{}").orEmpty()) }.getOrDefault(JSONObject())
        return json.keys().asSequence().associateWith { json.optInt(it) }
    }
    fun recordPlaybackStarted(id: String): Map<String, Int> {
        val counts = playCounts().toMutableMap()
        counts[id] = (counts[id] ?: 0) + 1
        val json = JSONObject(); counts.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString("play_counts", json.toString()).apply()
        return counts
    }

    suspend fun cached(source: PlaylistSource): Catalog? {
        if (source is PlaylistSource.Xtream) {
            readDatabaseCatalog(source)?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            cachedCatalog(source, requireFresh = false)?.let { legacy ->
                return legacy
            }
        }
        return cachedCatalog(source, requireFresh = false)
    }

    suspend fun load(
        source: PlaylistSource,
        save: Boolean = true,
        force: Boolean = false,
        refreshAll: Boolean = false,
        progress: (String) -> Unit = {},
        sectionState: (CatalogSection, SyncPhase, String?) -> Unit = { _, _, _ -> },
        sectionCommitted: (MediaKind, List<MediaEntry>) -> Unit = { _, _ -> },
    ): Catalog {
        if (!force) cachedCatalog(source, requireFresh = true)?.let { return it }

        return try {
            val entries: List<MediaEntry>
            val epgUrl: String?
            when (source) {
                is PlaylistSource.M3u -> {
                    progress("Baixando a lista M3U Plus…")
                    downloadPlaylist(m3uPlusUrl(source.url))
                    progress("Lendo a lista de reprodução…")
                    val parsed = rawPlaylist.openRead().bufferedReader().use(M3uParser::parseCatalog)
                    entries = parsed.entries
                    epgUrl = parsed.epgUrl
                }
                is PlaylistSource.Xtream -> {
                    entries = syncXtream(source, progress, sectionState, refreshAll, sectionCommitted)
                    val base = source.server.trim().trimEnd('/')
                    epgUrl = "$base/xmltv.php?username=${source.username}&password=${source.password}"
                }
            }
            require(entries.isNotEmpty()) { "Nenhum item reproduzível foi encontrado." }
            if (save) saveSource(source)
            if (source is PlaylistSource.M3u) prefs.edit().putString("epg_url", epgUrl).apply()
            else prefs.edit().remove("epg_url").apply()
            val previous = if (source is PlaylistSource.Xtream) readDatabaseCatalog(source)?.entries.orEmpty()
                else cachedCatalog(source, requireFresh = false)?.entries.orEmpty()
            val merged = mergeCatalog(previous, entries)
            if (source is PlaylistSource.M3u && previous != merged) saveCatalog(source, merged) else markCatalogChecked(source)
            Catalog(merged)
        } catch (error: Throwable) {
            if (force) throw error
            cachedCatalog(source, requireFresh = false) ?: throw error
        }
    }

    private suspend fun syncXtream(source: PlaylistSource.Xtream, progress: (String) -> Unit, sectionState: (CatalogSection, SyncPhase, String?) -> Unit, refreshAll: Boolean, sectionCommitted: (MediaKind, List<MediaEntry>) -> Unit): List<MediaEntry> = syncMutex.withLock {
        val playlistId = sourceKey(source)
        val client = XtreamClient(source)
        progress("Validando o acesso Xtream…")
        client.validate()
        dao.upsertAccount(PlaylistAccountEntity(playlistId, playlistId, XtreamUrlBuilder.normalizeBase(source.server), source.username, CREDENTIAL_ALIAS, System.currentTimeMillis()))
        progress("Atualizando canais, filmes e séries em segundo plano…")
        val due = listOf(CatalogSection.LIVE, CatalogSection.VOD, CatalogSection.SERIES).filter {
            refreshAll || SyncPolicy.isDue(prefs.getLong("last_sync_${it.name}", 0L), it)
        }
        if (due.isEmpty()) return@withLock readDatabaseCatalog(source)?.entries.orEmpty()
        val results = supervisorScope { due.map { section -> async(Dispatchers.IO) {
            syncSection(playlistId, section, sectionState) {
                val batch = when (section) { CatalogSection.LIVE -> client.liveBatch(); CatalogSection.VOD -> client.vodBatch(); else -> client.seriesBatch() }
                batch.also { persistBatch(playlistId, it); sectionCommitted(it.kind, it.entries) }
            }
        } }.awaitAll() }
        val successes = results.mapNotNull(Result<XtreamBatch>::getOrNull)
        if (successes.isEmpty()) throw results.firstNotNullOf { it.exceptionOrNull() }
        sectionState(CatalogSection.CATEGORIES, if (successes.size == results.size) SyncPhase.Success else SyncPhase.PartialSuccess,
            if (successes.size == results.size) null else "Algumas seções falharam; o cache anterior foi preservado.")
        if (dao.liveCount(playlistId) > 0 && dao.vodCount(playlistId) > 0 && dao.seriesCount(playlistId) > 0) {
            fastCache.delete(); cache.delete()
        }
        migrateUserState(playlistId)
        readDatabaseCatalog(source)?.entries.orEmpty().also { require(it.isNotEmpty()) }
    }

    private suspend fun syncSection(playlistId: String, section: CatalogSection, state: (CatalogSection, SyncPhase, String?) -> Unit, block: suspend () -> XtreamBatch): Result<XtreamBatch> {
        state(section, SyncPhase.Refreshing, null)
        return try {
            Result.success(block()).also { state(section, SyncPhase.Success, null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val safe = CredentialSanitizer.sanitize(error.message ?: "Falha temporária")
            val old = dao.metadata(playlistId).firstOrNull { it.section == section.name }
            dao.upsertMetadata(SyncMetadataEntity(playlistId, section.name, System.currentTimeMillis(), old?.lastSuccessfulSyncAt ?: 0L,
                safe, old?.syncVersion ?: 0L, old?.itemCount ?: 0, old?.etag, old?.lastModified, SyncPhase.Error.name))
            state(section, SyncPhase.Error, safe)
            Result.failure(error)
        }
    }

    private suspend fun persistBatch(playlistId: String, batch: XtreamBatch) {
        val persistenceStarted = System.nanoTime()
        val version = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val meta = SyncMetadataEntity(playlistId, batch.kind.name, now, now, null, version, batch.entries.size, null, null, SyncPhase.Success.name)
        fun category(id: String, name: String) = batch.categoryClassifications[id]
            ?: ContentClassificationEngine.classifyCategory(name)
        when (batch.kind) {
            MediaKind.LIVE -> dao.replaceLive(playlistId,
                batch.categories.entries.mapIndexed { order, item -> val c = category(item.key, item.value); LiveCategoryEntity(playlistId, item.key, item.value, c.normalizedName, false, ContentClassificationEngine.VERSION, version, order, c.isAdult, c.isLowQualityCinema, c.isKids, c.isBrazilian, c.isAdult || c.isLowQualityCinema, categoryReason(c)) },
                batch.entries.map { item -> val c = item.classification(); LiveStreamEntity(playlistId, item.streamId.orEmpty(), item.categoryId.orEmpty(), item.name, c.normalizedName, c.normalizedCategoryName, item.logo, item.epgId, item.addedAt, item.containerExtension ?: "ts", c.isAdult, c.isLowQualityCinema, c.isKids, c.isBrazilian, c.isHidden, c.reason.name, c.version, version) }, meta)
            MediaKind.MOVIE -> dao.replaceVod(playlistId,
                batch.categories.entries.mapIndexed { order, item -> val c = category(item.key, item.value); VodCategoryEntity(playlistId, item.key, item.value, c.normalizedName, false, ContentClassificationEngine.VERSION, version, order, c.isAdult, c.isLowQualityCinema, c.isKids, c.isBrazilian, c.isAdult || c.isLowQualityCinema, categoryReason(c)) },
                batch.entries.map { item -> val c = item.classification(); VodStreamEntity(playlistId, item.streamId.orEmpty(), item.categoryId.orEmpty(), item.name, c.normalizedName, c.normalizedCategoryName, item.logo, item.addedAt, item.containerExtension ?: "mp4", item.year, item.rating, item.description, item.durationMs, c.isAdult, c.isLowQualityCinema, c.isKids, c.isBrazilian, c.isHidden, c.reason.name, c.version, version) }, meta)
            MediaKind.SERIES -> dao.replaceSeries(playlistId,
                batch.categories.entries.mapIndexed { order, item -> val c = category(item.key, item.value); SeriesCategoryEntity(playlistId, item.key, item.value, c.normalizedName, false, ContentClassificationEngine.VERSION, version, order, c.isAdult, c.isLowQualityCinema, c.isKids, c.isBrazilian, c.isAdult || c.isLowQualityCinema, categoryReason(c)) },
                batch.entries.map { item -> val c = item.classification(); SeriesEntity(playlistId, item.seriesId.orEmpty(), item.categoryId.orEmpty(), item.name, c.normalizedName, c.normalizedCategoryName, item.logo, item.backdrop, item.addedAt, item.year, item.rating, item.description, c.isAdult, c.isLowQualityCinema, c.isKids, c.isBrazilian, c.isHidden, c.reason.name, c.version, version) }, meta)
        }
        prefs.edit().putBoolean("room_catalog_ready", true).apply()
        prefs.edit().putLong("last_sync_${batch.kind.name}", now).apply()
        val runtime = Runtime.getRuntime()
        val metrics = batch.classificationMetrics.copy(persistenceMs = (System.nanoTime() - persistenceStarted) / 1_000_000L,
            approximateMemoryBytes = runtime.totalMemory() - runtime.freeMemory())
        prefs.edit().putString("classification_metrics_${batch.kind.name}", JSONObject()
            .put("received", metrics.received).put("adultBlocked", metrics.adultBlocked).put("cinemaBlocked", metrics.cinemaBlocked)
            .put("kids", metrics.kids).put("brazilian", metrics.brazilian).put("networkAndParsingMs", metrics.networkAndParsingMs)
            .put("classificationMs", metrics.classificationMs).put("persistenceMs", metrics.persistenceMs)
            .put("approximateMemoryBytes", metrics.approximateMemoryBytes).toString()).apply()
    }

    private fun categoryReason(value: CategoryClassification): String? = when {
        value.isAdult -> ClassificationReason.ADULT_CATEGORY.name
        value.isLowQualityCinema -> ClassificationReason.LOW_QUALITY_CINEMA.name
        else -> null
    }

    private suspend fun readDatabaseCatalog(source: PlaylistSource.Xtream): Catalog? {
        val id = sourceKey(source)
        val liveCategories = dao.liveCategories(id).associate { it.categoryId to it.name }
        val vodCategories = dao.vodCategories(id).associate { it.categoryId to it.name }
        val seriesCategories = dao.seriesCategories(id).associate { it.categoryId to it.name }
        val entries = ArrayList<MediaEntry>(dao.liveCount(id) + dao.vodCount(id) + dao.seriesCount(id))
        dao.live(id).mapTo(entries) { MediaEntry("live:${it.streamId}", it.name, "", MediaKind.LIVE, liveCategories[it.categoryId] ?: "Ao vivo", it.icon, it.epgId, addedAt = it.addedAt, streamId = it.streamId, categoryId = it.categoryId, containerExtension = it.extension, normalizedName = it.normalizedName, normalizedCategoryName = it.normalizedCategoryName, isAdult = it.isAdult, isLowQualityCinema = it.isLowQualityCinema, isKids = it.isKids, isBrazilian = it.isBrazilian, isHidden = it.isHidden, classificationReason = it.classificationReason, classificationVersion = it.classificationVersion) }
        dao.vod(id).mapTo(entries) { MediaEntry("movie:${it.streamId}", it.name, "", MediaKind.MOVIE, vodCategories[it.categoryId] ?: "Filmes", it.icon, description = it.description, year = it.year, addedAt = it.addedAt, durationMs = it.durationMs, streamId = it.streamId, categoryId = it.categoryId, containerExtension = it.extension, rating = it.rating, normalizedName = it.normalizedName, normalizedCategoryName = it.normalizedCategoryName, isAdult = it.isAdult, isLowQualityCinema = it.isLowQualityCinema, isKids = it.isKids, isBrazilian = it.isBrazilian, isHidden = it.isHidden, classificationReason = it.classificationReason, classificationVersion = it.classificationVersion) }
        dao.series(id).mapTo(entries) { MediaEntry("series:${it.seriesId}", it.name, "", MediaKind.SERIES, seriesCategories[it.categoryId] ?: "Séries", it.cover, description = it.description, backdrop = it.backdrop, seriesId = it.seriesId, year = it.year, addedAt = it.addedAt, streamId = it.seriesId, categoryId = it.categoryId, rating = it.rating, normalizedName = it.normalizedName, normalizedCategoryName = it.normalizedCategoryName, isAdult = it.isAdult, isLowQualityCinema = it.isLowQualityCinema, isKids = it.isKids, isBrazilian = it.isBrazilian, isHidden = it.isHidden, classificationReason = it.classificationReason, classificationVersion = it.classificationVersion) }
        return Catalog(entries).takeIf { entries.isNotEmpty() }
    }

    private fun MediaEntry.classification(): ContentClassification = if (classificationVersion == ContentClassificationEngine.VERSION) {
        ContentClassification(normalizedName, normalizedCategoryName, isAdult, isLowQualityCinema, isKids, isBrazilian, isHidden,
            runCatching { ClassificationReason.valueOf(classificationReason.orEmpty()) }.getOrDefault(ClassificationReason.NONE), ClassificationConfidence.HIGH, classificationVersion)
    } else ContentClassificationEngine.classify(ClassificationInput(name, group, kind))

    private suspend fun persistLegacyCatalog(source: PlaylistSource.Xtream, entries: List<MediaEntry>) {
        val batches = entries.groupBy(MediaEntry::kind).map { (kind, values) ->
            val sanitized = values.map { item ->
                val id = item.id.substringAfter(':')
                val ext = item.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', if (kind == MediaKind.LIVE) "ts" else "mp4")
                item.copy(url = "", streamId = id, categoryId = item.group, containerExtension = ext)
            }
            XtreamBatch(kind, sanitized.associate { it.group to it.group }, sanitized)
        }
        batches.forEach { persistBatch(sourceKey(source), it) }
    }

    suspend fun ensureRoomCache(source: PlaylistSource, entries: List<MediaEntry>) {
        if (source is PlaylistSource.Xtream) syncMutex.withLock {
            if (readDatabaseCatalog(source) == null) {
                persistLegacyCatalog(source, entries)
                migrateUserState(sourceKey(source))
                fastCache.delete(); cache.delete()
            }
        }
    }

    suspend fun reclassifyIfNeeded(source: PlaylistSource): ClassificationMetrics? {
        if (source !is PlaylistSource.Xtream) return null
        val playlistId = sourceKey(source)
        val outdated = dao.outdatedLive(playlistId, ContentClassificationEngine.VERSION) +
            dao.outdatedVod(playlistId, ContentClassificationEngine.VERSION) + dao.outdatedSeries(playlistId, ContentClassificationEngine.VERSION)
        if (outdated == 0) return null
        return syncMutex.withLock {
            val liveCategoryNames = dao.liveCategories(playlistId).associate { it.categoryId to it.name }
            val vodCategoryNames = dao.vodCategories(playlistId).associate { it.categoryId to it.name }
            val seriesCategoryNames = dao.seriesCategories(playlistId).associate { it.categoryId to it.name }
            var adult = 0; var cinema = 0; var kids = 0; var brazilian = 0; var received = 0
            val started = System.nanoTime()
            suspend fun classifyLive() { var offset = 0; while (true) {
                currentCoroutineContext().ensureActive(); val batch = dao.liveBatch(playlistId, RECLASSIFY_BATCH, offset); if (batch.isEmpty()) break
                dao.updateLiveClassification(batch.map { item -> val c = ContentClassificationEngine.classify(ClassificationInput(item.name, liveCategoryNames[item.categoryId].orEmpty(), MediaKind.LIVE)); received++; if(c.isAdult)adult++; if(c.isKids)kids++; if(c.isBrazilian)brazilian++; item.copy(normalizedName=c.normalizedName, normalizedCategoryName=c.normalizedCategoryName, isAdult=c.isAdult, isLowQualityCinema=c.isLowQualityCinema, isKids=c.isKids, isBrazilian=c.isBrazilian, isHidden=c.isHidden, classificationReason=c.reason.name, classificationVersion=c.version) })
            } }
            suspend fun classifyVod() { var offset = 0; while (true) {
                currentCoroutineContext().ensureActive(); val batch = dao.vodBatch(playlistId, RECLASSIFY_BATCH, offset); if (batch.isEmpty()) break
                dao.updateVodClassification(batch.map { item -> val c = ContentClassificationEngine.classify(ClassificationInput(item.name, vodCategoryNames[item.categoryId].orEmpty(), MediaKind.MOVIE)); received++; if(c.isAdult)adult++; if(c.isLowQualityCinema)cinema++; if(c.isKids)kids++; if(c.isBrazilian)brazilian++; item.copy(normalizedName=c.normalizedName, normalizedCategoryName=c.normalizedCategoryName, isAdult=c.isAdult, isLowQualityCinema=c.isLowQualityCinema, isKids=c.isKids, isBrazilian=c.isBrazilian, isHidden=c.isHidden, classificationReason=c.reason.name, classificationVersion=c.version) })
            } }
            suspend fun classifySeries() { var offset = 0; while (true) {
                currentCoroutineContext().ensureActive(); val batch = dao.seriesBatch(playlistId, RECLASSIFY_BATCH, offset); if (batch.isEmpty()) break
                dao.updateSeriesClassification(batch.map { item -> val c = ContentClassificationEngine.classify(ClassificationInput(item.name, seriesCategoryNames[item.categoryId].orEmpty(), MediaKind.SERIES)); received++; if(c.isAdult)adult++; if(c.isKids)kids++; if(c.isBrazilian)brazilian++; item.copy(normalizedName=c.normalizedName, normalizedCategoryName=c.normalizedCategoryName, isAdult=c.isAdult, isLowQualityCinema=c.isLowQualityCinema, isKids=c.isKids, isBrazilian=c.isBrazilian, isHidden=c.isHidden, classificationReason=c.reason.name, classificationVersion=c.version) })
            } }
            classifyLive(); classifyVod(); classifySeries()
            prefs.edit().putLong("last_classification", System.currentTimeMillis()).putInt("classification_version", ContentClassificationEngine.VERSION).apply()
            ClassificationMetrics(received, adult, cinema, kids, brazilian, (System.nanoTime() - started) / 1_000_000L)
        }
    }

    private suspend fun migrateUserState(playlistId: String) {
        favorites().forEach { dao.upsertFavorite(FavoriteEntity(playlistId, it, System.currentTimeMillis())) }
        history().forEach { dao.upsertProgress(WatchProgressEntity(playlistId, it.mediaId, it.positionMs, it.durationMs, it.watchedAt)) }
    }

    fun playbackMedia(source: PlaylistSource, media: MediaEntry): MediaEntry = when (source) {
        is PlaylistSource.Xtream -> {
            val generated = XtreamUrlBuilder.forMedia(source, media)
            if (media.url.isNotBlank()) media.copy(playbackFallbackUrl = generated.takeIf { it != media.url })
            else media.copy(url = generated)
        }
        is PlaylistSource.M3u -> media
    }

    fun refreshEpg(): Map<String, EpgProgram> {
        val url = when (val source = savedSource()) {
            is PlaylistSource.Xtream -> "${XtreamUrlBuilder.normalizeBase(source.server)}/xmltv.php?username=${source.username}&password=${source.password}"
            is PlaylistSource.M3u -> prefs.getString("epg_url", null)
            null -> null
        }
        return url?.takeIf(String::isNotBlank)?.let(EpgParser::parse).orEmpty()
    }

    suspend fun loadEpisodes(source: PlaylistSource, seriesId: String): List<MediaEntry> = when (source) {
        is PlaylistSource.Xtream -> runCatching {
            XtreamClient(source).episodes(seriesId).also { episodes ->
                val now = System.currentTimeMillis()
                dao.upsertEpisodes(episodes.map { EpisodeEntity(sourceKey(source), it.streamId.orEmpty(), seriesId, it.name, it.season, it.episode, it.containerExtension ?: "mp4", it.logo, it.durationMs, now) })
            }
        }.getOrElse {
            dao.episodes(sourceKey(source), seriesId).map { episode -> MediaEntry(
                "episode:${episode.episodeId}", episode.name, "", MediaKind.MOVIE, "Temporada ${episode.season ?: 1}", episode.icon,
                parentSeriesId = seriesId, season = episode.season, episode = episode.episode, durationMs = episode.durationMs,
                streamId = episode.episodeId, containerExtension = episode.extension,
            ) }
        }
        is PlaylistSource.M3u -> runCatching { rawPlaylist.openRead().bufferedReader().use { M3uParser.episodes(it, seriesId) } }.getOrDefault(emptyList())
    }

    suspend fun loadDetails(source: PlaylistSource, media: MediaEntry): MediaEntry {
        if (source !is PlaylistSource.Xtream) return media
        val playlistId = sourceKey(source)
        val cached = dao.detail(playlistId, media.id)
        if (cached != null && System.currentTimeMillis() - cached.cachedAt < DETAILS_TTL_MS) return cached.applyTo(media)
        return runCatching { XtreamClient(source).details(media) }.map { fresh ->
            dao.upsertDetail(MediaDetailEntity(playlistId, media.id, fresh.description, fresh.logo, fresh.backdrop, fresh.year,
                fresh.durationMs, subtitlesToJson(fresh.subtitles).toString(), fresh.tmdbId, fresh.streamId, fresh.containerExtension, System.currentTimeMillis()))
            fresh
        }.getOrElse { cached?.applyTo(media) ?: media }
    }

    private fun MediaDetailEntity.applyTo(media: MediaEntry) = media.copy(description = description ?: media.description,
        logo = logo ?: media.logo, backdrop = backdrop ?: media.backdrop, year = year ?: media.year,
        durationMs = durationMs.takeIf { it > 0 } ?: media.durationMs, subtitles = subtitlesFromJson(runCatching { JSONArray(subtitlesJson) }.getOrNull()),
        tmdbId = tmdbId ?: media.tmdbId, streamId = streamId ?: media.streamId, containerExtension = extension ?: media.containerExtension)

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet().orEmpty()
    fun toggleFavorite(id: String): Set<String> {
        val changed = favorites().toMutableSet().apply { if (!add(id)) remove(id) }
        prefs.edit().putStringSet("favorites", changed).apply()
        return changed
    }
    suspend fun mirrorFavorite(id: String, selected: Boolean) {
        val source = savedSource() ?: return
        val playlistId = sourceKey(source)
        if (selected) dao.upsertFavorite(FavoriteEntity(playlistId, id, System.currentTimeMillis())) else dao.deleteFavorite(playlistId, id)
    }

    fun history(): List<WatchRecord> {
        val json = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrDefault(JSONArray())
        return (0 until json.length()).mapNotNull { i ->
            runCatching { json.getJSONObject(i) }.getOrNull()?.let {
                WatchRecord(
                    it.optString("id"), it.optLong("position"), it.optLong("duration"), it.optLong("at"),
                    it.optString("name"), it.optString("url"),
                    runCatching { MediaKind.valueOf(it.optString("kind", MediaKind.MOVIE.name)) }.getOrDefault(MediaKind.MOVIE),
                    it.optString("group"), it.optString("logo").takeIf(String::isNotBlank),
                    it.optString("parentSeriesId").takeIf(String::isNotBlank),
                    subtitlesFromJson(it.optJSONArray("subtitles")),
                    it.optInt("season", -1).takeIf { value -> value >= 0 },
                    it.optInt("episode", -1).takeIf { value -> value >= 0 },
                    it.optInt("year", -1).takeIf { value -> value > 0 },
                    it.optInt("tmdbId", -1).takeIf { value -> value > 0 },
                    it.optString("parentTitle").takeIf(String::isNotBlank),
                )
            }
        }
    }

    fun saveProgress(media: MediaEntry, positionMs: Long, durationMs: Long) {
        if (media.kind == MediaKind.LIVE || media.id.isBlank() || positionMs < 2_000) return
        val records = history().filterNot { it.mediaId == media.id }.toMutableList()
        val persistedUrl = media.url.takeUnless { prefs.getString("source_type", null) == "xtream" }.orEmpty()
        records.add(0, WatchRecord(media.id, positionMs, durationMs, System.currentTimeMillis(), media.name, persistedUrl,
            media.kind, media.group, media.logo, media.parentSeriesId, media.subtitles,
            media.season, media.episode, media.year, media.tmdbId, media.parentTitle))
        val json = JSONArray()
        records.take(60).forEach { json.put(JSONObject().put("id", it.mediaId).put("position", it.positionMs)
            .put("duration", it.durationMs).put("at", it.watchedAt).put("name", it.name).put("url", it.url)
            .put("kind", it.kind.name).put("group", it.group).put("logo", it.logo ?: "")
            .put("parentSeriesId", it.parentSeriesId ?: "").put("subtitles", subtitlesToJson(it.subtitles))
            .put("season", it.season ?: -1).put("episode", it.episode ?: -1).put("year", it.year ?: -1)
            .put("tmdbId", it.tmdbId ?: -1).put("parentTitle", it.parentTitle ?: "")) }
        prefs.edit().putString("history", json.toString()).apply()
    }
    suspend fun mirrorProgress(mediaId: String, positionMs: Long, durationMs: Long) {
        val source = savedSource() ?: return
        dao.upsertProgress(WatchProgressEntity(sourceKey(source), mediaId, positionMs, durationMs, System.currentTimeMillis()))
    }

    fun clearSource() {
        prefs.edit().remove("source_type").remove("m3u_url").remove("server").remove("username")
            .remove("password").remove("epg_url").remove("last_catalog_refresh").remove("catalog_source_key").apply()
        prefs.edit().remove("room_catalog_ready").apply()
        cache.delete()
        fastCache.delete()
        rawPlaylist.delete()
        credentialVault.remove(CREDENTIAL_ALIAS)
        CatalogWorkScheduler.cancelAll(appContext)
    }

    private fun cachedCatalog(source: PlaylistSource, requireFresh: Boolean): Catalog? {
        val interval = refreshInterval()
        val age = System.currentTimeMillis() - lastRefresh()
        val sourceMatches = prefs.getString("catalog_source_key", null) == sourceKey(source)
        if (!sourceMatches) return null
        if (requireFresh && (interval == RefreshInterval.EVERY_LAUNCH || age < 0 || age >= interval.durationMs)) return null
        readFastCatalog()?.let { return it }
        return readLegacyCatalog()
    }

    private fun saveCatalog(source: PlaylistSource, entries: List<MediaEntry>) {
        writeFastCatalog(entries)
        cache.delete()
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_catalog_refresh", now).putString("catalog_source_key", sourceKey(source)).apply()
    }

    private fun markCatalogChecked(source: PlaylistSource) {
        prefs.edit().putLong("last_catalog_refresh", System.currentTimeMillis())
            .putString("catalog_source_key", sourceKey(source)).apply()
    }

    /** Reutiliza os registros que não mudaram e aplica somente inclusões, alterações e remoções. */
    internal fun mergeCatalog(previous: List<MediaEntry>, incoming: List<MediaEntry>): List<MediaEntry> {
        if (previous.isEmpty()) return incoming
        val oldById = previous.associateBy(MediaEntry::id)
        return incoming.map { item -> oldById[item.id]?.takeIf { it == item } ?: item }
    }

    @Synchronized
    fun ensureFastCache(source: PlaylistSource, entries: List<MediaEntry>) {
        if (fastCache.baseFile.exists() || prefs.getString("catalog_source_key", null) != sourceKey(source)) return
        writeFastCatalog(entries)
        cache.delete()
    }

    private fun readLegacyCatalog(): Catalog? = runCatching {
        if (!cache.baseFile.exists()) return@runCatching null
        val input = cache.openRead()
        val textReader = InputStreamReader(input, Charsets.UTF_8).buffered()
        require(textReader.readLine() == "DROPLAY-CATALOG-3") { "Cache incompatível." }
        val entries = ArrayList<MediaEntry>()
        JsonReader(textReader).use { reader ->
            reader.isLenient = true
            while (reader.peek() != JsonToken.END_DOCUMENT) entries += reader.readMediaEntry()
        }
        Catalog(entries)
    }.getOrNull()?.takeIf { it.entries.isNotEmpty() }

    private fun readFastCatalog(): Catalog? = runCatching {
        if (!fastCache.baseFile.exists()) return@runCatching null
        DataInputStream(fastCache.openRead().buffered()).use { input ->
            require(input.readInt() == FAST_CACHE_MAGIC && input.readInt() == FAST_CACHE_SCHEMA) { "Cache rápido incompatível." }
            val count = input.readInt()
            require(count in 1..500_000) { "Quantidade inválida no cache." }
            Catalog(ArrayList<MediaEntry>(count).apply { repeat(count) { add(input.readMediaEntry()) } })
        }
    }.getOrNull()

    @Synchronized
    private fun writeFastCatalog(entries: List<MediaEntry>) {
        var stream: FileOutputStream? = null
        try {
            stream = fastCache.startWrite()
            val output = DataOutputStream(stream.buffered())
            output.writeInt(FAST_CACHE_MAGIC)
            output.writeInt(FAST_CACHE_SCHEMA)
            output.writeInt(entries.size)
            entries.forEach { output.writeMediaEntry(it) }
            output.flush()
            fastCache.finishWrite(stream)
            stream = null
        } catch (error: Throwable) {
            stream?.let(fastCache::failWrite)
            throw error
        }
    }

    private fun subtitlesToJson(tracks: List<SubtitleTrack>) = JSONArray().apply {
        tracks.forEach { put(JSONObject().put("url", it.url).put("label", it.label).put("language", it.language).put("mimeType", it.mimeType)) }
    }

    private fun subtitlesFromJson(array: JSONArray?): List<SubtitleTrack> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                item.textOrNull("url")?.let { SubtitleTrack(it, item.textOrNull("label"), item.textOrNull("language"), item.textOrNull("mimeType")) }
            }
        }

    private fun JSONObject.textOrNull(key: String) = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }

    private fun DataOutputStream.writeMediaEntry(item: MediaEntry) {
        writeText(item.id); writeText(item.name); writeText(item.url); writeText(item.kind.name)
        writeText(item.group); writeNullableText(item.logo); writeNullableText(item.epgId)
        writeNullableText(item.description); writeNullableText(item.backdrop); writeNullableText(item.seriesId)
        writeNullableText(item.parentSeriesId); writeNullableInt(item.season); writeNullableInt(item.episode)
        writeNullableInt(item.year); writeLong(item.addedAt); writeLong(item.durationMs)
        writeInt(item.subtitles.size)
        item.subtitles.forEach { subtitle ->
            writeText(subtitle.url); writeNullableText(subtitle.label); writeNullableText(subtitle.language); writeNullableText(subtitle.mimeType)
        }
    }

    private fun DataInputStream.readMediaEntry(): MediaEntry = MediaEntry(
        id = readText(), name = readText(), url = readText(),
        kind = runCatching { MediaKind.valueOf(readText()) }.getOrDefault(MediaKind.MOVIE),
        group = readText(), logo = readNullableText(), epgId = readNullableText(),
        description = readNullableText(), backdrop = readNullableText(), seriesId = readNullableText(),
        parentSeriesId = readNullableText(), season = readNullableInt(), episode = readNullableInt(),
        year = readNullableInt(), addedAt = readLong(), durationMs = readLong(),
        subtitles = List(readInt().also { require(it in 0..100) }) {
            SubtitleTrack(readText(), readNullableText(), readNullableText(), readNullableText())
        },
    )

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataOutputStream.writeNullableText(value: String?) {
        if (value == null) writeInt(-1) else writeText(value)
    }
    private fun DataInputStream.readText(): String {
        val size = readInt(); require(size in 0..20_000_000) { "Texto inválido no cache." }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
    private fun DataInputStream.readNullableText(): String? {
        val size = readInt()
        require(size in -1..20_000_000) { "Texto inválido no cache." }
        if (size == -1) return null
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }
    private fun DataOutputStream.writeNullableInt(value: Int?) { if (value == null) writeBoolean(false) else { writeBoolean(true); writeInt(value) } }
    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

    private fun JsonReader.readMediaEntry(): MediaEntry {
        var id = ""; var name = ""; var url = ""; var kind = MediaKind.MOVIE; var group = "Outros"
        var logo: String? = null; var epgId: String? = null; var description: String? = null; var backdrop: String? = null
        var seriesId: String? = null; var parentSeriesId: String? = null; var season: Int? = null; var episode: Int? = null
        var year: Int? = null; var addedAt = 0L; var durationMs = 0L; var subtitles = emptyList<SubtitleTrack>()
        beginObject()
        while (hasNext()) when (nextName()) {
            "id" -> id = nextText().orEmpty(); "name" -> name = nextText().orEmpty(); "url" -> url = nextText().orEmpty()
            "kind" -> kind = runCatching { MediaKind.valueOf(nextText().orEmpty()) }.getOrDefault(MediaKind.MOVIE)
            "group" -> group = nextText() ?: "Outros"; "logo" -> logo = nextText(); "epgId" -> epgId = nextText()
            "description" -> description = nextText(); "backdrop" -> backdrop = nextText(); "seriesId" -> seriesId = nextText()
            "parentSeriesId" -> parentSeriesId = nextText(); "season" -> season = nextIntOrNull(); "episode" -> episode = nextIntOrNull()
            "year" -> year = nextIntOrNull(); "addedAt" -> addedAt = nextLongOrNull() ?: 0L; "durationMs" -> durationMs = nextLongOrNull() ?: 0L
            "subtitles" -> subtitles = readSubtitles()
            else -> skipValue()
        }
        endObject()
        return MediaEntry(id, name, url, kind, group, logo, epgId, description, backdrop, seriesId, parentSeriesId,
            season, episode, year, addedAt, durationMs, subtitles)
    }

    private fun JsonReader.readSubtitles(): List<SubtitleTrack> {
        if (peek() == JsonToken.NULL) { nextNull(); return emptyList() }
        val result = ArrayList<SubtitleTrack>()
        beginArray()
        while (hasNext()) {
            var url = ""; var label: String? = null; var language: String? = null; var mimeType: String? = null
            beginObject()
            while (hasNext()) when (nextName()) {
                "url" -> url = nextText().orEmpty(); "label" -> label = nextText(); "language" -> language = nextText(); "mimeType" -> mimeType = nextText()
                else -> skipValue()
            }
            endObject()
            if (url.isNotBlank()) result += SubtitleTrack(url, label, language, mimeType)
        }
        endArray()
        return result
    }

    private fun JsonReader.nextText(): String? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
    private fun JsonReader.nextIntOrNull(): Int? = nextLongOrNull()?.toInt()
    private fun JsonReader.nextLongOrNull(): Long? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextString().toLongOrNull()

    private fun sourceKey(source: PlaylistSource): String {
        val raw = when (source) {
            is PlaylistSource.M3u -> "m3u:${source.url}"
            is PlaylistSource.Xtream -> "xtream:${source.server}|${source.username}|${source.password}"
        }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    internal fun m3uPlusUrl(url: String): String {
        if (!url.contains("get.php", ignoreCase = true) || Regex("[?&]type=", RegexOption.IGNORE_CASE).containsMatchIn(url)) return url
        val separator = if ('?' in url) '&' else '?'
        return "$url${separator}type=m3u_plus&output=ts"
    }

    private fun downloadPlaylist(url: String) {
        var output: FileOutputStream? = null
        try {
            Network.open(url).buffered().use { input ->
                input.mark(8_192)
                val headerBuffer = ByteArray(8_192)
                val read = input.read(headerBuffer)
                val header = if (read > 0) String(headerBuffer, 0, read, Charsets.UTF_8).lineSequence().firstOrNull().orEmpty().trimStart('\uFEFF') else ""
                require(header.startsWith("#EXTM3U", true)) { "A URL não retornou uma lista M3U válida." }
                input.reset()
                output = rawPlaylist.startWrite()
                input.copyTo(output!!, DEFAULT_BUFFER_SIZE)
                rawPlaylist.finishWrite(output!!)
                output = null
            }
        } catch (error: Throwable) {
            output?.let(rawPlaylist::failWrite)
            throw error
        }
    }

    private fun saveSource(source: PlaylistSource) = prefs.edit().apply {
        when (source) {
            is PlaylistSource.M3u -> putString("source_type", "m3u").putString("m3u_url", source.url)
            is PlaylistSource.Xtream -> putString("source_type", "xtream").putString("server", source.server)
                .putString("username", source.username).also { credentialVault.put(CREDENTIAL_ALIAS, source.password) }
        }
    }.apply().also { CatalogWorkScheduler.schedule(appContext, sourceKey(source)) }

    private companion object {
        const val FAST_CACHE_MAGIC = 0x44525034
        const val FAST_CACHE_SCHEMA = 1
        const val CREDENTIAL_ALIAS = "active_xtream_password"
        const val DETAILS_TTL_MS = 24L * 60 * 60 * 1000
        const val RECLASSIFY_BATCH = 500
        val syncMutex = Mutex()
    }
}
