package io.github.pablocollantes.releasethebee.store

import android.content.Context
import io.github.pablocollantes.releasethebee.service.Recorder
import io.github.pablocollantes.releasethebee.sink.SessionInfo

/** Non-secret settings. Secrets live in [SecretStore]. */
class Settings(ctx: Context) {

    enum class SinkKind { WEBSOCKET, TCP, NONE }

    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** BLE address of the user's RTB, learned on the first successful scan. */
    var deviceAddress: String?
        get() = p.getString("device_address", null)
        set(v) = p.edit().putString("device_address", v).apply()

    /** Its BluetoothDevice.ADDRESS_TYPE_*, or -1 if unknown (Android 13–14). */
    var deviceAddressType: Int
        get() = p.getInt("device_address_type", -1)
        set(v) = p.edit().putInt("device_address_type", v).apply()

    var sinkKind: SinkKind
        get() = runCatching { SinkKind.valueOf(p.getString("sink_kind", null)!!) }.getOrDefault(SinkKind.WEBSOCKET)
        set(v) = p.edit().putString("sink_kind", v.name).apply()

    var wsUrl: String
        get() = p.getString("ws_url", "")!!
        set(v) = p.edit().putString("ws_url", v.trim()).apply()

    var tcpHost: String
        get() = p.getString("tcp_host", "")!!
        set(v) = p.edit().putString("tcp_host", v.trim()).apply()

    var tcpPort: Int
        get() = p.getInt("tcp_port", 8741)
        set(v) = p.edit().putInt("tcp_port", v).apply()

    /**
     * Also write each recording to the phone. Off unless the user turned it on: the
     * player streams recordings from the receiver, so a phone copy is rarely needed.
     * (0.1 also defaulted to off and saved the checkbox, so existing choices carry over.)
     */
    var recordOnPhone: Boolean
        get() = p.getBoolean("record_on_phone", false)
        set(v) = p.edit().putBoolean("record_on_phone", v).apply()

    /** The user pressed Start (and not Stop): bring the service back after a reboot or update. */
    var serviceEnabled: Boolean
        get() = p.getBoolean("service_enabled", false)
        set(v) = p.edit().putBoolean("service_enabled", v).apply()

    var paused: Boolean
        get() = p.getBoolean("paused", false)
        set(v) = p.edit().putBoolean("paused", v).apply()

    /** The RTB's last recording state chosen by its button (or the app): restored on the next start. */
    var rtbRecording: Boolean
        get() = p.getBoolean("rtb_recording", true)
        set(v) = p.edit().putBoolean("rtb_recording", v).apply()

    // ---------------------------------------------------------------- the RTB's button

    /** What a single press records when it unmutes the RTB. */
    var unmuteKind: SessionInfo.Kind
        get() = enumOr("unmute_kind", SessionInfo.Kind.SUMMARY)
        set(v) = p.edit().putString("unmute_kind", v.name).apply()

    var doublePress: Recorder.Action
        get() = enumOr("double_press", Recorder.Action.COMMAND)
        set(v) = p.edit().putString("double_press", v.name).apply()

    var hold: Recorder.Action
        get() = enumOr("hold", Recorder.Action.COMMAND)
        set(v) = p.edit().putString("hold", v.name).apply()

    /** A command that isn't ended by its gesture ends itself after this. */
    var commandMaxSeconds: Int
        get() = p.getInt("command_max_s", 30)
        set(v) = p.edit().putInt("command_max_s", v).apply()

    fun recorderConfig() = Recorder.Config(unmuteKind, doublePress, hold, commandMaxSeconds * 1000L)

    // ---------------------------------------------------------------- command feedback

    var feedbackVibrate: Boolean
        get() = p.getBoolean("feedback_vibrate", true)
        set(v) = p.edit().putBoolean("feedback_vibrate", v).apply()

    /** Off by default: a blink mutes the RTB for a moment, and that audio is lost. */
    var feedbackBlink: Boolean
        get() = p.getBoolean("feedback_blink", false)
        set(v) = p.edit().putBoolean("feedback_blink", v).apply()

    /**
     * How long one blink keeps the LED off (or on). One of [BLINK_LENGTHS]: shorter blinks
     * (0.4 and before offered 0.1 to 0.6 s) were often swallowed by the RTB.
     */
    var blinkMs: Int
        get() = p.getInt("blink_ms", 1000).let { ms -> BLINK_LENGTHS.minBy { kotlin.math.abs(it - ms) } }
        set(v) = p.edit().putInt("blink_ms", v).apply()

    companion object {
        val BLINK_LENGTHS = listOf(500, 1000, 1500, 2000)
    }

    private inline fun <reified E : Enum<E>> enumOr(key: String, default: E): E =
        runCatching { enumValueOf<E>(p.getString(key, null)!!) }.getOrDefault(default)
}
