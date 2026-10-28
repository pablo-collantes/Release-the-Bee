package io.github.pablocollantes.releasethebee.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverApiTest {

    @Test fun `derives the HTTP origin from the WebSocket URL`() {
        assertEquals("http://100.101.102.103:8740", ReceiverApi.httpBase("ws://100.101.102.103:8740/"))
        assertEquals("http://100.101.102.103:8740", ReceiverApi.httpBase("ws://100.101.102.103:8740"))
        assertEquals("https://rtb.example.com", ReceiverApi.httpBase("wss://rtb.example.com/stream?x=1"))
        assertEquals("https://rtb.example.com:8443", ReceiverApi.httpBase("  WSS://rtb.example.com:8443/a/b#c "))
        assertEquals("http://box.lan", ReceiverApi.httpBase("ws://user:pw@box.lan/"))
        assertEquals("http://[fd7a:115c::1]:8740", ReceiverApi.httpBase("ws://[fd7a:115c::1]:8740/"))
        assertNull(ReceiverApi.httpBase(""))
        assertNull(ReceiverApi.httpBase("http://host:1/"))
        assertNull(ReceiverApi.httpBase("tcp://host:8741"))
        assertNull(ReceiverApi.httpBase("ws://"))
        assertNull(ReceiverApi.httpBase("host:8740"))
    }

    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')

    @After fun stop() = server.shutdown()

    @Test fun `lists entries with the bearer token, then fetches only changes`() {
        server.enqueue(MockResponse().setBody("""{"now":1000,"entries":[{"id":"a","started_at":1,"status":"done"}]}"""))
        server.enqueue(MockResponse().setBody("""{"now":2000,"entries":[]}"""))
        val api = ReceiverApi(base, "s3cret")

        val first = api.entries()
        assertEquals(1000L, first.now)
        assertEquals("a", first.entries.single().id)
        val r1 = server.takeRequest()
        assertEquals("/api/entries", r1.path)
        assertEquals("Bearer s3cret", r1.getHeader("Authorization"))

        api.entries(since = first.now)
        assertEquals("/api/entries?since=1000", server.takeRequest().path)
    }

    @Test fun `fetches an entry, reprocesses it, and builds the audio URL`() {
        server.enqueue(MockResponse().setBody("""{"id":"7e83","started_at":1,"status":"done","transcript":{"segments":[]}}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        val api = ReceiverApi(base, null)

        assertEquals("7e83", api.entry("7e83").id)
        val get = server.takeRequest()
        assertEquals("/api/entries/7e83", get.path)
        assertNull("no token, no header", get.getHeader("Authorization"))

        api.reprocess("7e83")
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/entries/7e83/reprocess", post.path)

        assertEquals("$base/api/entries/7e83/audio", api.audioUrl("7e83"))
        assertTrue(api.authHeaders().isEmpty())
        assertEquals(mapOf("Authorization" to "Bearer t"), ReceiverApi(base, "t").authHeaders())
    }

    @Test fun `ids are escaped in paths`() {
        assertEquals("$base/api/entries/a%2Fb/audio", ReceiverApi(base, null).audioUrl("a/b"))
    }

    @Test fun `errors carry the HTTP status`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(404))
        val api = ReceiverApi(base, "wrong")
        assertEquals(401, assertThrows(ReceiverApi.HttpError::class.java) { api.entries() }.code)
        assertEquals(404, assertThrows(ReceiverApi.HttpError::class.java) { api.entry("nope") }.code)
    }
}
