package io.github.pablocollantes.releasethebee.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import io.github.pablocollantes.releasethebee.BuildConfig
import io.github.pablocollantes.releasethebee.R
import io.github.pablocollantes.releasethebee.rtb.RtbLink
import io.github.pablocollantes.releasethebee.sink.AudioFrame
import io.github.pablocollantes.releasethebee.sink.AudioSink
import io.github.pablocollantes.releasethebee.sink.FileSink
import io.github.pablocollantes.releasethebee.sink.SessionInfo
import io.github.pablocollantes.releasethebee.sink.TcpSink
import io.github.pablocollantes.releasethebee.sink.WebSocketSink
import io.github.pablocollantes.releasethebee.store.SecretStore
import io.github.pablocollantes.releasethebee.store.Settings
import io.github.pablocollantes.releasethebee.ui.MainActivity
import java.util.UUID

/**
 * Foreground service (type connectedDevice) that owns the RTB link and the audio sinks,
 * so streaming continues with the screen off and no UI open. START_STICKY plus
 * [BootReceiver] bring it back after the process is killed, a reboot, or an app update.
 */
class RtbService : Service(), RtbLink.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var wakeLock: PowerManager.WakeLock

    private var link: RtbLink? = null
    private var sinks: List<AudioSink> = emptyList()
    @Volatile private var session: SessionInfo? = null
    private var frames = 0L
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReleaseTheBee:stream")
            .apply { setReferenceCounted(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "RTB connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Release the Bee keeps your RTB connected"
            },
        )
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.serviceEnabled = false
                shutdown {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_PAUSE, ACTION_RESUME -> {
                val pause = intent.action == ACTION_PAUSE
                settings.paused = pause
                link?.setPaused(pause)
                if (link != null) return START_STICKY
            }
            ACTION_RELOAD -> if (link != null) {
                shutdown { begin() }
                return START_STICKY
            }
        }
        // ACTION_START, or a sticky restart (null intent).
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (link == null) begin()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(bluetoothReceiver)
        link?.release()
        link = null
        sinks.forEach { it.close() }
        sinks = emptyList()
        if (wakeLock.isHeld) wakeLock.release()
        AppStatus.update { it.copy(serviceRunning = false, link = RtbLink.State.STOPPED, linkDetail = "stopped") }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun goForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: Exception) {
        AppStatus.log("can't run in the background: ${e.message}")
        false
    }

    private fun begin() {
        val token = SecretStore(this).sealToken
        val problem = when {
            !hasPermissions(this) -> "Bluetooth permission isn't granted"
            token == null -> "import your RTB's seal token first"
            else -> null
        }
        if (problem != null) {
            AppStatus.log("not starting: $problem")
            AppStatus.update { it.copy(serviceRunning = false, link = RtbLink.State.FAILED, linkDetail = problem) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        sinks = buildSinks(token!!.deviceIdHex)
        sinks.forEach { it.open() }
        AppStatus.update { it.copy(serviceRunning = true) }
        link = RtbLink(this, token, settings.deviceAddress, this).also {
            it.setPaused(settings.paused)
            it.start()
        }
    }

    /** Stop the link politely, close the sinks, then run [then] on the main thread. */
    private fun shutdown(then: () -> Unit) {
        val l = link
        if (l == null) {
            then()
            return
        }
        link = null
        l.stop {
            main.post {
                l.release()
                sinks.forEach { it.close() }
                sinks = emptyList()
                if (wakeLock.isHeld) wakeLock.release()
                AppStatus.update { it.copy(serviceRunning = false, sinks = emptyMap()) }
                then()
            }
        }
    }

    private fun buildSinks(deviceIdHex: String): List<AudioSink> {
        val list = mutableListOf<AudioSink>()
        val app = "release-the-bee-android/${BuildConfig.VERSION_NAME}"
        when (settings.sinkKind) {
            Settings.SinkKind.WEBSOCKET -> if (settings.wsUrl.isNotBlank()) {
                list += WebSocketSink(
                    settings.wsUrl, SecretStore(this).sinkToken, app, deviceIdHex,
                    sinkStatus("network"),
                ) { msg -> AppStatus.log("receiver: ${msg.take(200)}") }
            }
            Settings.SinkKind.TCP -> if (settings.tcpHost.isNotBlank()) {
                list += TcpSink(settings.tcpHost, settings.tcpPort, sinkStatus("network"))
            }
            Settings.SinkKind.NONE -> {}
        }
        if (settings.recordOnPhone) {
            list += FileSink(getExternalFilesDir("recordings")!!, sinkStatus("phone"))
        }
        if (list.isEmpty()) AppStatus.log("no destination set: audio will be decrypted and discarded")
        return list
    }

    private fun sinkStatus(name: String): (String) -> Unit = { status ->
        AppStatus.update { it.copy(sinks = it.sinks + (name to status)) }
        if (!status.startsWith("recording")) AppStatus.log("$name: $status")
        refreshNotification(force = false)
    }

    // ------------------------------------------------------------------ RtbLink.Listener (link thread)

    override fun onState(state: RtbLink.State, detail: String) {
        AppStatus.update { it.copy(link = state, linkDetail = detail) }
        AppStatus.log(detail)
        if (state == RtbLink.State.STREAMING) {
            wakeLock.acquire(WAKELOCK_MS)
        } else if (wakeLock.isHeld) {
            wakeLock.release()
        }
        if (state == RtbLink.State.FAILED) settings.serviceEnabled = false
        refreshNotification(force = true)
    }

    override fun onLog(line: String) = AppStatus.log(line)

    override fun onAddressConfirmed(address: String) {
        settings.deviceAddress = address
        AppStatus.log("remembered RTB at $address")
    }

    override fun onStreamStart(deviceIdHex: String) {
        val s = SessionInfo(UUID.randomUUID().toString(), deviceIdHex, System.currentTimeMillis())
        session = s
        frames = 0
        sinks.forEach { it.sessionStart(s) }
        AppStatus.update { it.copy(sessionStartedAt = s.startedAtMs, frames = 0) }
    }

    override fun onAudio(seq: Long, adts: ByteArray) {
        val f = AudioFrame(seq, System.currentTimeMillis(), adts)
        sinks.forEach { it.audio(f) }
        if (++frames % 8 == 0L) {        // ~1/s
            AppStatus.update { it.copy(frames = frames) }
            if (frames % (8 * 60) == 0L) wakeLock.acquire(WAKELOCK_MS)   // extend while streaming
            refreshNotification(force = false)
        }
    }

    override fun onStreamEnd(reason: String) {
        val s = session ?: return
        session = null
        sinks.forEach { it.sessionEnd(s, reason) }
        AppStatus.update { it.copy(sessionStartedAt = null) }
    }

    // ------------------------------------------------------------------ notification

    private fun refreshNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotified < 5_000) return
        lastNotified = now
        main.post {
            if (link != null) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val st = AppStatus.snapshot
        val title = when (st.link) {
            RtbLink.State.STREAMING -> "Streaming from your RTB"
            RtbLink.State.PAUSED -> "Paused: RTB connected, not recording"
            RtbLink.State.FAILED -> "Needs attention"
            else -> "Keeping your RTB connected"
        }
        val text = buildString {
            st.sessionStartedAt?.let {
                val mins = (System.currentTimeMillis() - it) / 60_000
                append("${mins} min · ")
            }
            append(st.sinks["network"]?.let { "receiver $it" } ?: st.linkDetail)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = if (settings.paused) ACTION_RESUME else ACTION_PAUSE
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rtb)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, if (settings.paused) "Resume" else "Pause", servicePending(toggle)).build())
            .addAction(Notification.Action.Builder(null, "Stop", servicePending(ACTION_STOP)).build())
            .build()
    }

    private fun servicePending(action: String) = PendingIntent.getService(
        this, action.hashCode(), Intent(this, RtbService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> link?.bluetoothChanged(true)
                BluetoothAdapter.STATE_OFF -> link?.bluetoothChanged(false)
            }
        }
    }

    companion object {
        const val ACTION_START = "io.github.pablocollantes.releasethebee.START"
        const val ACTION_STOP = "io.github.pablocollantes.releasethebee.STOP"
        const val ACTION_PAUSE = "io.github.pablocollantes.releasethebee.PAUSE"
        const val ACTION_RESUME = "io.github.pablocollantes.releasethebee.RESUME"
        const val ACTION_RELOAD = "io.github.pablocollantes.releasethebee.RELOAD"

        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_MS = 10 * 60 * 1000L

        val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        /** Bluetooth is required; notifications are only nice to have. */
        fun hasPermissions(ctx: Context) = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RtbService::class.java).setAction(action)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
