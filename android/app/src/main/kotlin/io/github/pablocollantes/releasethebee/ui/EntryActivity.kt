package io.github.pablocollantes.releasethebee.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.pablocollantes.releasethebee.R
import io.github.pablocollantes.releasethebee.api.Entry
import io.github.pablocollantes.releasethebee.api.ReceiverApi
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.service.AppStatus

/**
 * One recording: its summary and action items (or the command), the transcript with
 * speakers and times, and a player that streams the audio from the receiver. Tapping a
 * transcript line plays from there. Nothing downloads until the user presses play.
 */
class EntryActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var fmt: Format
    private lateinit var id: String
    private val main = Handler(Looper.getMainLooper())

    private var entry: Entry? = null
    private var api: ReceiverApi? = null
    private var problem: Receiver.Problem? = null
    private var loading = false

    private lateinit var reprocessBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var chips: LinearLayout
    private lateinit var notice: TextView
    private lateinit var body: LinearLayout
    private lateinit var transcriptHeader: TextView
    private lateinit var transcriptNote: TextView
    private lateinit var list: ListView
    private val segments = SegmentsAdapter()

    private lateinit var playBtn: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView

    // ------------------------------------------------------------------ player state
    private var player: MediaPlayer? = null
    private var prepared = false
    private var preparing = false
    private var playWhenReady = false
    private var pendingSeekMs: Int? = null
    private var dragging = false
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }
            .build()
    }

    /** Moves the seek bar while playing; stopped whenever playback is. */
    private val tick = object : Runnable {
        override fun run() {
            showPosition()
            if (player?.isPlaying == true) main.postDelayed(this, 250)
        }
    }

    /** The list changed: follow this recording's status, or close if it was deleted. */
    private val onLibrary: () -> Unit = {
        val listed = Library.index[id]
        val e = entry
        if (listed == null && e != null && Library.fetchedOk) {
            finish()
        } else if (listed != null && e != null && (listed.status != e.status || listed.title != e.title)) {
            entry = e.copy(status = listed.status, title = listed.title ?: e.title)
            render()
            if (listed.status.finished || listed.status == Entry.Status.QUEUED) load()   // new transcript, summary or audio
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        ui = Ui(this)
        fmt = Format(use24h = android.text.format.DateFormat.is24HourFormat(this))
        Library.load(this)
        entry = Library.index[id]           // the list's copy: title and status, right away
        setContentView(buildUi())
        render()
        load()
    }

    override fun onStart() {
        super.onStart()
        Library.addListener(onLibrary)
    }

    override fun onStop() {
        Library.removeListener(onLibrary)
        pause()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ data

    private fun load() {
        if (loading) return
        loading = true
        render()
        val app = applicationContext
        Ui.io.execute {
            val src = Receiver.source(app)
            val result = (src as? Receiver.Source.Ready)?.let { r -> runCatching { r.api.entry(id) } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loading = false
                api = (src as? Receiver.Source.Ready)?.api
                val e = result?.getOrNull()
                if (e != null) {
                    entry = e
                    Library.index.upsert(e)
                    problem = null
                } else {
                    problem = Receiver.problem(src, result?.exceptionOrNull())
                }
                render()
            }
        }
    }

    private fun confirmReprocess() {
        AlertDialog.Builder(this)
            .setTitle("Reprocess this recording?")
            .setMessage("The receiver transcribes and summarizes it again, and replaces the transcript and summary it has now.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reprocess") { _, _ -> reprocess() }
            .show()
    }

    private fun reprocess() {
        val a = api ?: return
        Ui.io.execute {
            val r = runCatching { a.reprocess(id) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (r.isSuccess) {
                    entry = entry?.copy(status = Entry.Status.QUEUED, error = null)
                    entry?.let { Library.index.upsert(it) }
                    toast("Queued. This page updates when it's done.")
                } else {
                    toast(Receiver.problem(Receiver.Source.Ready(a), r.exceptionOrNull()).title)
                }
                render()
            }
        }
    }

    private fun confirmDelete() {
        val e = entry
        val live = id in AppStatus.snapshot.live
        val made = e?.command?.action in listOf("CREATE_NOTE", "ADD_TO_NOTE", "CREATE_REMINDER")
        AlertDialog.Builder(this)
            .setTitle("Delete this recording?")
            .setMessage(buildString {
                append("Its audio, transcript and summary are deleted from your receiver and this phone. This can't be undone.")
                if (made) append("\n\nThe ${if (e?.command?.action == "CREATE_REMINDER") "reminder" else "note"} made from it goes too.")
                if (live) append("\n\nIt's recording now: it stops, and isn't transcribed.")
            })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val app = applicationContext
                Library.delete(this, listOf(id)) { failed ->
                    if (failed > 0) Toast.makeText(app, "Couldn't reach your receiver: not deleted.", Toast.LENGTH_LONG).show()
                }
                finish()
            }
            .show()
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        val e = entry
        titleView.text = e?.let { fmt.title(it) } ?: "Recording"
        metaView.text = e?.let { meta(it) } ?: ""

        chips.removeAllViews()
        if (e != null) {
            chips.addView(
                if (e.kind == Entry.Kind.COMMAND) ui.badge("Command", ui.accent, ui.onAccent)
                else ui.badge("Summary", ui.surface, ui.fgMuted),
            )
            val c = when (e.status) {
                Entry.Status.RECORDING, Entry.Status.FAILED -> ui.red
                Entry.Status.DONE -> ui.green
                else -> ui.accentText
            }
            chips.addView(ui.dot(c, 6).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)).apply { marginStart = ui.dp(12) }
            })
            chips.addView(ui.text(13f, if (e.status == Entry.Status.DONE) ui.fgMuted else c, value = fmt.status(e)).apply {
                setPadding(ui.dp(6), 0, 0, 0)
            })
            e.speakers?.let { n ->
                chips.addView(ui.text(13f, ui.fgMuted, value = "·  $n speaker${if (n == 1) "" else "s"}").apply {
                    setPadding(ui.dp(10), 0, 0, 0)
                })
            }
        }

        val p = problem
        val (noticeText, noticeColor) = when {
            p != null && (e == null || (e.transcript == null && e.summary == null && e.command == null)) ->
                "${p.title}. ${p.body}" to ui.redSoft
            e?.status == Entry.Status.FAILED -> "Processing failed${e.error?.let { ": $it" } ?: "."} Reprocess to try again." to ui.redSoft
            e?.status == Entry.Status.RECORDING -> "Still recording. The transcript and summary follow when it ends." to ui.surface
            e != null && !e.status.finished -> "${fmt.status(e)} This page updates by itself when it's done." to ui.surface
            else -> null to 0
        }
        notice.visibility = if (noticeText == null) View.GONE else View.VISIBLE
        if (noticeText != null) {
            notice.text = noticeText
            notice.background = ui.rounded(noticeColor, 12)
        }

        renderBody(e)

        val segs = e?.transcript?.segments.orEmpty()
        segments.set(segs)
        transcriptHeader.text = buildString {
            append("TRANSCRIPT")
            e?.transcript?.language?.takeIf { it.isNotBlank() }?.let { append("  ·  ${it.uppercase()}") }
        }
        transcriptNote.visibility = if (segs.isEmpty()) View.VISIBLE else View.GONE
        transcriptNote.text = when {
            loading && e?.transcript == null -> "Loading…"
            e?.status == Entry.Status.DONE -> "No transcript for this recording."
            else -> "The transcript appears here once it's transcribed."
        }

        reprocessBtn.visibility =
            if (api != null && e != null && (e.status.finished || e.status == Entry.Status.OTHER)) View.VISIBLE else View.GONE
        renderPlayer()
    }

    private fun meta(e: Entry): String = buildString {
        append(fmt.date(e.startedAt))
        append(" · ")
        append(fmt.clock(e.startedAt))
        e.endedAt?.let { append("–").append(fmt.clock(it)) }
        e.durationS?.takeIf { e.endedAt != null }?.let { append(" · ").append(Format.shortDuration(it)) }
    }

    /** Summary and action items, or the command. */
    private fun renderBody(e: Entry?) {
        body.removeAllViews()
        if (e == null) return
        val s = e.summary
        val c = e.command
        if (s != null) {
            if (!s.summary.isNullOrBlank()) {
                body.addView(ui.sectionHeader("Summary"))
                body.addView(ui.text(16f).apply {
                    text = Markdown.render(s.summary, ui)
                    setLineSpacing(ui.dpf(4f), 1f)
                    setTextIsSelectable(true)
                })
            }
            if (s.actionItems.isNotEmpty()) {
                body.addView(ui.sectionHeader("Action items"))
                s.actionItems.forEach { item ->
                    val box = View(this).apply { background = ui.rounded(0, 4, ui.accentText) }
                    val t = ui.text(16f, value = item).apply { setLineSpacing(ui.dpf(2f), 1f) }
                    body.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, ui.dp(6), 0, ui.dp(6))
                        addView(box, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply {
                            topMargin = ui.dp(4)
                            marginEnd = ui.dp(12)
                        })
                        addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    })
                }
            }
        } else if (c != null) {
            body.addView(ui.sectionHeader("Command"))
            val card = ui.card()
            card.addView(ui.text(18f, bold = true, value = Format.commandAction(c.action)))
            fun field(label: String, value: String?) {
                if (value.isNullOrBlank()) return
                card.addView(ui.text(12f, ui.fgMuted, bold = true, value = label.uppercase()).apply {
                    letterSpacing = 0.06f
                    setPadding(0, ui.dp(12), 0, ui.dp(2))
                })
                card.addView(ui.text(16f, value = value).apply { setTextIsSelectable(true) })
            }
            field("Note", c.note)
            field("When", c.whenIso?.let { Format.whenLabel(it) })
            field("Text", c.text)
            body.addView(card)
        } else if (!e.preview.isNullOrBlank()) {
            body.addView(ui.sectionHeader("Summary"))
            body.addView(ui.text(16f, ui.fgMuted, value = e.preview).apply { setLineSpacing(ui.dpf(4f), 1f) })
        }
    }

    // ------------------------------------------------------------------ player

    private val canPlay get() = api != null && entry?.hasAudio == true

    private fun renderPlayer() {
        val mp = player
        val playing = mp?.isPlaying == true
        playBtn.setImageResource(if (playing || (preparing && playWhenReady)) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = if (playing) "Pause" else "Play"
        playBtn.isEnabled = canPlay
        playBtn.alpha = if (canPlay) 1f else 0.4f
        seek.isEnabled = canPlay
        val totalMs = if (prepared && mp != null && mp.duration > 0) mp.duration else ((entry?.durationS ?: 0.0) * 1000).toInt()
        seek.max = totalMs.coerceAtLeast(1)
        durText.text = if (totalMs > 0) Format.clockDuration(totalMs / 1000.0) else ""
        when {
            api == null && entry != null && !loading -> posText.text = "The receiver isn't reachable"
            entry?.hasAudio == false -> posText.text = "Audio is ready when the recording ends"
            preparing -> posText.text = "Loading audio…"
            else -> showPosition()
        }
    }

    private fun showPosition() {
        if (dragging) return
        val mp = player
        val ms = when {
            mp != null && prepared -> mp.currentPosition
            else -> pendingSeekMs ?: 0
        }
        seek.progress = ms
        posText.text = Format.clockDuration(ms / 1000.0)
        // Highlight the line being heard, once there is something to hear.
        segments.highlight(if (mp != null || pendingSeekMs != null) ms / 1000.0 else -1.0)
    }

    private fun togglePlay() {
        val mp = player
        when {
            mp != null && prepared -> if (mp.isPlaying) pause() else play()
            preparing -> playWhenReady = !playWhenReady
            else -> prepare(playAfter = true)
        }
        renderPlayer()
    }

    /** Start streaming the audio. MediaPlayer sends the bearer token and uses Range to seek. */
    private fun prepare(playAfter: Boolean) {
        val a = api ?: return
        if (!canPlay) return
        releasePlayer()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(this, Uri.parse(a.audioUrl(id)), a.authHeaders())
        } catch (e: Exception) {
            mp.release()
            toast("Couldn't open the audio: ${e.message}")
            return
        }
        mp.setOnPreparedListener {
            prepared = true
            preparing = false
            pendingSeekMs?.let { ms -> it.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) }
            pendingSeekMs = null
            if (playWhenReady) play()
            renderPlayer()
        }
        mp.setOnCompletionListener {
            audio.abandonAudioFocusRequest(focus)
            main.removeCallbacks(tick)
            renderPlayer()
            showPosition()
        }
        mp.setOnErrorListener { _, what, extra ->
            releasePlayer()
            toast("Couldn't play the audio (error $what/$extra). Is the receiver reachable?")
            renderPlayer()
            true
        }
        player = mp
        preparing = true
        playWhenReady = playAfter
        mp.prepareAsync()
    }

    private fun play() {
        val mp = player ?: return
        if (!prepared) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        mp.start()
        main.removeCallbacks(tick)
        main.post(tick)
        renderPlayer()
    }

    private fun pause() {
        playWhenReady = false
        val mp = player ?: return
        if (prepared && mp.isPlaying) mp.pause()
        audio.abandonAudioFocusRequest(focus)
        main.removeCallbacks(tick)
        renderPlayer()
    }

    /** Jump to [ms] and play from there (a transcript tap), or just move there (the seek bar). */
    private fun seekTo(ms: Int, andPlay: Boolean) {
        val mp = player
        if (mp != null && prepared) {
            mp.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
            if (andPlay && !mp.isPlaying) play()
        } else {
            pendingSeekMs = ms
            if (andPlay) {
                if (preparing) playWhenReady = true else prepare(playAfter = true)
            }
        }
        seek.progress = ms
        posText.text = Format.clockDuration(ms / 1000.0)
        segments.highlight(ms / 1000.0)
        renderPlayer()
    }

    private fun releasePlayer() {
        main.removeCallbacks(tick)
        player?.let { runCatching { it.release() } }
        player = null
        prepared = false
        preparing = false
        if (::ui.isInitialized) runCatching { audio.abandonAudioFocusRequest(focus) }
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        reprocessBtn = ui.link("Reprocess") { confirmReprocess() }.apply { visibility = View.GONE }
        val (bar, _) = ui.topBar("", { finish() }, reprocessBtn, ui.icon(R.drawable.ic_delete, "Delete") { confirmDelete() })

        titleView = ui.text(24f, bold = true).apply {
            letterSpacing = -0.01f
            setLineSpacing(ui.dpf(2f), 1f)
        }
        metaView = ui.text(14f, ui.fgMuted)
        chips = ui.row()
        notice = ui.text(14f).apply {
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            setLineSpacing(ui.dpf(2f), 1f)
            visibility = View.GONE
            setOnClickListener { if (problem != null) load() }
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        transcriptHeader = ui.sectionHeader("Transcript")
        transcriptNote = ui.text(15f, ui.fgMuted).apply { setPadding(0, 0, 0, ui.dp(16)) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0)
            addView(titleView)
            addView(metaView, lp(top = 6))
            addView(chips, lp(top = 12))
            addView(notice, lp(top = 16))
            addView(body, lp())
            addView(transcriptHeader, lp())
            addView(transcriptNote, lp())
        }

        list = ListView(this).apply {
            divider = null
            clipToPadding = false
            addHeaderView(header, null, false)
            adapter = segments
            setOnItemClickListener { _, _, pos, _ ->
                val seg = segments.getItem(pos - headerViewsCount) as? Entry.Segment ?: return@setOnItemClickListener
                if (canPlay) seekTo((seg.start * 1000).toInt(), andPlay = true)
            }
        }

        val playerBar = buildPlayer()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(View(this@EntryActivity).apply { setBackgroundColor(ui.divider) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
            addView(playerBar)
        }
        val capped = CappedWidth(this, ui.dp(760)).apply { addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        Ui.edgeToEdge(this, capped) { v, top, bottom, left, right ->
            v.setPadding(left, top, right, 0)
            playerBar.setPadding(ui.dp(12), ui.dp(10), ui.dp(16), ui.dp(10) + bottom)
        }
        return capped
    }

    private fun buildPlayer(): LinearLayout {
        playBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(ui.onAccent)
            background = ui.ripple(ui.rounded(ui.accent, 28))
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            setOnClickListener { togglePlay() }
        }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(ui.accent)
            thumbTintList = ColorStateList.valueOf(ui.accent)
            progressBackgroundTintList = ColorStateList.valueOf(ui.fgMuted)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) posText.text = Format.clockDuration(progress / 1000.0)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    seekTo(sb.progress, andPlay = false)
                }
            })
        }
        posText = ui.text(12f, ui.fgMuted)
        durText = ui.text(12f, ui.fgMuted)
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(8), 0, ui.dp(8), 0)
            addView(posText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(durText)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(times, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ui.background)
            addView(playBtn, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ui.dp(8) })
        }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(top) }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ transcript

    private inner class SegmentsAdapter : BaseAdapter() {
        private var items: List<Entry.Segment> = emptyList()
        private var current = -1

        fun set(segs: List<Entry.Segment>) {
            if (segs === items) return
            items = segs
            current = -1
            notifyDataSetChanged()
        }

        /** Mark the line being played; redraws only when it changes. */
        fun highlight(sec: Double) {
            var lo = 0
            var hi = items.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (items[mid].start <= sec) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (found != current) {
                current = found
                notifyDataSetChanged()
            }
        }

        override fun getCount() = items.size
        override fun getItem(position: Int): Any? = items.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val h = (convertView?.tag as? SegmentRow) ?: SegmentRow()
            val seg = items[position]
            val prev = items.getOrNull(position - 1)
            h.bind(seg, showSpeaker = prev == null || prev.speaker != seg.speaker, current = position == current)
            return h.view
        }
    }

    private inner class SegmentRow {
        val speaker = ui.text(13f, bold = true)
        val time = ui.text(12f, ui.fgMuted)
        val text = ui.text(16f).apply { setLineSpacing(ui.dpf(3f), 1f) }
        val view = LinearLayout(this@EntryActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
            addView(LinearLayout(this@EntryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(speaker)
                addView(time)
            })
            addView(text, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(2) })
            tag = this@SegmentRow
        }

        fun bind(seg: Entry.Segment, showSpeaker: Boolean, current: Boolean) {
            val name = Format.speaker(seg.speaker)
            speaker.visibility = if (showSpeaker && name != null) View.VISIBLE else View.GONE
            speaker.text = name
            speaker.setTextColor(speakerColor(Format.speakerIndex(seg.speaker)))
            time.text = Format.clockDuration(seg.start)
            time.setPadding(if (speaker.visibility == View.VISIBLE) ui.dp(8) else 0, 0, 0, 0)
            text.text = seg.text.trim()
            view.setPadding(ui.dp(20), ui.dp(if (showSpeaker) 12 else 4), ui.dp(20), ui.dp(4))
            view.setBackgroundColor(if (current) ui.accentSoft else 0)
        }
    }

    private fun speakerColor(i: Int): Int {
        if (i < 0) return ui.fgMuted
        val light = intArrayOf(0xFF1E63C9.toInt(), 0xFFB5470B.toInt(), 0xFF0F7A55.toInt(), 0xFF8340A8.toInt(), 0xFFAD2257.toInt(), 0xFF00727D.toInt())
        val dark = intArrayOf(0xFF7DB2FF.toInt(), 0xFFFFA36E.toInt(), 0xFF62D49A.toInt(), 0xFFCB9DF5.toInt(), 0xFFF28CB4.toInt(), 0xFF4FD3E0.toInt())
        val palette = if (ui.night) dark else light
        return palette[i % palette.size]
    }

    companion object {
        const val EXTRA_ID = "entry_id"

        fun open(ctx: Context, id: String) =
            ctx.startActivity(Intent(ctx, EntryActivity::class.java).putExtra(EXTRA_ID, id))
    }
}
