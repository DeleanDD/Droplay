package com.droplay.tv.subtitles

import android.content.Context
import android.net.Uri
import com.droplay.tv.data.MediaEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object SubtitlePager {
    suspend fun load(fetch: suspend (Int) -> SubtitlePage, progress: (Int, Int) -> Unit = { _, _ -> }): List<SubtitleCandidate> {
        val all = ArrayList<SubtitleCandidate>()
        var pageNumber = 1
        var totalPages: Int
        do {
            currentCoroutineContext().ensureActive()
            val page = fetch(pageNumber)
            totalPages = page.totalPages
            all += page.candidates
            progress(pageNumber, totalPages)
            pageNumber++
        } while (pageNumber <= totalPages)
        return all
    }
}

class SubtitleRepository(context: Context, private val client: OpenSubtitlesClient = OpenSubtitlesClient()) {
    val settings = SubtitleSettings(context.applicationContext)
    private val cache = SubtitleFileCache(java.io.File(context.cacheDir, "opensubtitles-v1"))

    suspend fun login(password: String) {
        val response = client.login(settings.configuration(), password)
        settings.saveSession(response.token, response.baseUrl)
    }

    suspend fun search(media: MediaEntry, progress: (Int, Int) -> Unit = { _, _ -> }): Pair<List<SubtitleCandidate>, Boolean> {
        val configuration = settings.configuration()
        val request = SubtitleSearchQueryFactory.create(media)
        val all = SubtitlePager.load({ page -> client.searchPage(configuration, request, page) }, progress)
        return SubtitleRanking.rank(all, settings.appearance().preferredLanguage, media.name) to request.approximate
    }

    suspend fun download(candidate: SubtitleCandidate, delayMs: Long): String {
        val raw = cache.raw(candidate.fileId) ?: run {
            val response = client.requestDownload(settings.configuration(), candidate.fileId)
            val text = SubtitleText.decode(client.downloadBytes(response.link))
            val format = SubtitleText.validate(text)
            cache.store(candidate.fileId, text, format)
        }
        val playable = cache.prepare(candidate.fileId, raw, delayMs)
        return Uri.fromFile(playable).toString()
    }

    fun prepareCached(candidate: SubtitleCandidate, delayMs: Long): String? {
        val raw = cache.raw(candidate.fileId) ?: return null
        return Uri.fromFile(cache.prepare(candidate.fileId, raw, delayMs)).toString()
    }
}
