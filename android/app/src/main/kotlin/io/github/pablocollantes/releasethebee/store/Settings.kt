package io.github.pablocollantes.releasethebee.store

import android.content.Context

/** Non-secret settings. Secrets live in [SecretStore]. */
class Settings(ctx: Context) {

    enum class SinkKind { WEBSOCKET, TCP, NONE }

    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** BLE address of the user's RTB, learned on the first successful scan. */
    var deviceAddress: String?
        get() = p.getString("device_address", null)
        set(v) = p.edit().putString("device_address", v).apply()

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
        get() = p.getInt("tcp_port", 8766)
        set(v) = p.edit().putInt("tcp_port", v).apply()

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
}
