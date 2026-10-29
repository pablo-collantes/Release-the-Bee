package io.github.pablocollantes.releasethebee.rtb

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.min

/**
 * One persistent connection to one RTB.
 *
 * On every connect it runs the handshake on its own:
 *
 *     MTU 517 → discover → notify(control, audio)
 *       → GetSecureIds ─┬─ sealed ───────────────────────────────┐
 *                       └─ not sealed → SetEncryptionKey(token)   │
 *                                       → GetSecureIds (sealed?) ─┤
 *       → GetSessionNonce → SetDeviceState(RECORDING) → audio ◀──┘
 *
 * The device ID in GetSecureIds must match the key's, so a scan can never latch onto
 * somebody else's RTB. A full seal token names its device. A bare ctrKey doesn't: the
 * link binds it to the first RTB it reaches ([Listener.onDeviceBound]) and holds it to
 * that one from then on. A ctrKey can't seal, so an unsealed RTB is a dead end for it,
 * and audio that never decrypts (even after a fresh nonce) means it's the wrong key.
 *
 * Once streaming, a lost link is re-dialled with autoConnect, which
 * lets the Bluetooth stack wait for the RTB to come back in range instead of polling.
 *
 * Whether the RTB should record survives reconnects: muted by its button (or by the
 * app, [setRecording]) stays muted after the link comes back. Pause wins over both. A
 * command's temporary unmute isn't remembered: if the link drops mid-command, the RTB
 * goes back to its resting state. State events right after we changed the state
 * ourselves are the RTB echoing us (or settling), not its button.
 *
 * Everything runs on one private HandlerThread (GATT callbacks included), so there are
 * no locks. Listener callbacks arrive on that thread.
 */
