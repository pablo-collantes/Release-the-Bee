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
    /** [discard]: the user deleted the recording while it ran. Nothing of it should be kept. */
    fun sessionEnd(s: SessionInfo, reason: String, discard: Boolean = false)
    fun close()
}

/**
 * One continuous stretch of audio: from RECORDING until the link drops or is paused.
 *
 * [kind] says what the recording is for and [trigger] which button gesture started it.
 * A command recorded in the middle of a summary recording has [parentId] set to that
 * summary's id: audio goes to the command until it ends, then back to the parent.
 */
class SessionInfo(
    val id: String,
    val deviceIdHex: String,
    val startedAtMs: Long,
    val kind: Kind = Kind.SUMMARY,
    val trigger: Trigger? = Trigger.BUTTON,
    val parentId: String? = null,
) {
    enum class Kind(val wire: String) { SUMMARY("summary"), COMMAND("command") }

    enum class Trigger(val wire: String) { BUTTON("button"), DOUBLE_PRESS("double_press"), HOLD("hold"), APP("app") }
}

/** One decrypted RTB notification: a run of ADTS AAC-LC (16 kHz mono) frames. */
class AudioFrame(val seq: Long, val capturedAtMs: Long, val adts: ByteArray)
