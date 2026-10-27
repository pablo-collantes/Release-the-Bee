package io.github.pablocollantes.releasethebee.sink

/**
 * Where decrypted audio goes. The phone doesn't care how the bytes travel: a sink
 * points at an address, and whatever network the phone is on (Tailscale, WireGuard,
 * OpenVPN, LAN, a Cloudflare tunnel or relay…) gets them there.
 *
 * Calls arrive on the BLE thread and must not block it: implementations queue.
 */
interface AudioSink {
    fun open()
    fun sessionStart(s: SessionInfo)
    fun audio(f: AudioFrame)
    fun sessionEnd(s: SessionInfo, reason: String)
    fun close()
}

/** One continuous stretch of audio: from RECORDING until the link drops or is paused. */
class SessionInfo(val id: String, val deviceIdHex: String, val startedAtMs: Long)

/** One decrypted RTB notification: a run of ADTS AAC-LC (16 kHz mono) frames. */
class AudioFrame(val seq: Long, val capturedAtMs: Long, val adts: ByteArray)
