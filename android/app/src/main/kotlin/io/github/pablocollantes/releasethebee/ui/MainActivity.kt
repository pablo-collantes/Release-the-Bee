package io.github.pablocollantes.releasethebee.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.pablocollantes.releasethebee.rtb.RtbLink
import io.github.pablocollantes.releasethebee.rtb.SealToken
import io.github.pablocollantes.releasethebee.service.AppStatus
import io.github.pablocollantes.releasethebee.service.RtbService
import io.github.pablocollantes.releasethebee.store.SecretStore
import io.github.pablocollantes.releasethebee.store.Settings

/**
 * Status and setup. All the real work happens in [RtbService]; this screen only edits
 * settings and sends it start/pause/stop/reload.
 */
class MainActivity : Activity() {

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

    private lateinit var tokenView: TextView
    private lateinit var tokenInput: EditText
    private lateinit var tokenImport: LinearLayout
    private lateinit var forgetTokenBtn: Button

    private lateinit var deviceView: TextView
    private lateinit var forgetDeviceBtn: Button

    private lateinit var sinkGroup: RadioGroup
    private lateinit var wsBox: LinearLayout
    private lateinit var tcpBox: LinearLayout
    private lateinit var wsUrl: EditText
    private lateinit var wsToken: EditText
    private lateinit var tcpHost: EditText
    private lateinit var tcpPort: EditText
    private lateinit var recordBox: CheckBox

    private lateinit var logView: TextView

    private val onStatus: () -> Unit = { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
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

    // ------------------------------------------------------------------ actions

    private fun start() {
        if (!RtbService.hasPermissions(this)) {
            requestPermissions(RtbService.PERMISSIONS, REQ_PERMS)
            return
        }
        if (secrets.sealToken == null) return toast("Import your RTB's seal token first")
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

    private fun saveToken(t: SealToken) {
        val previous = secrets.sealToken
        secrets.sealToken = t
        tokenInput.setText("")
        if (previous != null && previous.deviceIdHex != t.deviceIdHex) settings.deviceAddress = null
        AppStatus.log("seal token saved for RTB ${t.deviceIdHex}")
        renderSetup()
        reloadIfRunning()
    }

    private fun importTokenText() {
        val raw = tokenInput.text.toString()
        if (raw.isBlank()) return toast("Paste the token first")
        runCatching { SealToken.parse(raw) }
            .onSuccess { saveToken(it) }
            .onFailure { toast("Not a seal token: ${it.message}") }
    }

    private fun pickTokenFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_TOKEN_FILE)
    }

