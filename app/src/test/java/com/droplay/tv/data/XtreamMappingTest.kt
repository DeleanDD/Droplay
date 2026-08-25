package com.droplay.tv.data

import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XtreamMappingTest {
    @Test fun mapsDtosAndAssociatesCategoriesWithoutPlaybackUrl() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = when (request.requestUrl?.queryParameter("action")) {
                    null -> "{\"user_info\":{\"auth\":1,\"status\":\"Active\"}}"
                    "get_live_categories" -> "[{\"category_id\":\"7\",\"category_name\":\"Esportes\"}]"
                    "get_live_streams" -> "[{\"stream_id\":\"42\",\"category_id\":\"7\",\"name\":\"Canal Bola\",\"stream_icon\":\"https://img.test/42.png\",\"epg_channel_id\":\"bola.br\"}]"
                    else -> "[]"
                }
                return MockResponse().setBody(json).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        try {
            val client = XtreamClient(PlaylistSource.Xtream(server.url("/").toString(), "demo", "demo"))
            assertTrue(client.validate().authenticated)
            val item = client.liveBatch().entries.single()
            assertEquals("live:42", item.id); assertEquals("Esportes", item.group); assertEquals("7", item.categoryId)
            assertEquals("42", item.streamId); assertEquals("", item.url)
        } finally { server.shutdown() }
    }
}
