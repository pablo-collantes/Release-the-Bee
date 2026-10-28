package io.github.pablocollantes.releasethebee.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.pablocollantes.releasethebee.rtb.RtbKey
import io.github.pablocollantes.releasethebee.rtb.RtbLink
import io.github.pablocollantes.releasethebee.service.AppStatus
import io.github.pablocollantes.releasethebee.service.RtbService
import io.github.pablocollantes.releasethebee.store.SecretStore
import io.github.pablocollantes.releasethebee.store.Settings

/**
 * The connection and everything behind it: the service (start, pause, stop), permissions
 * and battery, the RTB key, the paired RTB, where audio goes, and the log. The real work
 * happens in [RtbService]; this screen edits settings and sends it start/pause/stop/reload.
 */
class SettingsActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var settings: Settings
    private lateinit var secrets: SecretStore

    private lateinit var stateView: TextView
    private lateinit var detailView: TextView
    private lateinit var sinkView: TextView
    private lateinit var startBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var stopBtn: Button

    private lateinit var permView: TextView
    private lateinit var permBtn: Button
    private lateinit var batteryView: TextView
    private lateinit var batteryBtn: Button

    private lateinit var keyKind: TextView
    private lateinit var keyDevice: TextView
    private lateinit var keyNote: TextView
    private lateinit var keyImport: LinearLayout
    private lateinit var keyInput: EditText
    private lateinit var replaceKeyLink: TextView
    private lateinit var removeKeyLink: TextView
    private var importOpen = false

    private lateinit var deviceView: TextView
    private lateinit var forgetDeviceBtn: TextView

    private lateinit var sinkGroup: RadioGroup
    private lateinit var wsBox: LinearLayout
    private lateinit var tcpBox: LinearLayout
    private lateinit var wsUrl: EditText
    private lateinit var wsToken: EditText
    private lateinit var tcpHost: EditText
    private lateinit var tcpPort: EditText
    private lateinit var recordBox: CheckBox

    private lateinit var logView: TextView

    private val onStatus: () -> Unit = {
        renderStatus()
        renderDevice()      // the address (and a ctrKey's RTB) is learned while the screen is open
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        ui = Ui(this)
        settings = Settings(this)
        secrets = SecretStore(this)
        setContentView(buildUi())
        loadDestination()
    }

    override fun onResume() {
        super.onResume()
        AppStatus.addListener(onStatus)
        renderSetup()
        renderStatus()
    }

    override fun onPause() {
        AppStatus.removeListener(onStatus)
        super.onPause()
    }

    // ------------------------------------------------------------------ service

    private fun start() {
        if (!RtbService.hasPermissions(this)) {
            requestPermissions(RtbService.PERMISSIONS, REQ_PERMS)
            return
        }
        if (secrets.rtbKey == null) return toast("Import your RTB's key first")
        if (destinationEdited() && !saveDestination()) return   // typed a URL but didn't press Save
        settings.serviceEnabled = true
        settings.paused = false
        RtbService.send(this, RtbService.ACTION_START)
    }

    private fun togglePause() {
        val pause = !settings.paused
        RtbService.send(this, if (pause) RtbService.ACTION_PAUSE else RtbService.ACTION_RESUME)
        settings.paused = pause
        renderStatus()
    }

    private fun stop() {
        settings.serviceEnabled = false
        if (AppStatus.snapshot.serviceRunning) RtbService.send(this, RtbService.ACTION_STOP)
    }

    /** Apply changed settings to a running service. */
    private fun reloadIfRunning() {
        if (AppStatus.snapshot.serviceRunning) RtbService.send(this, RtbService.ACTION_RELOAD)
    }

    // ------------------------------------------------------------------ key

    private fun importKey(parsed: RtbKey) {
        val previous = secrets.rtbKey
        var k = parsed
        // The bare ctrKey of the token already stored: it belongs to the token's RTB.
        val prevId = previous?.deviceId
        if (k is RtbKey.CtrOnly && previous != null && prevId != null && previous.ctrKey().contentEquals(k.ctrKey())) {
            k = k.boundTo(prevId)
        }
        if (previous is RtbKey.Full && k is RtbKey.CtrOnly) {
            AlertDialog.Builder(this)
                .setTitle("Replace the full token with a ctrKey?")
                .setMessage(
                    "A ctrKey decrypts audio but can't seal the RTB. Without the full token, an RTB " +
                        "that has been reset can't be used again. Keep a backup of the token before you replace it.",
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ -> saveKey(k, previous) }
                .show()
                .also { styleDestructive(it) }
        } else {
            saveKey(k, previous)
        }
    }

    private fun saveKey(k: RtbKey, previous: RtbKey?) {
        secrets.rtbKey = k
        keyInput.setText("")
        importOpen = false
        // A key for another RTB: forget the address, so the next start scans for the right one.
        val prevId = previous?.deviceIdHex
        if (prevId != null && k.deviceIdHex != null && prevId != k.deviceIdHex) forgetAddress()
        AppStatus.log(
            when (k) {
                is RtbKey.Full -> "seal token saved for RTB ${k.deviceIdHex}"
                is RtbKey.CtrOnly -> k.deviceIdHex?.let { "ctrKey saved for RTB $it" }
                    ?: "ctrKey saved; it binds to the first RTB it connects to"
            },
        )
        renderSetup()
        reloadIfRunning()
    }

    private fun importKeyText() {
        val raw = keyInput.text.toString()
        if (raw.isBlank()) return toast("Paste the token or ctrKey first")
        runCatching { RtbKey.parse(raw) }
            .onSuccess { importKey(it) }
            .onFailure { toast("Not a key: ${it.message}") }
    }

    private fun pickKeyFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_KEY_FILE)
    }

    @Deprecated("Activity result API; fine for a framework-only Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_KEY_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openInputStream(uri)!!.use { it.readNBytes(64 * 1024) }
        }.mapCatching { RtbKey.fromFile(it) }
            .onSuccess { importKey(it) }
            .onFailure { toast("Couldn't read a key from that file: ${it.message}") }
    }

    /** Removing the key is deliberate: a quiet link, then a dialog that defaults to Cancel. */
    private fun confirmRemoveKey() {
        val k = secrets.rtbKey ?: return
        val what = if (k is RtbKey.Full) "the full seal token" else "the ctrKey"
        val rtb = k.deviceIdHex?.let { " for RTB $it" } ?: ""
        val consequence = if (k is RtbKey.Full) {
            "Without the full token you can't reseal the RTB after a reset, and only a capture of the " +
                "stock app's pairing gives you a new one. Keep a backup of the token file before you remove it."
        } else {
            "Without a key the RTB's audio can't be decrypted. Keep a backup before you remove it."
        }
        AlertDialog.Builder(this)
            .setTitle("Remove the key from this phone?")
            .setMessage("This deletes $what$rtb from this phone and stops the connection.\n\n$consequence")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove key") { _, _ -> removeKey() }
            .show()
            .also { styleDestructive(it) }
    }

    private fun styleDestructive(d: AlertDialog) {
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ui.red)
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun removeKey() {
        secrets.rtbKey = null
        stop()
        AppStatus.log("RTB key removed from this phone")
        renderSetup()
    }

    // ------------------------------------------------------------------ device

    private fun forgetAddress() {
        settings.deviceAddress = null
        settings.deviceAddressType = -1
    }

    /** Forget the paired RTB; a ctrKey-only key also forgets which RTB it bound to. */
    private fun forgetDevice() {
        forgetAddress()
        val k = secrets.rtbKey
        if (k is RtbKey.CtrOnly && k.deviceId != null) secrets.rtbKey = k.unbound()
        AppStatus.log("forgot the RTB; the next start scans for it")
        renderSetup()
        reloadIfRunning()
    }

    // ------------------------------------------------------------------ destination

    private fun checkedKind() = when (sinkGroup.checkedRadioButtonId) {
        ID_TCP -> Settings.SinkKind.TCP
        ID_NONE -> Settings.SinkKind.NONE
        else -> Settings.SinkKind.WEBSOCKET
    }

    private fun destinationEdited(): Boolean =
        checkedKind() != settings.sinkKind ||
            wsUrl.text.toString().trim() != settings.wsUrl ||
            wsToken.text.toString().trim() != (secrets.sinkToken ?: "") ||
            tcpHost.text.toString().trim() != settings.tcpHost ||
            tcpPort.text.toString().trim() != settings.tcpPort.toString() ||
            recordBox.isChecked != settings.recordOnPhone

    /** Validate and store the destination fields. False (with a toast) if they're invalid. */
    private fun saveDestination(): Boolean {
        val kind = checkedKind()
        val url = wsUrl.text.toString().trim()
        if (kind == Settings.SinkKind.WEBSOCKET && !url.startsWith("ws://") && !url.startsWith("wss://")) {
            toast("The URL must start with ws:// or wss://")
            return false
        }
        val port = tcpPort.text.toString().trim().toIntOrNull()
        if (kind == Settings.SinkKind.TCP && (tcpHost.text.isBlank() || port == null || port !in 1..65535)) {
            toast("Enter a host and a port (1-65535)")
            return false
        }
        settings.sinkKind = kind
        settings.wsUrl = url
        secrets.sinkToken = wsToken.text.toString()
        settings.tcpHost = tcpHost.text.toString()
        if (port != null) settings.tcpPort = port
        settings.recordOnPhone = recordBox.isChecked
        AppStatus.log("destination saved: ${describeDestination()}")
        reloadIfRunning()
        toast("Saved")
        return true
    }

    private fun describeDestination(): String = buildString {
        append(
            when (settings.sinkKind) {
                Settings.SinkKind.WEBSOCKET -> settings.wsUrl.ifBlank { "(no URL)" }
                Settings.SinkKind.TCP -> "tcp://${settings.tcpHost}:${settings.tcpPort}"
                Settings.SinkKind.NONE -> "nowhere"
            },
        )
        if (settings.recordOnPhone) append(" + this phone")
    }

    // ------------------------------------------------------------------ render

    private fun renderStatus() {
        val st = AppStatus.snapshot
        stateView.text = when (st.link) {
            RtbLink.State.STREAMING -> "Recording"
            RtbLink.State.PAUSED -> "Paused: RTB connected and muted"
            RtbLink.State.MUTED -> "RTB muted"
            RtbLink.State.SCANNING -> "Looking for your RTB"
            RtbLink.State.CONNECTING -> "Connecting"
            RtbLink.State.HANDSHAKE -> "Setting up"
            RtbLink.State.SEALING -> "Sealing"
            RtbLink.State.WAITING -> "Waiting"
            RtbLink.State.FAILED -> "Needs attention"
            RtbLink.State.STOPPED -> if (st.serviceRunning) "Starting" else "Stopped"
        }
        stateView.setTextColor(if (st.link == RtbLink.State.FAILED) ui.red else ui.fg)
        detailView.text = buildString {
            append(st.linkDetail.ifBlank { "Press Start to connect to your RTB." })
            st.sessionStartedAt?.let {
                val secs = (System.currentTimeMillis() - it) / 1000
                append("\nSession %d:%02d · %,d frames".format(secs / 60, secs % 60, st.frames))
            }
        }
        sinkView.text = if (st.sinks.isEmpty()) "Destination: ${describeDestination()}"
        else st.sinks.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        startBtn.isEnabled = !st.serviceRunning
        startBtn.alpha = if (startBtn.isEnabled) 1f else 0.5f
        pauseBtn.isEnabled = st.serviceRunning
        pauseBtn.text = if (settings.paused) "Resume" else "Pause"
        stopBtn.isEnabled = st.serviceRunning
        logView.text = AppStatus.logs().takeLast(80).joinToString("\n")
    }

    private fun renderSetup() {
        val perms = RtbService.hasPermissions(this)
        permView.text = if (perms) "Bluetooth allowed" else "Bluetooth permission needed"
        permBtn.visibility = if (perms) View.GONE else View.VISIBLE

        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryView.text = if (unrestricted) "Battery unrestricted"
        else "Battery optimised: Android may cut the connection in the background"
        batteryBtn.visibility = if (unrestricted) View.GONE else View.VISIBLE

        val k = secrets.rtbKey
        when (k) {
            null -> {
                keyKind.text = "No key yet"
                keyDevice.visibility = View.GONE
                keyNote.text = "Paste the 108-byte seal token (216 hex characters) or just the 16-byte ctrKey " +
                    "(32 hex characters), or import the file tools/parse_bee_token.py wrote."
            }
            is RtbKey.Full -> {
                keyKind.text = "Full seal token"
                keyDevice.visibility = View.VISIBLE
                keyDevice.text = "RTB ${k.deviceIdHex}"
                keyNote.text = "Encrypted on this phone. Decrypts audio and reseals the RTB after a reset."
            }
            is RtbKey.CtrOnly -> {
                keyKind.text = "ctrKey only"
                keyDevice.visibility = View.VISIBLE
                keyDevice.text = k.deviceIdHex?.let { "RTB $it" } ?: "Not tied to an RTB yet"
                keyDevice.typeface = if (k.deviceIdHex != null) Typeface.MONOSPACE else ui.regular
                keyNote.text = (if (k.deviceIdHex == null) "It binds to the first RTB it connects to. " else "") +
                    "Encrypted on this phone. Decrypts audio but can't seal the RTB: import the full token to be able to reseal it after a reset."
            }
        }
        val showImport = k == null || importOpen
        keyImport.visibility = if (showImport) View.VISIBLE else View.GONE
        replaceKeyLink.visibility = if (k != null && !importOpen) View.VISIBLE else View.GONE
        removeKeyLink.visibility = if (k != null) View.VISIBLE else View.GONE

        renderDevice()
    }

    private fun renderDevice() {
        val addr = settings.deviceAddress
        deviceView.text = addr?.let { "Paired with the RTB at $it" }
            ?: "No RTB yet. Start scans for the one your key belongs to."
        forgetDeviceBtn.visibility = if (addr != null) View.VISIBLE else View.GONE
    }

    private fun loadDestination() {
        sinkGroup.check(
            when (settings.sinkKind) {
                Settings.SinkKind.WEBSOCKET -> ID_WS
                Settings.SinkKind.TCP -> ID_TCP
                Settings.SinkKind.NONE -> ID_NONE
            },
        )
        wsUrl.setText(settings.wsUrl)
        wsToken.setText(secrets.sinkToken ?: "")
        tcpHost.setText(settings.tcpHost)
        tcpPort.setText(settings.tcpPort.toString())
        recordBox.isChecked = settings.recordOnPhone
        showDestinationFields()
    }

    private fun showDestinationFields() {
        wsBox.visibility = if (sinkGroup.checkedRadioButtonId == ID_WS) View.VISIBLE else View.GONE
        tcpBox.visibility = if (sinkGroup.checkedRadioButtonId == ID_TCP) View.VISIBLE else View.GONE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(24))
        }

        // Connection
        root.addView(ui.sectionHeader("Connection").apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
        val card = ui.card(ui.accentSoft)
        stateView = ui.text(18f, bold = true)
        detailView = ui.text(14f).apply { setPadding(0, ui.dp(4), 0, 0) }
        sinkView = ui.text(13f, ui.fgMuted).apply { setPadding(0, ui.dp(6), 0, 0) }
        card.addView(stateView)
        card.addView(detailView)
        card.addView(sinkView)
        root.addView(card, full())
        startBtn = ui.primaryButton("Start") { start() }
        pauseBtn = ui.button("Pause") { togglePause() }
        stopBtn = ui.button("Stop") { stop() }
        root.addView(weighted(startBtn, pauseBtn, stopBtn), full(top = 8))
        root.addView(ui.text(13f, ui.fgMuted, value = "The RTB's own button mutes and unmutes it. Pause mutes it from here instead; Stop disconnects.").apply {
            setPadding(0, ui.dp(4), 0, 0)
        })

        // Permissions
        root.addView(ui.sectionHeader("This phone"))
        permView = ui.text(15f)
        permBtn = ui.button("Allow") { requestPermissions(RtbService.PERMISSIONS, REQ_PERMS) }
        root.addView(labelled(permView, permBtn), full())
        batteryView = ui.text(15f)
        batteryBtn = ui.button("Unrestrict") {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
        root.addView(labelled(batteryView, batteryBtn), full(top = 4))

        // Key
        root.addView(ui.sectionHeader("RTB key"))
        keyKind = ui.text(16f, bold = true)
        keyDevice = ui.text(14f, ui.fgMuted).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, ui.dp(2), 0, 0)
        }
        keyNote = ui.text(13f, ui.fgMuted).apply {
            setPadding(0, ui.dp(6), 0, 0)
            setLineSpacing(ui.dpf(1f), 1f)
        }
        val keyCard = ui.card().apply {
            addView(keyKind)
            addView(keyDevice)
            addView(keyNote)
        }
        root.addView(keyCard, full())
        keyInput = EditText(this).apply {
            hint = "Seal token or ctrKey: hex or base64"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            // Keep the key out of keyboard learning and autofill.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            maxLines = 4
            setHorizontallyScrolling(false)
        }
        keyImport = ui.column(
            keyInput,
            weighted(ui.button("Save pasted key") { importKeyText() }, ui.button("Import file…") { pickKeyFile() }),
        )
        root.addView(keyImport, full(top = 8))
        replaceKeyLink = ui.link("Replace with another key…") {
            importOpen = true
            renderSetup()
        }
        root.addView(replaceKeyLink, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(4) })
        // Deliberately quiet, on its own line, away from the other actions. It asks first.
        removeKeyLink = ui.link("Remove key from this phone…", color = ui.fgMuted, size = 13f) { confirmRemoveKey() }.apply {
            typeface = ui.regular
        }
        root.addView(removeKeyLink, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(12) })

        // Device
        root.addView(ui.sectionHeader("RTB"))
        deviceView = ui.text(15f)
        forgetDeviceBtn = ui.link("Forget") { forgetDevice() }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(deviceView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(forgetDeviceBtn)
        }, full())

        // Destination
        root.addView(ui.sectionHeader("Send audio to"))
        sinkGroup = RadioGroup(this).apply {
            addView(radio(ID_WS, "WebSocket receiver (ws:// or wss://)"))
            addView(radio(ID_TCP, "Raw TCP: plain AAC stream (netcat works)"))
            addView(radio(ID_NONE, "Nowhere: only the copy on this phone, if kept"))
            setOnCheckedChangeListener { _, _ -> showDestinationFields() }
        }
        root.addView(sinkGroup)
        wsUrl = field("ws://100.101.102.103:8740/").apply { inputType = InputType.TYPE_TEXT_VARIATION_URI }
        wsToken = field("Bearer token (optional)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        wsBox = ui.column(wsUrl, wsToken)
        root.addView(wsBox, full(top = 4))
        tcpHost = field("Host or IP")
        tcpPort = field("Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        tcpBox = weighted(tcpHost, tcpPort)
        root.addView(tcpBox, full(top = 4))
        root.addView(ui.text(13f, ui.fgMuted).apply {
            text = "Any address this phone can reach works: a Tailscale, WireGuard or OpenVPN IP, a LAN IP, " +
                "or a public wss:// endpoint such as a Cloudflare Tunnel. Use wss:// whenever the path crosses " +
                "the open internet. Recordings, transcripts and playback come from the same receiver."
            setLineSpacing(ui.dpf(1f), 1f)
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        })
        recordBox = CheckBox(this).apply {
            text = "Also keep a copy on this phone"
            textSize = 15f
            buttonTintList = ColorStateList.valueOf(ui.accent)
        }
        root.addView(recordBox)
        root.addView(ui.text(13f, ui.fgMuted, value = "Not needed to listen back: the player streams each recording from your receiver.").apply {
            setPadding(ui.dp(32), 0, 0, 0)
        })
        root.addView(ui.primaryButton("Save destination") { saveDestination() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = ui.dp(12)
        })

        // Diagnostics
        root.addView(ui.sectionHeader("Diagnostics"))
        logView = ui.text(11f, ui.fgMuted).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(ui.card().apply { addView(logView) }, full())

        val (bar, _) = ui.topBar("Settings", { finish() })
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(CappedWidth(this@SettingsActivity, ui.dp(720)).apply {
                addView(root, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            })
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        Ui.edgeToEdge(this, page) { v, top, bottom, left, right ->
            v.setPadding(left, top, right, 0)
            scroll.setPadding(0, 0, 0, bottom)
        }
        return page
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(top) }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        isSingleLine = true
        textSize = 15f
    }

    private fun radio(id: Int, label: String) = RadioButton(this).apply {
        this.id = id
        text = label
        textSize = 15f
        buttonTintList = ColorStateList.valueOf(ui.accent)
    }

    /** Views sharing the width equally. */
    private fun weighted(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = ui.dp(8) })
        }
    }

    /** Text that takes the width, with a button at the end. */
    private fun labelled(label: TextView, action: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(action, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_KEY_FILE = 2
        const val ID_WS = 101
        const val ID_TCP = 102
        const val ID_NONE = 103
    }
}
