package com.droplay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    @Test fun parsesCommonExtendedM3u() {
        val source = """
            #EXTM3U x-tvg-url="https://example.test/epg.xml"
            #EXTINF:-1 tvg-id="news.br" tvg-name="Notícias" tvg-logo="https://img.test/n.png" group-title="Brasil",Notícias HD
            https://example.test/live.m3u8
            #EXTINF:-1 group-title="Filmes",Meu Filme
            https://example.test/movie.mp4
        """.trimIndent()
        val result = M3uParser.parse(source)
        assertEquals(2, result.size)
        assertEquals(MediaKind.LIVE, result[0].kind)
        assertEquals("news.br", result[0].epgId)
        assertEquals(MediaKind.MOVIE, result[1].kind)
        assertTrue(result.map { it.id }.distinct().size == 2)
    }
}
