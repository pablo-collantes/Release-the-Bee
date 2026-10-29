package io.github.pablocollantes.releasethebee.service

import io.github.pablocollantes.releasethebee.rtb.RtbLink
import io.github.pablocollantes.releasethebee.sink.AudioFrame
import io.github.pablocollantes.releasethebee.sink.SessionInfo
import io.github.pablocollantes.releasethebee.sink.SessionInfo.Kind
import io.github.pablocollantes.releasethebee.sink.SessionInfo.Trigger
import java.util.UUID

/**
 * Turns what the RTB does into recordings: what kind each one is, which gesture started
 * it, and when it ends.
 *
 * - **Single press**: the RTB unmutes or mutes itself. Unmuting starts a recording of
 *   [Config.unmute]; muting ends whatever is open.
 * - **Double press** and **hold**: the RTB only reports them, so their meaning is ours
 *   ([Config.doublePress], [Config.hold]). A command during a summary recording is nested
 *   in it (audio goes to the command, then back). A gesture while the RTB is muted
 *   unmutes it for the recording and mutes it again after.
 *
 * Commands end on the same gesture again (or a hold's release), or after
 * [Config.commandMaxMs]. Their start and end get [Output.commandFeedback].
 *
 * Unmuting the RTB for a command is temporary: the RTB's resting state (muted or
 * recording) is whatever its button or a summary last chose, and a dropped link mid-command
 * goes back to it. [discard] stops a recording the user deleted while it ran.
 *
 * Calls arrive from the link thread and from timers; everything is synchronized, so audio
 * never lands outside the session it belongs to.
 */
