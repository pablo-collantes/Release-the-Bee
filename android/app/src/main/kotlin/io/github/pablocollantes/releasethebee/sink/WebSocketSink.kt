package io.github.pablocollantes.releasethebee.sink

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Streams rtb-stream/1 to a ws:// or wss:// URL. This is the general-purpose sink: the
 * receiver can sit behind a VPN address, a LAN IP, a reverse proxy, a Cloudflare tunnel
 * or a relay. An optional bearer token is sent as `Authorization: Bearer …`.
 *
 * The receiver talks back on the same connection: `entry` pushes (a recording's entry
 * changed) go to [onEntry], so the app never has to poll. Other messages are ignored.
 * [deviceIdHex] is read at every connect, because a ctrKey-only key learns its RTB late.
 */
class WebSocketSink(
    private val url: String,
    private val bearer: String?,
    private val app: String,
    private val deviceIdHex: () -> String?,
    onStatus: (String) -> Unit,
    private val onEntry: (EntryPush) -> Unit,
) : NetworkSink("ws", onStatus) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)        // notices a dead path (VPN reroute, Wi-Fi drop)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var failure: String? = null

    override fun connect() {
        failure = null
        val opened = CountDownLatch(1)
        val req = Request.Builder().url(url).apply {
            if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
        }.build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

            override fun onMessage(webSocket: WebSocket, text: String) {
                StreamProtocol.parseServerMessage(text)?.let(onEntry)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                failure = "receiver closed the connection ($code${if (reason.isNotEmpty()) " $reason" else ""})"
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (failure == null) failure = "closed"
                opened.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = (t.message ?: t.javaClass.simpleName) + (response?.let { " (HTTP ${it.code})" } ?: "")
                opened.countDown()
            }
        })
        if (!opened.await(20, TimeUnit.SECONDS)) throw IOException("timed out connecting")
        failure?.let { throw IOException(it) }
    }

    override fun greet(open: List<SessionInfo>) {
        sendText(StreamProtocol.hello(app, deviceIdHex()))
        open.forEach { sendText(StreamProtocol.sessionStart(it, resumed = true)) }
    }

    override fun send(item: Item) {
        failure?.let { throw IOException(it) }
        val w = ws ?: throw IOException("not connected")
        when (item) {
            is Item.Start -> sendText(StreamProtocol.sessionStart(item.s, resumed = false))
            is Item.End -> sendText(StreamProtocol.sessionEnd(item.s, item.reason))
            is Item.Audio -> if (!w.send(StreamProtocol.audio(item.f).toByteString())) throw IOException("connection closed")
        }
        // Keep OkHttp's own buffer short, so a silently dead link loses seconds of audio
        // instead of everything handed over since it died.
        while (w.queueSize() > 64 * 1024 && failure == null) Thread.sleep(50)
    }

    private fun sendText(s: String) {
        if (ws?.send(s) != true) throw IOException("connection closed")
    }

    override fun disconnect() {
        ws?.cancel()
        ws = null
    }

    override fun close() {
        super.close()
        ws?.close(1000, "app stopped")
    }
}
