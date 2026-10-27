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
import android.os.Handler
import android.os.HandlerThread
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
 * The device ID in GetSecureIds must match the token's, so a scan can never latch onto
 * somebody else's RTB. Once streaming, a lost link is re-dialled with autoConnect, which
 * lets the Bluetooth stack wait for the RTB to come back in range instead of polling.
 *
 * Everything runs on one private HandlerThread (GATT callbacks included), so there are
 * no locks. Listener callbacks arrive on that thread.
 */
@SuppressLint("MissingPermission") // the service checks BLUETOOTH_SCAN/CONNECT before start()
class RtbLink(
    private val ctx: Context,
    private val token: SealToken,
    knownAddress: String?,
    private val listener: Listener,
) {
    enum class State { STOPPED, SCANNING, CONNECTING, HANDSHAKE, SEALING, STREAMING, PAUSED, WAITING, FAILED }

    interface Listener {
        fun onState(state: State, detail: String)
        fun onLog(line: String)
        /** A scanned device proved to be the token's RTB: remember it for next time. */
        fun onAddressConfirmed(address: String)
        fun onStreamStart(deviceIdHex: String)
        fun onAudio(seq: Long, adts: ByteArray)
        fun onStreamEnd(reason: String)
    }

    private enum class Step { IDS, SEAL, IDS_AFTER_SEAL, NONCE, RECORD, MUTE }

    private val thread = HandlerThread("rtb-link").apply { start() }
    private val h = Handler(thread.looper)
    private val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter

    private var address = knownAddress
    private var running = false
    private var paused = false
    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var scanCb: ScanCallback? = null
    private var verifying = false                    // connected to a scan hit, not yet proven ours
    private val rejected = mutableSetOf<String>()    // scan hits that turned out to be other RTBs
    private var attempts = 0                         // consecutive failures; 0 = next dial is direct
    private var step: Step? = null
    private var decryptor: AudioDecryptor? = null
    private var streaming = false
    private var undecodable = 0

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

    /** Paused = stay connected and sealed, but keep the RTB muted. Survives reconnects. */
    fun setPaused(p: Boolean) = post {
        if (paused == p) return@post
        paused = p
        if (gatt == null || control == null || step != null) return@post   // applied by the handshake
        if (p) {
            endStream("paused")
            decryptor = null
            send(Step.MUTE, RtbProtocol.setDeviceState(RtbProtocol.STATE_MUTE))
        } else {
            send(Step.NONCE, RtbProtocol.getSessionNonce())
        }
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
        val addr = address
        if (addr == null) scan() else connect(addr, verify = false)
    }

    private fun connect(addr: String, verify: Boolean) {
        verifying = verify
        // First dial is direct (fast, times out). After a failure, dial with autoConnect:
        // the stack then waits, at low duty cycle, for the RTB to show up again.
        val auto = attempts > 0 && !verify
        setState(
            State.CONNECTING,
            if (auto) "waiting for RTB $addr to come back in range" else "connecting to $addr",
        )
        gatt = remoteDevice(addr).connectGatt(
            ctx, auto, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, h,
        )
        if (!auto) h.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun remoteDevice(addr: String): BluetoothDevice {
        val first = addr.substring(0, 2).toInt(16)
        // Top two bits 11 = static random address. Naming the type lets the stack connect
        // after a reboot, when it no longer remembers the device from a scan.
        return if (first and 0xC0 == 0xC0) adapter.getRemoteLeDevice(addr, BluetoothDevice.ADDRESS_TYPE_RANDOM)
        else adapter.getRemoteDevice(addr)
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
        connect(addr, verify = true)
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
            setState(State.PAUSED, "paused")
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
            is Reply.Event -> log("RTB event 0x%04x %s".format(r.tag, r.payload.toHex()))
            is Reply.Unknown -> log("unrecognised control message ${r.raw.toHex()}")
        }
    }

    private fun onOk(r: Reply.Ok) {
        when (r.op) {
            RtbProtocol.OP_GET_SECURE_IDS -> {
                if (step != Step.IDS && step != Step.IDS_AFTER_SEAL) return
                val ids = SecureIds.parse(r.payload) ?: return onLinkDown("RTB sent a short SecureIds reply")
                if (!ids.deviceId.contentEquals(token.deviceId)) return wrongDevice(ids)
                confirmAddress()
                when {
                    ids.sealed -> afterSealed()
                    step == Step.IDS -> {
                        setState(State.SEALING, "RTB isn't sealed; sealing it with your token")
                        send(Step.SEAL, RtbProtocol.setEncryptionKey(token))
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
                decryptor = AudioDecryptor(token.ctrKey(), r.payload.copyOf(12))
                undecodable = 0
                send(Step.RECORD, RtbProtocol.setDeviceState(RtbProtocol.STATE_RECORDING))
            }
            RtbProtocol.OP_SET_DEVICE_STATE -> when (step) {
                Step.RECORD -> if (paused) muteNow() else beginStream()
                Step.MUTE -> {
                    step = null
                    h.removeCallbacks(replyTimeout)
                    attempts = 0
                    setState(State.PAUSED, "paused; RTB connected and muted")
                }
                else -> {}
            }
            else -> log("RTB ok 0x%04x".format(r.op))
        }
    }

    private fun onFailed(r: Reply.Failed) {
        val what = "${r.kind} for ${r.op?.let { "0x%04x".format(it) } ?: "?"}"
        when (step) {
            Step.SEAL -> fail("the RTB rejected the seal token ($what). Is the token from this RTB?")
            Step.MUTE -> { step = null; setState(State.PAUSED, "paused") }
            null -> log("RTB: $what")
            else -> onLinkDown("RTB answered $what during ${step?.name}")
        }
    }

    private fun afterSealed() {
        if (paused) muteNow() else send(Step.NONCE, RtbProtocol.getSessionNonce())
    }

    private fun muteNow() {
        decryptor = null
        send(Step.MUTE, RtbProtocol.setDeviceState(RtbProtocol.STATE_MUTE))
    }

    private fun confirmAddress() {
        val g = gatt ?: return
        if (!verifying && address != null) return
        verifying = false
        address = g.device.address
        listener.onAddressConfirmed(g.device.address)
    }

    private fun wrongDevice(ids: SecureIds) {
        val addr = gatt?.device?.address ?: "?"
        if (verifying) {
            rejected += addr
            log("$addr is a different RTB (device ${ids.deviceId.toHex()}); still looking")
            onLinkDown("not our RTB")
        } else {
            fail(
                "the RTB at $addr is device ${ids.deviceId.toHex()}, but your token is for " +
                    "device ${token.deviceIdHex}. Forget the device and start again to scan.",
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
        setState(State.STREAMING, "streaming from RTB ${token.deviceIdHex}")
        listener.onStreamStart(token.deviceIdHex)
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
            if (step == Step.RECORD && !paused) beginStream() else return
        }
        val f = dec.decrypt(d) ?: return
        if (!f.looksLikeAdts && undecodable++ % 50 == 0) {
            log("frame ${f.seq} didn't decrypt to AAC (wrong key or stale nonce?)")
        }
        listener.onAudio(f.seq, f.adts)
    }

    // ------------------------------------------------------------------ helpers

    private fun setState(s: State, detail: String) = listener.onState(s, detail)
    private fun log(line: String) = listener.onLog(line)
    private fun post(f: () -> Unit) { h.post(f) }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 35_000L
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val REPLY_TIMEOUT_MS = 8_000L
    }
}