class Recorder(
    private val config: () -> Config,
    private val out: Output,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    enum class Action { SUMMARY, COMMAND, NOTHING }

    data class Config(
        val unmute: Kind = Kind.SUMMARY,
        val doublePress: Action = Action.COMMAND,
        val hold: Action = Action.COMMAND,
        val commandMaxMs: Long = 30_000,
        /** A command shorter than this was a press that read as a hold: thrown away, no feedback. */
        val minCommandMs: Long = 1_000,
    )

    interface Output {
        fun sessionStart(s: SessionInfo)
        fun audio(f: AudioFrame)
        /** [discard]: the user deleted it while it ran. */
        fun sessionEnd(s: SessionInfo, reason: String, discard: Boolean)
        /**
         * Ask the RTB to record or mute. [temporary]: just for a command, so not its new
         * resting state (see [io.github.pablocollantes.releasethebee.rtb.RtbLink.setRecording]).
         */
        fun setRecording(on: Boolean, temporary: Boolean)
        /** A command started or ended. [ledShows]: the RTB unmuted or muted for it, so its light already changed. */
        fun commandFeedback(start: Boolean, ledShows: Boolean)
        /** Run [f] after [ms]; returns a cancel. */
        fun schedule(ms: Long, f: () -> Unit): () -> Unit
        fun log(line: String)
    }

    /** We asked the RTB to record, for this. */
    private class Pending(val kind: Kind, val trigger: Trigger, val hold: Boolean, val at: Long)

    private var deviceHex = "?"
    private var streaming = false
    private var top: SessionInfo? = null
    private var child: SessionInfo? = null       // a command inside the summary in [top]
    private var held: SessionInfo? = null        // started by a hold: its release ends it
    private var pending: Pending? = null
    private var cancelTimer: (() -> Unit)? = null

    /** Where audio goes right now: the command inside a summary, or the outer recording. */
    val current: SessionInfo? @Synchronized get() = child ?: top

    /** Whether a command is recording now. */
    val commandOpen: Boolean @Synchronized get() = commandOpen() != null

    /** Ids of the recordings open now, outermost first. */
    val live: List<String> @Synchronized get() = listOfNotNull(top?.id, child?.id)

    // ------------------------------------------------------------------ from the link

    @Synchronized fun streamStarted(deviceIdHex: String) {
        if (top != null) streamEnded("restarted")
        deviceHex = deviceIdHex
        streaming = true
        val p = pending?.takeIf { clock() - it.at < PENDING_MS }
        pending = null
        val s = open(p?.kind ?: config().unmute, p?.trigger ?: Trigger.BUTTON, parent = null)
        if (p?.hold == true) held = s
        if (s.kind == Kind.COMMAND) commandStarted(s, ledShows = true)
    }

    @Synchronized fun audio(f: AudioFrame) {
        if (child != null || top != null) out.audio(f)
    }

    @Synchronized fun streamEnded(reason: String) {
        streaming = false
        child?.let { close(it, reason, ledShows = true) }
        top?.let { close(it, reason, ledShows = true) }
    }

    @Synchronized fun button(b: RtbLink.Button) {
        val cfg = config()
        when (b) {
            RtbLink.Button.DOUBLE_PRESS -> when (cfg.doublePress) {
                Action.COMMAND -> toggleCommand(Trigger.DOUBLE_PRESS)
                Action.SUMMARY -> toggleSummary(Trigger.DOUBLE_PRESS)
                Action.NOTHING -> out.log("double press: set to do nothing")
            }
            RtbLink.Button.HOLD_START -> when (cfg.hold) {
                Action.COMMAND -> if (commandOpen() != null) out.log("hold: a command is already recording")
                else startCommand(Trigger.HOLD, hold = true)
                Action.SUMMARY -> if (top != null || streaming) out.log("hold: already recording")
                else requestStart(Kind.SUMMARY, Trigger.HOLD, hold = true)
                Action.NOTHING -> out.log("hold: set to do nothing")
            }
            RtbLink.Button.HOLD_END -> {
                val h = held
                when {
                    h != null -> end(h, "hold released")
                    pending?.hold == true -> {      // let go before the RTB started recording
                        val p = pending!!
                        pending = null
                        out.setRecording(false, temporary = p.kind == Kind.COMMAND)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ gestures

    private fun commandOpen(): SessionInfo? = child ?: top?.takeIf { it.kind == Kind.COMMAND }

    private fun toggleCommand(trigger: Trigger) {
        val running = commandOpen()
        if (running != null) end(running, trigger.wire.replace('_', ' ')) else startCommand(trigger, hold = false)
    }

    private fun startCommand(trigger: Trigger, hold: Boolean) {
        val t = top
        when {
            t != null && t.kind == Kind.SUMMARY -> {
                val s = open(Kind.COMMAND, trigger, parent = t.id)
                if (hold) held = s
                commandStarted(s, ledShows = false)
            }
            t == null && !streaming -> requestStart(Kind.COMMAND, trigger, hold)
            else -> out.log("command: the RTB is busy changing state; try again")
        }
    }

    private fun toggleSummary(trigger: Trigger) {
        if (top == null && !streaming) requestStart(Kind.SUMMARY, trigger, hold = false)
        else top?.let { end(it, trigger.wire.replace('_', ' ')) }
    }

    private fun requestStart(kind: Kind, trigger: Trigger, hold: Boolean) {
        pending = Pending(kind, trigger, hold, clock())
        out.setRecording(true, temporary = kind == Kind.COMMAND)
    }

    /** End [s]. A nested command hands the audio back to its summary; anything else mutes the RTB. */
    private fun end(s: SessionInfo, reason: String) {
        if (s === child) {
            close(s, reason, ledShows = false)
        } else if (s === top) {
            child?.let { close(it, reason, ledShows = true) }
            close(s, reason, ledShows = true)
            out.setRecording(false, temporary = s.kind == Kind.COMMAND)
        }
    }

    /**
     * The user deleted recording [id] while it ran: end it, and tell the sinks to throw it
     * away. A command inside a summary hands the audio back to the summary. An outer
     * recording also mutes the RTB; a command recorded inside it is kept. False if [id]
     * isn't recording.
     */
    @Synchronized fun discard(id: String): Boolean {
        val c = child
        val t = top
        when (id) {
            c?.id -> close(c, "deleted", ledShows = false, discard = true)
            t?.id -> {
                c?.let { close(it, "its recording was deleted", ledShows = true) }
                close(t, "deleted", ledShows = true, discard = true)
                out.setRecording(false, temporary = t.kind == Kind.COMMAND)
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ sessions

    private fun open(kind: Kind, trigger: Trigger, parent: String?): SessionInfo {
        val s = SessionInfo(newId(), deviceHex, clock(), kind, trigger, parent)
        if (parent == null) top = s else child = s
        out.sessionStart(s)
        return s
    }

    private fun close(s: SessionInfo, reason: String, ledShows: Boolean, discard: Boolean = false) {
        if (s === child) child = null
        if (s === top) top = null
        if (s === held) held = null
        val blip = !discard && s.kind == Kind.COMMAND && clock() - s.startedAtMs < config().minCommandMs
        if (blip) out.log("command ${s.id.take(8)} lasted under ${config().minCommandMs} ms: a press, not a command; not kept")
        out.sessionEnd(s, if (blip) "too short" else reason, discard || blip)
        if (s.kind == Kind.COMMAND) {
            cancelTimer?.invoke()
            cancelTimer = null
            // Deleted on the phone, or nothing said: no buzz.
            if (!discard && !blip) out.commandFeedback(start = false, ledShows = ledShows)
        }
    }

    private fun commandStarted(s: SessionInfo, ledShows: Boolean) {
        out.commandFeedback(start = true, ledShows = ledShows)
        cancelTimer?.invoke()
        val max = config().commandMaxMs
        cancelTimer = out.schedule(max) { timeUp(s, max) }
    }

    @Synchronized private fun timeUp(s: SessionInfo, max: Long) {
        if (s !== child && s !== top) return
        cancelTimer = null
        out.log("command reached ${max / 1000} s; ending it")
        end(s, "time limit")
    }

    private companion object {
        /** How long a requested start waits for the RTB before a stream counts as its own. */
        const val PENDING_MS = 10_000L
    }
}
