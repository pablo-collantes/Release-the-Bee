package io.github.pablocollantes.releasethebee.sink

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The simplest possible receiver contract: a TCP connection carrying nothing but the
 * ADTS AAC stream. `nc -l 9000 > rtb.aac` is a working receiver. No session markers,
 * no auth, no timestamps: use the WebSocket sink when you need those.
 */
class TcpSink(
    private val host: String,
    private val port: Int,
    onStatus: (String) -> Unit,
) : NetworkSink("tcp", onStatus) {

    @Volatile private var socket: Socket? = null
    private var out: OutputStream? = null

    override fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 10_000)
        s.keepAlive = true
        s.tcpNoDelay = true
        socket = s
        out = s.getOutputStream()
    }

    override fun greet(open: List<SessionInfo>) {}

    override fun send(item: Item) {
        if (item is Item.Audio) out!!.write(item.f.adts)
    }

    override fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    override fun close() {
        super.close()
        runCatching { socket?.close() }
    }
}