    @Deprecated("Activity result API; fine for a framework-only Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TOKEN_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openInputStream(uri)!!.use { it.readNBytes(64 * 1024) }
        }.mapCatching { SealToken.fromFile(it) }
            .onSuccess { saveToken(it) }
            .onFailure { toast("Couldn't read a seal token from that file: ${it.message}") }
    }

    private fun forgetToken() {
        secrets.sealToken = null
        stop()
        AppStatus.log("seal token removed from this phone")
        renderSetup()
    }

    private fun forgetDevice() {
        settings.deviceAddress = null
        AppStatus.log("forgot the RTB's address; next start will scan for it")
        renderSetup()
        reloadIfRunning()
    }

    private fun saveDestination() {
        val kind = when (sinkGroup.checkedRadioButtonId) {
            ID_TCP -> Settings.SinkKind.TCP
            ID_NONE -> Settings.SinkKind.NONE
            else -> Settings.SinkKind.WEBSOCKET
        }
        val url = wsUrl.text.toString().trim()
        if (kind == Settings.SinkKind.WEBSOCKET && !url.startsWith("ws://") && !url.startsWith("wss://")) {
            return toast("The URL must start with ws:// or wss://")
        }
        val port = tcpPort.text.toString().trim().toIntOrNull()
        if (kind == Settings.SinkKind.TCP && (tcpHost.text.isBlank() || port == null || port !in 1..65535)) {
            return toast("Enter a host and a port (1-65535)")
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
            RtbLink.State.STREAMING -> "● Streaming"
            RtbLink.State.PAUSED -> "Paused"
            RtbLink.State.SCANNING -> "Looking for your RTB"
            RtbLink.State.CONNECTING -> "Connecting"
            RtbLink.State.HANDSHAKE -> "Setting up"
            RtbLink.State.SEALING -> "Sealing"
            RtbLink.State.WAITING -> "Waiting"
            RtbLink.State.FAILED -> "Needs attention"
            RtbLink.State.STOPPED -> if (st.serviceRunning) "Starting" else "Stopped"
        }
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
        pauseBtn.isEnabled = st.serviceRunning
        pauseBtn.text = if (settings.paused) "Resume" else "Pause"
        stopBtn.isEnabled = st.serviceRunning
        logView.text = AppStatus.logs().takeLast(80).joinToString("\n")
    }

    private fun renderSetup() {
        val perms = RtbService.hasPermissions(this)
        permView.text = if (perms) "Bluetooth: allowed ✓" else "Bluetooth: permission needed"
        permBtn.visibility = if (perms) View.GONE else View.VISIBLE

        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryView.text = if (unrestricted) "Battery: unrestricted ✓"
        else "Battery: optimised. Android may cut the connection in the background."
        batteryBtn.visibility = if (unrestricted) View.GONE else View.VISIBLE

        val token = secrets.sealToken
        tokenView.text = if (token != null) "Token for RTB ${token.deviceIdHex} ✓ (encrypted on this phone)"
        else "No token yet. Paste the 108-byte token (216 hex characters) or import the file " +
            "tools/parse_bee_token.py produced."
        tokenImport.visibility = if (token == null) View.VISIBLE else View.GONE
        tokenInput.visibility = tokenImport.visibility
        forgetTokenBtn.visibility = if (token != null) View.VISIBLE else View.GONE

        val addr = settings.deviceAddress
        deviceView.text = addr?.let { "Paired RTB: $it" } ?: "No RTB yet. Start scans for the one your token belongs to."
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(text(24f, bold = true).apply { text = "Release the Bee" })
        root.addView(text(14f).apply {
            text = "Your RTB → this phone → your own machine."
            alpha = 0.7f
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x33F5C518)
            }
        }
        stateView = text(20f, bold = true)
        detailView = text(14f)
        sinkView = text(13f).apply { alpha = 0.8f }
        card.addView(stateView)
        card.addView(detailView)
        card.addView(sinkView)
        root.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        startBtn = button("Start") { start() }
        pauseBtn = button("Pause") { togglePause() }
        stopBtn = button("Stop") { stop() }
        root.addView(row(startBtn, pauseBtn, stopBtn))

        root.addView(header("Setup"))
        permView = text(14f)
        permBtn = button("Allow") { requestPermissions(RtbService.PERMISSIONS, REQ_PERMS) }
        root.addView(labelled(permView, permBtn))
        batteryView = text(14f)
        batteryBtn = button("Unrestrict") {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
        root.addView(labelled(batteryView, batteryBtn))

        root.addView(header("Seal token"))
        tokenView = text(14f)
        root.addView(tokenView)
        tokenInput = EditText(this).apply {
            hint = "534b5631…"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            // Keep the token out of keyboard learning and autofill.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            maxLines = 4
            setHorizontallyScrolling(false)
        }
        root.addView(tokenInput)
        tokenImport = row(button("Save pasted token") { importTokenText() }, button("Import file…") { pickTokenFile() })
        root.addView(tokenImport)
        forgetTokenBtn = button("Remove token from this phone") { forgetToken() }
        root.addView(forgetTokenBtn)

        root.addView(header("RTB"))
        deviceView = text(14f)
        forgetDeviceBtn = button("Forget") { forgetDevice() }
        root.addView(labelled(deviceView, forgetDeviceBtn))

        root.addView(header("Send audio to"))
        sinkGroup = RadioGroup(this).apply {
            addView(radio(ID_WS, "WebSocket: ws:// or wss:// URL"))
            addView(radio(ID_TCP, "Raw TCP: plain AAC stream (netcat works)"))
            addView(radio(ID_NONE, "Nowhere (phone recording only)"))
            setOnCheckedChangeListener { _, _ -> showDestinationFields() }
        }
        root.addView(sinkGroup)
        wsUrl = field("ws://100.101.102.103:8765/").apply { inputType = InputType.TYPE_TEXT_VARIATION_URI }
        wsToken = field("Bearer token (optional)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        wsBox = column(wsUrl, wsToken)
        root.addView(wsBox)
        tcpHost = field("Host or IP")
        tcpPort = field("Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        tcpBox = row(tcpHost, tcpPort)
        root.addView(tcpBox)
        root.addView(text(12f).apply {
            alpha = 0.7f
            text = "Any address this phone can reach works: a Tailscale, WireGuard or OpenVPN IP, " +
                "a LAN IP, or a public wss:// endpoint such as a Cloudflare Tunnel. " +
                "Use wss:// whenever the path crosses the open internet."
        })
        recordBox = CheckBox(this).apply { text = "Also keep a copy on this phone" }
        root.addView(recordBox)
        root.addView(button("Save destination") { saveDestination() })

        root.addView(header("Log"))
        logView = text(11f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(logView)

        val pad = dp(16)
        return ScrollView(this).apply {
            addView(root)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun header(title: String) = text(13f, bold = true).apply {
        text = title.uppercase()
        alpha = 0.6f
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        isSingleLine = true
        textSize = 14f
    }

    private fun radio(id: Int, label: String) = RadioButton(this).apply {
        this.id = id
        text = label
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
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
        const val REQ_TOKEN_FILE = 2
        const val ID_WS = 101
        const val ID_TCP = 102
        const val ID_NONE = 103
    }
}
