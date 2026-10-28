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
import io.github.pablocollantes.releasethebee.rtb.RtbKey
import io.github.pablocollantes.releasethebee.rtb.RtbLink
import io.github.pablocollantes.releasethebee.rtb.hexToBytes
import io.github.pablocollantes.releasethebee.sink.AudioFrame
import io.github.pablocollantes.releasethebee.sink.AudioSink
import io.github.pablocollantes.releasethebee.sink.FileSink
import io.github.pablocollantes.releasethebee.sink.SessionInfo
import io.github.pablocollantes.releasethebee.sink.TcpSink
import io.github.pablocollantes.releasethebee.sink.WebSocketSink
import io.github.pablocollantes.releasethebee.store.SecretStore
import io.github.pablocollantes.releasethebee.store.Settings
import io.github.pablocollantes.releasethebee.ui.MainActivity

/**
 * Foreground service (type connectedDevice) that owns the RTB link and the audio sinks,
 * so streaming continues with the screen off and no UI open. START_STICKY plus
 * [BootReceiver] bring it back after the process is killed, a reboot, or an app update.
 * [Recorder] decides what each stretch of audio is (summary or command) from the RTB's
 * button; this service carries out what it asks for.
 */
class RtbService : Service(), RtbLink.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var haptics: Haptics

    @Volatile private var link: RtbLink? = null
    @Volatile private var recorder: Recorder? = null
    @Volatile private var sinks: List<AudioSink> = emptyList()
    @Volatile private var key: RtbKey? = null
    /** The RTB's deviceId, for the receiver's hello. Null until a ctrKey-only key has bound. */
    @Volatile private var deviceIdHex: String? = null
    private var frames = 0L
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        haptics = Haptics(this)
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
            ACTION_TEST -> {
                val l = link
                if (l == null) {
                    AppStatus.log("test not sent: the RTB isn't connected")
                    if (AppStatus.snapshot.serviceRunning) return START_STICKY
                    stopSelf()
                    return START_NOT_STICKY
                }
                val v = intent.getIntExtra(EXTRA_VALUE, 0)
                when (intent.getStringExtra(EXTRA_TEST)) {
                    TEST_BRIGHTNESS -> l.setBrightness(v)
                    TEST_READ_BRIGHTNESS -> l.readBrightness()
                    TEST_BLINK -> l.blink(settings.blinkMs.toLong(), v)
                    TEST_RECORD -> l.setRecording(v != 0)
                    TEST_SLEEP -> l.sleep(v)
                }
                return START_STICKY
            }
            ACTION_DEBUG_SEND -> {
                val bytes = intent.getStringExtra(EXTRA_HEX)?.let { runCatching { it.hexToBytes() }.getOrNull() }
                if (bytes == null) AppStatus.log("debug: pass the request as an even-length hex string")
                else link?.debugSend(bytes) ?: AppStatus.log("debug: no RTB link")
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
        val k = SecretStore(this).rtbKey
        val problem = when {
            !hasPermissions(this) -> "Bluetooth permission isn't granted"
            k == null -> "import your RTB's key first"
            else -> null
        }
        if (problem != null) {
            AppStatus.log("not starting: $problem")
            AppStatus.update { it.copy(serviceRunning = false, link = RtbLink.State.FAILED, linkDetail = problem) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        key = k
        deviceIdHex = k!!.deviceIdHex
        sinks = buildSinks()
        sinks.forEach { it.open() }
        AppStatus.update { it.copy(serviceRunning = true) }
        recorder = Recorder({ settings.recorderConfig() }, recorderOut)
        // Muted by its button stays muted, even across a restart. With unmuting set to record
        // commands, the RTB rests muted until a press.
        val recordOnConnect = settings.rtbRecording && settings.unmuteKind == SessionInfo.Kind.SUMMARY
        link = RtbLink(this, k, settings.deviceAddress, settings.deviceAddressType, this, recordOnConnect).also {
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

    private fun buildSinks(): List<AudioSink> {
        val list = mutableListOf<AudioSink>()
        val app = "release-the-bee-android/${BuildConfig.VERSION_NAME}"
        when (settings.sinkKind) {
            Settings.SinkKind.WEBSOCKET -> if (settings.wsUrl.isNotBlank()) {
                list += WebSocketSink(
                    settings.wsUrl, SecretStore(this).sinkToken, app, { deviceIdHex },
                    sinkStatus("network"),
                ) { e ->
                    // Titles are the user's content: keep them out of the log.
                    AppStatus.log("receiver: recording ${e.id.take(8)} is ${e.status ?: "updated"}")
                    AppStatus.entryPushed(e)
                }
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

    override fun onAddressConfirmed(address: String, addressType: Int) {
        settings.deviceAddress = address
        settings.deviceAddressType = addressType
        AppStatus.log("remembered RTB at $address")
    }

    override fun onDeviceBound(deviceId: ByteArray) {
        val bound = (key as? RtbKey.CtrOnly)?.boundTo(deviceId) ?: return
        key = bound
        deviceIdHex = bound.deviceIdHex
        // Persist the binding, unless the user replaced the key in the meantime.
        val secrets = SecretStore(this)
        val stored = secrets.rtbKey
        if (stored != null && stored.sameKeyAs(bound)) secrets.rtbKey = bound
    }

    override fun onStreamStart(deviceIdHex: String) {
        recorder?.streamStarted(deviceIdHex)
    }

    override fun onAudio(seq: Long, adts: ByteArray) {
        recorder?.audio(AudioFrame(seq, System.currentTimeMillis(), adts))
        if (++frames % 8 == 0L) {        // ~1/s
            AppStatus.update { it.copy(frames = frames) }
            if (frames % (8 * 60) == 0L) wakeLock.acquire(WAKELOCK_MS)   // extend while streaming
            refreshNotification(force = false)
        }
    }

    override fun onStreamEnd(reason: String) {
        recorder?.streamEnded(reason)
    }

    override fun onButton(b: RtbLink.Button) {
        recorder?.button(b)
    }

    override fun onReply(line: String) {
        AppStatus.log(line)
        AppStatus.update { it.copy(reply = line) }
    }

    override fun onBrightness(level: Int) = AppStatus.update { it.copy(brightness = level) }

    override fun onRecordingChoice(on: Boolean) {
        settings.rtbRecording = on
    }

    // ------------------------------------------------------------------ Recorder.Output

    private val recorderOut = object : Recorder.Output {
        override fun sessionStart(s: SessionInfo) {
            sinks.forEach { it.sessionStart(s) }
            val inside = s.parentId?.let { " inside ${it.take(8)}" } ?: ""
            AppStatus.log("recording ${s.id.take(8)}: ${s.kind.wire}$inside, from ${s.trigger?.wire ?: "?"}")
            if (s.parentId == null) {
                frames = 0
                AppStatus.update { it.copy(sessionStartedAt = s.startedAtMs, frames = 0, commandOpen = s.kind == SessionInfo.Kind.COMMAND) }
            } else {
                AppStatus.update { it.copy(commandOpen = true) }
            }
            refreshNotification(force = true)
        }

        override fun audio(f: AudioFrame) = sinks.forEach { it.audio(f) }

        override fun sessionEnd(s: SessionInfo, reason: String) {
            sinks.forEach { it.sessionEnd(s, reason) }
            AppStatus.update {
                if (s.parentId == null) it.copy(sessionStartedAt = null, commandOpen = false) else it.copy(commandOpen = false)
            }
            refreshNotification(force = true)
        }

        override fun setRecording(on: Boolean) {
            link?.setRecording(on)
        }

        override fun commandFeedback(start: Boolean, ledShows: Boolean) {
            if (settings.feedbackVibrate) if (start) haptics.commandStart() else haptics.commandEnd()
            // The light already changes when the RTB unmutes or mutes for the command.
            if (settings.feedbackBlink && !ledShows) link?.blink(settings.blinkMs.toLong(), if (start) 1 else 2)
        }

        override fun schedule(ms: Long, f: () -> Unit): () -> Unit {
            val r = Runnable(f)
            main.postDelayed(r, ms)
            return { main.removeCallbacks(r) }
        }

        override fun log(line: String) = AppStatus.log(line)
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
            RtbLink.State.STREAMING -> if (st.commandOpen) "Recording a command" else "Streaming from your RTB"
            RtbLink.State.PAUSED -> "Paused: RTB connected, not recording"
            RtbLink.State.MUTED -> "RTB muted: its button starts recording"
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
        /** Requests from the light tests in Settings: [EXTRA_TEST] is one of the TEST_ values. */
        const val ACTION_TEST = "io.github.pablocollantes.releasethebee.TEST"
        const val EXTRA_TEST = "test"
        const val EXTRA_VALUE = "value"
        const val TEST_BRIGHTNESS = "brightness"            // value: 0-100
        const val TEST_READ_BRIGHTNESS = "read_brightness"
        const val TEST_BLINK = "blink"                      // value: how many flashes
        const val TEST_RECORD = "record"                    // value: 1 record, 0 mute
        const val TEST_SLEEP = "sleep"                      // value: device state 2 or 4

        /** Debug builds only, from adb: see `src/debug/…/DebugReceiver.kt`. */
        const val ACTION_DEBUG_SEND = "io.github.pablocollantes.releasethebee.DEBUG_SEND"
        const val EXTRA_HEX = "hex"

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

        fun test(ctx: Context, what: String, value: Int = 0) {
            ctx.startService(Intent(ctx, RtbService::class.java).setAction(ACTION_TEST).putExtra(EXTRA_TEST, what).putExtra(EXTRA_VALUE, value))
        }

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RtbService::class.java).setAction(action)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
