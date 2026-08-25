package com.droplay.tv.data

object SyncPolicy {
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
