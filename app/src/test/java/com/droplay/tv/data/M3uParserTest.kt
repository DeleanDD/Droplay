package com.droplay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    @Test fun preservesExternalSubtitleFromM3uPlus() {
        val item = M3uParser.parse("""#EXTM3U
#EXTINF:-1 tvg-name="Filme" group-title="Filmes" tvg-subtitle="https://example.test/filme.srt",Filme [L]
https://example.test/filme.mkv
""").single()
        assertEquals("https://example.test/filme.srt", item.subtitles.single().url)
    }
    @Test fun parsesCommonExtendedM3u() {
        val source = """
            #EXTM3U x-tvg-url="https://example.test/epg.xml"
            #EXTINF:-1 tvg-id="news.br" tvg-name="Notícias" tvg-logo="https://img.test/n.png" group-title="Brasil",Notícias HD
            https://example.test/live.m3u8
            #EXTINF:-1 group-title="Filmes" tvg-description="Uma aventura" tvg-duration="7200",Meu Filme 2026
            https://example.test/movie.mp4
        """.trimIndent()
        val result = M3uParser.parse(source)
        assertEquals(2, result.size)
        assertEquals(MediaKind.LIVE, result[0].kind)
        assertEquals("news.br", result[0].epgId)
        assertEquals(MediaKind.MOVIE, result[1].kind)
        assertEquals("Uma aventura", result[1].description)
        assertEquals(2026, result[1].year)
        assertEquals(7_200_000L, result[1].durationMs)
        assertTrue(result.map { it.id }.distinct().size == 2)
    }

    @Test fun groupsM3uEpisodesAndCleansSourceDecorations() {
        val source = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Minha Série S01E01" tvg-logo="https://img.test/series.jpg" group-title="Séries ❖ Netflix ⭐",Minha Série S01E01
            https://example.test/series/1.mp4
            #EXTINF:-1 tvg-name="Minha Série S01E02" tvg-logo="https://img.test/series.jpg" group-title="Séries ❖ Netflix ⭐",Minha Série S01E02
            https://example.test/series/2.mp4
        """.trimIndent()
        val catalog = M3uParser.parseCatalog(source.reader())
        assertEquals(1, catalog.entries.size)
        val series = catalog.entries.single()
        assertEquals(MediaKind.SERIES, series.kind)
        assertEquals("Minha Série", series.name)
        assertEquals("Séries Netflix", series.group)
        val episodes = M3uParser.episodes(source.reader(), series.seriesId!!)
        assertEquals(listOf(1, 2), episodes.mapNotNull { it.episode })
        assertEquals(listOf("Episódio 1", "Episódio 2"), episodes.map { it.name })
    }

    @Test fun findsTheMetadataDelimiterOutsideQuotedTitles() {
        val source = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Ano Novo, Vida Nova (2026)" group-title="❖ Acao²",Ano Novo, Vida Nova (2026)
            https://example.test/movie.mp4
        """.trimIndent()
        val movie = M3uParser.parse(source).single()
        assertEquals("Ano Novo, Vida Nova (2026)", movie.name)
        assertEquals("Acao²", movie.group)
    }
}
