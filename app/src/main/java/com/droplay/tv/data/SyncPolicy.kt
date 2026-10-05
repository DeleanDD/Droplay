package com.droplay.tv.data

object SyncPolicy {
    val catalogSections = listOf(CatalogSection.LIVE, CatalogSection.VOD, CatalogSection.SERIES)

    fun section(kind: MediaKind): CatalogSection = when (kind) {
        MediaKind.LIVE -> CatalogSection.LIVE
        MediaKind.MOVIE -> CatalogSection.VOD
        MediaKind.SERIES -> CatalogSection.SERIES
    }

    // Older releases saved film metadata as MOVIE while reading it as VOD.
    fun lastSuccess(metadata: List<SyncMetadataEntity>, section: CatalogSection): Long =
        metadata.filter { it.section == section.name || (section == CatalogSection.VOD && it.section == "MOVIE") }
            .maxOfOrNull { it.lastSuccessfulSyncAt } ?: 0L

    const val LIVE_TTL_MS = 30L * 60 * 1000
    const val CATEGORY_TTL_MS = 6L * 60 * 60 * 1000
    const val VOD_TTL_MS = 6L * 60 * 60 * 1000
    const val SERIES_TTL_MS = 6L * 60 * 60 * 1000

    fun ttl(section: CatalogSection): Long = when (section) {
        CatalogSection.LIVE -> LIVE_TTL_MS
        CatalogSection.VOD -> VOD_TTL_MS
        CatalogSection.SERIES, CatalogSection.CATEGORIES -> SERIES_TTL_MS
        CatalogSection.DETAILS -> 24L * 60 * 60 * 1000
        CatalogSection.EPG -> 6L * 60 * 60 * 1000
    }

    fun isDue(lastSuccessAt: Long, section: CatalogSection, now: Long = System.currentTimeMillis()): Boolean =
        lastSuccessAt <= 0L || now < lastSuccessAt || now - lastSuccessAt >= ttl(section)
}