@SuppressLint("MissingPermission") // the service checks BLUETOOTH_SCAN/CONNECT before start()
class RtbLink(
    private val ctx: Context,
    private val key: RtbKey,
    knownAddress: String?,
    private val knownAddressType: Int,
    private val listener: Listener,
    recordOnConnect: Boolean = true,
    warmMute: Boolean = false,
) {
    enum class State { STOPPED, SCANNING, CONNECTING, HANDSHAKE, SEALING, STREAMING, PAUSED, MUTED, WAITING, FAILED }

    interface Listener {
        fun onState(state: State, detail: String)
        fun onLog(line: String)
        /**
         * A scanned device proved to be the key's RTB: remember it for next time.
         * [addressType] is BluetoothDevice.ADDRESS_TYPE_PUBLIC/RANDOM, or -1 if unknown.
         */
        fun onAddressConfirmed(address: String, addressType: Int)
        /** A ctrKey-only key reached its first RTB: remember [deviceId] and accept no other. */
        fun onDeviceBound(deviceId: ByteArray)
        fun onStreamStart(deviceIdHex: String)
        fun onAudio(seq: Long, adts: ByteArray)
        fun onStreamEnd(reason: String)
        /** A button gesture the RTB reports but doesn't act on. Not sent while paused. */
        fun onButton(b: Button)
        /** The RTB answered a request sent outside the handshake (the light tests…). */
        fun onReply(line: String)
        /** Whether the RTB should record changed (its button, or [setRecording]): keep it for the next start. */
        fun onRecordingChoice(on: Boolean)
        /** The RTB's battery: [level] in percent and/or whether it's [charging]; null = not said. */
        fun onBattery(level: Int?, charging: Boolean?)
    }

    /** A single press isn't here: the RTB mutes or unmutes itself, and the stream follows. */
    enum class Button { DOUBLE_PRESS, HOLD_START, HOLD_END }

    private enum class Step { IDS, SEAL, IDS_AFTER_SEAL, NONCE, RECORD, MUTE }

    private val thread = HandlerThread("rtb-link").apply { start() }
    private val h = Handler(thread.looper)
    private val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter

    private var address = knownAddress
    private var device: BluetoothDevice? = null     // the confirmed RTB, as the stack knows it
    private var running = false
    private var paused = false
    private var wantRecording = recordOnConnect     // the RTB's resting state: its button, or ours
    private var warmMute = warmMute                  // muted = its mic kept running (see [restState])
    private var borrowed = false                     // recording just for a command; forgotten with the link
    private var settleUntil = 0L                     // state events before this follow our own change
    private var blinking = false                     // our own mute/unmute flicker: ignore its state events
    private var blinkGen = 0
    private var blinkSeen = 0                        // state events during the blink...
    private var blinkLast: Boolean? = null           // ...and the last one (true = recording)
    private var holdStartedAt = 0L                   // a hold registered: the RTB unmutes itself
    private var holdEndedAt = 0L                     // its button let go of: the RTB mutes itself
    private var batteryAsked = false                 // once per connection; it reports changes itself
    private var stateTests = 0                       // sleep requests awaiting their reply
    private var idleFrames = 0                       // audio that arrived while we weren't recording
    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var scanCb: ScanCallback? = null
    private var verifying = false                    // connected to a scan hit, not yet proven ours
    private val rejected = mutableSetOf<String>()    // scan hits that turned out to be other RTBs
    private var attempts = 0                         // consecutive failures; 0 = next dial is direct
    private var step: Step? = null
    private var decryptor: AudioDecryptor? = null
    private var streaming = false
    private var undecodable = 0                      // consecutive notifications with no AAC in them
    private var lastNonceRefresh = 0L
    private var keyProven = false                    // some audio has decrypted to AAC with this key
    private var deviceId: ByteArray? = key.deviceId  // the RTB we answer to (null: a ctrKey not bound yet)
    private val deviceHex: String get() = deviceId?.toHex() ?: "?"

    // Android runs one GATT operation at a time; queue them.
    private val ops = ArrayDeque<() -> Unit>()
    private var opBusy = false

    // ------------------------------------------------------------------ public API

    fun start() = post {
        if (running) return@post
        running = true
        attempts = 0
        connectOrScan()
    }

    /** Mute the RTB (so it stops spending battery on radio), disconnect, then [onStopped]. */
    fun stop(onStopped: () -> Unit = {}) = post {
        running = false
        cancelTimers()
        stopScan()
        if (gatt != null && control != null && (streaming || step != null)) {
            writeControl(RtbProtocol.setDeviceState(RtbProtocol.STATE_MUTE))
            h.postDelayed({ teardown("stopped"); setState(State.STOPPED, "stopped"); onStopped() }, 600)
        } else {
            teardown("stopped")
            setState(State.STOPPED, "stopped")
            onStopped()
        }
    }

    /** Muted in [RtbProtocol.STATE_SLEEP_SW_VAD] (the mic kept running) or in mute proper. */
    fun setWarmMute(on: Boolean) = post {
        if (warmMute == on) return@post
        warmMute = on
        if (gatt != null && control != null && step == null && !blinking && !wanted) muteNow()
    }

    /** Paused = stay connected and sealed, but keep the RTB muted. Survives reconnects. */
    fun setPaused(p: Boolean) = post {
        if (paused == p) return@post
        paused = p
        if (p) decryptor = null                     // a fresh nonce on resume
        if (gatt == null || control == null || step != null) return@post   // applied by the handshake
        reconcile()
    }

    /**
     * Record or mute, as the RTB's button would. Remembered across reconnects; ignored
     * (until resumed) while paused. Mid-handshake, the handshake applies it at its end.
     *
     * [temporary]: just for a command. Recording that way isn't remembered; stopping that
     * way goes back to the resting state (or mutes, if the RTB was recording anyway).
     */
    fun setRecording(on: Boolean, temporary: Boolean = false) = post {
        when {
            !temporary -> {
                borrowed = false
                choose(on)
            }
            on -> borrowed = true
            borrowed -> borrowed = false
            else -> choose(false)
        }
        if (gatt == null || control == null || step != null) return@post
        reconcile()
    }

    /**
     * Flash the LED by flipping the RTB's state for [offMs], [times] times: off and back on
     * while recording (that audio is lost), on and back off while muted. The recording
     * carries on: the state events this causes are ignored.
     */
    fun blink(offMs: Long, times: Int) = post {
        if (gatt == null || control == null || step != null || blinking) return@post reply("blink not sent: the RTB isn't connected or is busy")
        val flip = if (streaming) restState else RtbProtocol.STATE_RECORDING
        val back = if (streaming) RtbProtocol.STATE_RECORDING else restState
        val n = times.coerceIn(1, 5)
        blinking = true
        blinkSeen = 0
        blinkLast = null
        val gen = ++blinkGen
        val at = { delay: Long, f: () -> Unit -> h.postDelayed({ if (gen == blinkGen && gatt != null) f() }, delay) }
        // The RTB drops state changes that come too fast: keep the gap as long as the flash.
        val gap = maxOf(offMs, BLINK_MIN_MS)
        var t = 0L
        repeat(n) {
            at(t) { writeControl(RtbProtocol.setDeviceState(flip)) }
            at(t + offMs) { writeControl(RtbProtocol.setDeviceState(back)) }
            t += offMs + gap
        }
        at(t + BLINK_SETTLE_MS) { blinkDone(back == RtbProtocol.STATE_RECORDING, expected = 2 * n) }
        log("blink: ${if (streaming) "off" else "on"} for $offMs ms, ${n}×")
    }

    /**
     * The blink's state events were ignored. If the RTB didn't end where the blink left
     * it, either its button was pressed meanwhile (more events than the blink makes: go
     * with the button) or it dropped the last flip (set it again).
     */
    private fun blinkDone(backOn: Boolean, expected: Int) {
        blinking = false
        val last = blinkLast ?: return
        if (last == backOn || step != null || paused) return
        if (blinkSeen > expected) {
            log("its button was pressed during the blink")
            settleUntil = 0
            deviceState(last)
        } else {
            log("the RTB missed the end of the blink; setting it again")
            if (wanted) record() else muteNow()
        }
    }

    /**
     * Put the RTB in a sleep state ([RtbProtocol.STATE_SLEEP_HW_VAD] or
     * [RtbProtocol.STATE_SLEEP_SW_VAD]) to see what it does. [setRecording] or its button
     * wakes it.
     */
    fun sleep(state: Int) = direct("device state $state") {
        choose(false)
        endStream("RTB put to sleep from the app")
        stateTests++
        writeControl(RtbProtocol.setDeviceState(state))
        setState(State.MUTED, "RTB in sleep state $state (a test); Record or its button wakes it")
    }

    fun bluetoothChanged(on: Boolean) = post {
        if (!running) return@post
        if (on) {
            attempts = 0
            cancelTimers()
            connectOrScan()
        } else {
            cancelTimers()
            stopScan()
            teardown("Bluetooth turned off")
            setState(State.WAITING, "Bluetooth is off")
        }
    }

    /** Final shutdown, without the polite mute. Use [stop] first when there is time. */
    fun release() {
        post {
            running = false
            cancelTimers()
            stopScan()
            teardown("released")
        }
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ connect / scan

    private fun connectOrScan() {
        if (!running) return
        if (adapter?.isEnabled != true) {
            setState(State.WAITING, "Bluetooth is off")
            return
        }
        val dev = device ?: address?.let { remoteDevice(it, knownAddressType) }
        if (dev == null) scan() else connect(dev, verify = false)
    }

    private fun connect(dev: BluetoothDevice, verify: Boolean) {
        val addr = dev.address
        verifying = verify
        // First dial is direct (fast, times out). After a failure, dial with autoConnect:
        // the stack then waits, at low duty cycle, for the RTB to show up again.
        val auto = attempts > 0 && !verify
        setState(
            State.CONNECTING,
            if (auto) "waiting for RTB $addr to come back in range" else "connecting to $addr",
        )
        gatt = dev.connectGatt(
            ctx, auto, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h,
        )
        if (!auto) h.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    /**
     * A device for a stored address. With the address type a scan saw (Android 15+), the
     * stack can dial it even after a reboot has emptied its cache. Without it, the stack
     * uses what it remembers. Don't guess the type from the address bits: some RTBs use
     * public addresses that look random.
     */
    private fun remoteDevice(addr: String, type: Int): BluetoothDevice =
        if (type == BluetoothDevice.ADDRESS_TYPE_PUBLIC || type == BluetoothDevice.ADDRESS_TYPE_RANDOM) {
            adapter.getRemoteLeDevice(addr, type)
        } else {
            adapter.getRemoteDevice(addr)
        }

    private fun scan() {
        val scanner = adapter.bluetoothLeScanner ?: return retryLater("Bluetooth scanner unavailable")
        setState(State.SCANNING, "looking for your RTB nearby")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                post { onScanHit(result) }
            }
            override fun onScanFailed(errorCode: Int) {
                post { stopScan(); retryLater("scan failed (error $errorCode)") }
            }
        }
        scanCb = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        h.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private fun onScanHit(r: ScanResult) {
        if (scanCb == null || gatt != null) return
        val addr = r.device.address
        if (addr in rejected) return
        val name = r.scanRecord?.deviceName
        val advertisesService = r.scanRecord?.serviceUuids?.any { it.uuid == RtbProtocol.SERVICE } == true
        if (!advertisesService && name?.contains("bee", ignoreCase = true) != true) return
        stopScan()
        log("found ${name ?: "an RTB"} at $addr (rssi ${r.rssi}); checking it's yours")
        attempts = 0
        connect(r.device, verify = true)    // the scan's device object carries the right address type
    }

    private fun stopScan() {
        h.removeCallbacks(scanTimeout)
        val cb = scanCb ?: return
        scanCb = null
        runCatching { adapter.bluetoothLeScanner?.stopScan(cb) }
    }

    private fun retryLater(reason: String) {
        if (!running) return
        val delay = (1000L shl min(attempts, 6)).coerceAtMost(60_000)
        setState(State.WAITING, "$reason; retrying in ${delay / 1000}s")
        h.postDelayed(retry, delay)
    }

    private val retry = Runnable { connectOrScan() }
    private val scanTimeout = Runnable { stopScan(); attempts++; retryLater("no RTB found nearby") }
    private val connectTimeout = Runnable { onLinkDown("connection attempt timed out") }
    private val replyTimeout = Runnable {
        if (step == Step.MUTE) {           // a missing mute ack isn't worth a reconnect
            step = null
            settledMuted()
        } else {
            onLinkDown("RTB didn't answer (${step?.name})")
        }
    }

    private fun cancelTimers() {
        h.removeCallbacks(retry)
        h.removeCallbacks(scanTimeout)
        h.removeCallbacks(connectTimeout)
        h.removeCallbacks(replyTimeout)
    }

    private fun onLinkDown(reason: String) {
        h.removeCallbacks(connectTimeout)
        teardown(reason)
        if (!running) return
        attempts++
        if (address == null) {
            h.postDelayed(retry, 1000)     // still looking for our RTB: rescan
        } else {
            retryLater(reason)
        }
    }

    private fun teardown(reason: String) {
        endStream(reason)
        reportIdleFrames()
        borrowed = false                // a command ends with the link
        blinkGen++
        blinking = false
        batteryAsked = false
        stateTests = 0
        h.removeCallbacks(replyTimeout)
        step = null
        decryptor = null
        control = null
        verifying = false
        ops.clear()
        opBusy = false
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }

    // ------------------------------------------------------------------ GATT

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) { runCatching { g.close() }; return }   // an attempt we already gave up on
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    h.removeCallbacks(connectTimeout)
                    setState(State.HANDSHAKE, "connected; setting up")
                    if (!g.requestMtu(RtbProtocol.MTU)) g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> onLinkDown("link lost (status $status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            if (mtu < RtbProtocol.AUDIO_FRAME_LEN + 3) {
                log("warning: MTU $mtu is too small for ${RtbProtocol.AUDIO_FRAME_LEN}-byte audio frames")
            }
            if (!g.discoverServices()) onLinkDown("couldn't start service discovery")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            val svc = g.getService(RtbProtocol.SERVICE)
            val ctl = svc?.getCharacteristic(RtbProtocol.CONTROL)
            val aud = svc?.getCharacteristic(RtbProtocol.AUDIO)
            if (ctl == null || aud == null) {
                if (verifying) rejected += g.device.address
                onLinkDown("${g.device.address} doesn't have the RTB service")
                return
            }
            control = ctl
            enableNotify(g, ctl)
            enableNotify(g, aud)
            send(Step.IDS, RtbProtocol.getSecureIds())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) log("control write failed (status $status)")
            opDone()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) log("enabling notifications failed (status $status)")
            opDone()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (g !== gatt) return
            when (c.uuid) {
                RtbProtocol.CONTROL -> onControl(value)
                RtbProtocol.AUDIO -> onAudio(value)
            }
        }
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) = enqueue {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(RtbProtocol.CCCD)
        if (d == null) {
            log("no notification descriptor on ${c.uuid}")
            opDone()
            return@enqueue
        }
        val rc = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            log("enabling notifications refused (rc $rc)")
            opDone()
        }
    }

    private fun writeControl(bytes: ByteArray) = enqueue {
        val g = gatt
        val c = control
        if (g == null || c == null) {
            opDone()
            return@enqueue
        }
        val rc = g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            log("control write refused (rc $rc)")
            opDone()
        }
    }

    private fun enqueue(op: () -> Unit) {
        ops.addLast(op)
        pump()
    }

    private fun pump() {
        if (opBusy) return
        val op = ops.removeFirstOrNull() ?: return
        opBusy = true
        op()
    }

    private fun opDone() {
        opBusy = false
        pump()
    }

    // ------------------------------------------------------------------ handshake

    /** Send a control request and expect its reply within [REPLY_TIMEOUT_MS]. */
    private fun send(next: Step, bytes: ByteArray) {
        step = next
        h.removeCallbacks(replyTimeout)
        h.postDelayed(replyTimeout, REPLY_TIMEOUT_MS)
        writeControl(bytes)
    }

    private fun onControl(d: ByteArray) {
        when (val r = RtbProtocol.parse(d)) {
            is Reply.Ok -> onOk(r)
            is Reply.KeyAlreadySet -> if (step == Step.SEAL) {
                log("RTB says it already has a key; re-checking")
                send(Step.IDS_AFTER_SEAL, RtbProtocol.getSecureIds())
            }
            is Reply.Failed -> onFailed(r)
            is Reply.Event -> onEvent(r)
            is Reply.Unknown -> log("unrecognised control message ${r.raw.toHex()}")
        }
    }

    private fun onOk(r: Reply.Ok) {
        when (r.op) {
            RtbProtocol.OP_GET_SECURE_IDS -> {
                if (step != Step.IDS && step != Step.IDS_AFTER_SEAL) return
                val ids = SecureIds.parse(r.payload) ?: return onLinkDown("RTB sent a short SecureIds reply")
                val expected = deviceId
                if (expected != null && !ids.deviceId.contentEquals(expected)) return wrongDevice(ids)
                if (!ids.sealed && key !is RtbKey.Full) {
                    return fail("This RTB isn't sealed. A ctrKey alone can't reseal it; import the full 108-byte token.")
                }
                if (expected == null) bind(ids.deviceId)
                confirmAddress()
                when {
                    ids.sealed -> afterSealed()
                    step == Step.IDS -> {
                        setState(State.SEALING, "RTB isn't sealed; sealing it with your token")
                        send(Step.SEAL, RtbProtocol.setEncryptionKey((key as RtbKey.Full).token))
                    }
                    else -> fail("the RTB accepted the token but still reports unsealed")
                }
            }
            RtbProtocol.OP_SET_ENCRYPTION_KEY -> if (step == Step.SEAL) {
                log("seal accepted")
                send(Step.IDS_AFTER_SEAL, RtbProtocol.getSecureIds())
            }
            RtbProtocol.OP_GET_SESSION_NONCE -> if (step == Step.NONCE) {
                if (r.payload.size < 12) return onLinkDown("RTB sent a short session nonce")
                decryptor = AudioDecryptor(key.ctrKey(), r.payload.copyOf(12))
                undecodable = 0
                send(Step.RECORD, RtbProtocol.setDeviceState(RtbProtocol.STATE_RECORDING))
            }
            RtbProtocol.OP_SET_DEVICE_STATE -> when (step) {
                Step.RECORD -> {
                    settle()
                    if (!wanted) muteNow() else beginStream()
                }
                Step.MUTE -> {
                    step = null
                    h.removeCallbacks(replyTimeout)
                    attempts = 0
                    settle()
                    if (wanted) record() else settledMuted()   // asked to record meanwhile
                }
                // Only a sleep test's reply: audio can start a recording before its own ack lands.
                null -> if (stateTests > 0) {
                    stateTests--
                    reply("RTB state set: ${r.payload.toHex()}")
                }
                else -> {}
            }
            RtbProtocol.OP_GET_BATTERY -> {
                log("RTB battery reply ${r.payload.toHex()}")
                BatteryState.parse(r.payload)?.let { listener.onBattery(it.level, it.charging) }
            }
            else -> reply("RTB ok 0x%04x %s".format(r.op, r.payload.toHex()))
        }
    }

    /**
     * Developer probe: write raw [bytes] to the control characteristic. The reply (or
     * event) lands in the log through the normal paths. Refused mid-handshake, so it
     * can't be mistaken for a handshake reply.
     */
    fun debugSend(bytes: ByteArray) = post {
        if (gatt == null || step != null) return@post log("debug: link busy or down; not sending ${bytes.toHex()}")
        log("debug → ${bytes.toHex()}")
        writeControl(bytes)
    }

    /**
     * The RTB can stop and start recording on its own (its button, or its own logic) and
     * says so with a device-state event: `[state][…]`, 0 = muted, 1 = recording.
     * Muting ends our session; resuming starts a new one. The session nonce belongs to the
     * connection: on hardware, audio after a self-mute and resume still decrypts with the
     * nonce fetched at connect, so keep the decryptor. (If frames ever stop decrypting,
     * [onAudio] refetches the nonce.)
     */
    private fun onEvent(e: Reply.Event) {
        log("RTB event 0x%04x %s".format(e.tag, e.payload.toHex()))
        when (e.tag) {
            RtbProtocol.TAG_EVENT_DOUBLE_PRESS -> return button(Button.DOUBLE_PRESS)
            RtbProtocol.TAG_EVENT_HOLD -> {
                val start = e.payload.firstOrNull()?.toInt() == 1
                if (start) holdStartedAt = SystemClock.elapsedRealtime() else holdEndedAt = SystemClock.elapsedRealtime()
                return button(if (start) Button.HOLD_START else Button.HOLD_END)
            }
            RtbProtocol.TAG_EVENT_BATTERY -> return e.payload.firstOrNull()?.let { listener.onBattery(it.toInt() and 0xFF, null) } ?: Unit
            RtbProtocol.TAG_EVENT_CHARGING -> return e.payload.firstOrNull()?.let { listener.onBattery(null, it.toInt() != 0) } ?: Unit
        }
        if (e.tag != RtbProtocol.TAG_EVENT_DEVICE_STATE || e.payload.isEmpty()) return
        reportIdleFrames()
        val state = e.payload[0].toInt()
        val recording = state == RtbProtocol.STATE_RECORDING
        if (blinking) {
            blinkSeen++
            blinkLast = recording
            return
        }
        if (gatt == null || step != null || paused) return     // our own handshake or pause caused it
        deviceState(recording, state)
    }

    /** [state]: the RTB's own number for it; null = not said (a blink's last event). */
    private fun deviceState(recording: Boolean, state: Int? = null) {
        val now = SystemClock.elapsedRealtime()
        if (recording == wanted && recording == streaming) {           // what we asked for...
            // ...except that its button mutes it properly: keep the mic warm instead.
            if (!recording && state != null && state != restState && now >= settleUntil) muteNow()
            return
        }
        if (recording && now - holdStartedAt < STATE_SETTLE_MS) {
            // The RTB unmutes itself when a hold registers. That isn't its single press, so
            // not a new resting state: what the hold means is the Recorder's call, and it
            // asks with setRecording (temporary for a command).
            holdStartedAt = 0
            return
        }
        if (!recording && now - holdEndedAt < STATE_SETTLE_MS) {
            // The RTB mutes itself when its button is let go after a hold. That ends the
            // command; whether to record again is the resting state's call (if it was
            // recording before the hold, the recording carries on).
            holdEndedAt = 0
            if (wantRecording) return record()
            endStream("button let go")
            return rest(state)
        }
        if (now < settleUntil) {
            // Right after we set the state: the RTB settling, not its button. Ours stands.
            log("RTB reported ${if (recording) "recording" else "muted"} right after we set it; setting it again")
            return if (wanted) record() else muteNow()
        }
        borrowed = false                    // its button: keep that choice across reconnects
        choose(recording)
        if (!recording && streaming) {
            endStream("RTB stopped recording")
            setState(State.MUTED, "the RTB stopped recording (its button?); waiting for it to resume")
            if (state != null && state != restState) muteNow()
        } else if (recording && !streaming) {
            if (decryptor != null) beginStream() else send(Step.NONCE, RtbProtocol.getSessionNonce())
        }
    }

    private fun button(b: Button) {
        if (gatt == null || paused) return log("ignoring ${b.name.lowercase()}: ${if (paused) "paused" else "not connected"}")
        listener.onButton(b)
    }

    private fun onFailed(r: Reply.Failed) {
        val what = "${r.kind} for ${r.op?.let { "0x%04x".format(it) } ?: "?"}"
        if (r.op == RtbProtocol.OP_GET_BATTERY) return log("RTB won't say its battery level ($what); waiting for it to report one")
        when (step) {
            Step.SEAL -> fail("the RTB rejected the seal token ($what). Is the token from this RTB?")
            Step.MUTE -> { step = null; settledMuted() }
            null -> reply("RTB: $what")
            else -> onLinkDown("RTB answered $what during ${step?.name}")
        }
    }

    private fun afterSealed() {
        if (!wanted) muteNow() else send(Step.NONCE, RtbProtocol.getSessionNonce())
    }

    /** Whether the RTB should be recording now: its resting state or a command, unless paused. */
    private val wanted get() = (wantRecording || borrowed) && !paused

    /** Bring the RTB in line with [wanted]. Only between steps. */
    private fun reconcile() {
        val want = wanted
        when {
            want && !streaming -> record()
            !want -> muteNow()             // also when muted already: pause changes what muted means
        }
    }

    /** The nonce belongs to the connection, so a mute and unmute keep it. */
    private fun record() {
        if (decryptor != null) send(Step.RECORD, RtbProtocol.setDeviceState(RtbProtocol.STATE_RECORDING))
        else send(Step.NONCE, RtbProtocol.getSessionNonce())
    }

    /** Mute, and end the stream: whatever audio still arrives isn't wanted. */
    private fun muteNow() {
        if (paused) decryptor = null
        endStream(if (paused) "paused" else "muted from the app")
        send(Step.MUTE, RtbProtocol.setDeviceState(restState))
    }

    /**
     * What muted means. Mute proper stops the mic's clock, and the mic clicks loudly when
     * it starts again (docs/RTB_PROTOCOL.md). The software-VAD sleep keeps the mic running
     * and sends nothing (tested in a quiet room), for more battery. Paused uses mute proper.
     */
    private val restState get() = if (warmMute && !paused) RtbProtocol.STATE_SLEEP_SW_VAD else RtbProtocol.STATE_MUTE

    /** The RTB muted itself into [state]: keep it there, or move it to [restState]. */
    private fun rest(state: Int?) = if (state != null && state != restState) muteNow() else settledMuted()

    private fun settle() {
        settleUntil = SystemClock.elapsedRealtime() + STATE_SETTLE_MS
    }

    private fun choose(on: Boolean) {
        if (wantRecording == on) return
        wantRecording = on
        listener.onRecordingChoice(on)
    }

    private fun settledMuted() {
        if (paused) setState(State.PAUSED, "paused; RTB connected and muted")
        else setState(State.MUTED, "RTB muted; its button starts recording")
        askBattery()
    }

    /** Once per connection, after the handshake; after that the RTB reports changes itself. */
    private fun askBattery() {
        if (batteryAsked || gatt == null || control == null) return
        batteryAsked = true
        writeControl(RtbProtocol.getBattery())
    }

    private fun confirmAddress() {
        val g = gatt ?: return
        device = g.device
        if (!verifying && address != null) return
        verifying = false
        address = g.device.address
        val type = if (Build.VERSION.SDK_INT >= 35) g.device.addressType else -1
        listener.onAddressConfirmed(g.device.address, type)
    }

    /** A ctrKey-only key meets its RTB: from now on, only this deviceId is accepted. */
    private fun bind(id: ByteArray) {
        deviceId = id.copyOf()
        log("your ctrKey is now bound to RTB ${id.toHex()}")
        listener.onDeviceBound(id.copyOf())
    }

    private fun wrongDevice(ids: SecureIds) {
        val addr = gatt?.device?.address ?: "?"
        if (verifying) {
            rejected += addr
            log("$addr is a different RTB (device ${ids.deviceId.toHex()}); still looking")
            onLinkDown("not our RTB")
        } else {
            val whose = if (key is RtbKey.Full) "your token is for" else "your ctrKey is bound to"
            fail(
                "the RTB at $addr is device ${ids.deviceId.toHex()}, but $whose " +
                    "device $deviceHex. Forget the device and start again to scan.",
            )
        }
    }

    /** Unrecoverable without the user: stop trying. */
    private fun fail(reason: String) {
        running = false
        cancelTimers()
        teardown(reason)
        setState(State.FAILED, reason)
    }

    // ------------------------------------------------------------------ audio

    private fun beginStream() {
        step = null
        h.removeCallbacks(replyTimeout)
        if (streaming) return
        streaming = true
        attempts = 0
        reportIdleFrames()
        setState(State.STREAMING, "streaming from RTB $deviceHex")
        listener.onStreamStart(deviceHex)
        askBattery()
    }

    /** The sleep states may send audio on their own: say how much came. */
    private fun reportIdleFrames() {
        if (idleFrames == 0) return
        log("$idleFrames audio frames (${idleFrames * RtbProtocol.AUDIO_FRAME_LEN / 1024} KB) arrived while not recording")
        idleFrames = 0
    }

    private fun endStream(reason: String) {
        if (!streaming) return
        streaming = false
        listener.onStreamEnd(reason)
    }

    private fun onAudio(d: ByteArray) {
        val dec = decryptor ?: return
        if (!streaming) {
            // Audio can beat the SetDeviceState ack; it confirms the same thing.
            if (step == Step.RECORD && wanted) {
                settle()
                beginStream()
            } else {
                if (idleFrames++ == 0) log("audio arriving while not recording")
                return
            }
        }
        val f = dec.decrypt(d) ?: return
        if (f.containsAdtsSync) {
            undecodable = 0
            keyProven = true
        } else if (++undecodable == STALE_NONCE_FRAMES && step == null) {
            val now = System.currentTimeMillis()
            if (now - lastNonceRefresh > 60_000) {
                lastNonceRefresh = now
                log("frames stopped decrypting to AAC; fetching a fresh nonce")
                send(Step.NONCE, RtbProtocol.getSessionNonce())
            } else if (!keyProven && key is RtbKey.CtrOnly) {
                // A token's ctrKey belongs to the device it was checked against; a bare
                // ctrKey that has never produced AAC, even with a fresh nonce, is the wrong key.
                return fail(
                    "Your ctrKey doesn't decrypt the audio of RTB $deviceHex. Check the key, or " +
                        "import the full 108-byte token. If this isn't your RTB, Forget it in Settings.",
                )
            } else {
                log("frames still don't decrypt to AAC after a nonce refresh")
            }
        }
        listener.onAudio(f.seq, f.adts)
    }

    // ------------------------------------------------------------------ helpers

    private fun setState(s: State, detail: String) = listener.onState(s, detail)
    private fun log(line: String) = listener.onLog(line)
    private fun reply(line: String) = listener.onReply(line)
    private fun post(f: () -> Unit) { h.post(f) }

    /** A request from the app's tests: only on a ready link, never mid-handshake. */
    private fun direct(what: String, f: () -> Unit) = post {
        if (gatt == null || control == null || step != null) return@post reply("$what not sent: the RTB isn't connected or is busy")
        log("→ $what")
        f()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 35_000L
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val REPLY_TIMEOUT_MS = 8_000L
        private const val STALE_NONCE_FRAMES = 4
        private const val BLINK_MIN_MS = 500L       // shorter flashes and gaps get swallowed by the RTB
        private const val BLINK_SETTLE_MS = 1_500L  // late state events from a blink are still ignored
        private const val STATE_SETTLE_MS = 1_500L  // state events this soon after our own change are echoes
    }
}
