package io.github.pablocollantes.releasethebee.service

import io.github.pablocollantes.releasethebee.rtb.RtbLink.Button
import io.github.pablocollantes.releasethebee.service.Recorder.Action
import io.github.pablocollantes.releasethebee.sink.AudioFrame
import io.github.pablocollantes.releasethebee.sink.SessionInfo
import io.github.pablocollantes.releasethebee.sink.SessionInfo.Kind
import io.github.pablocollantes.releasethebee.sink.SessionInfo.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {

    private var now = 1_000_000L
    private var ids = 0
    private var config = Recorder.Config(minCommandMs = 0)      // the short-command rule has its own tests
    private val events = mutableListOf<String>()
    private val sessions = mutableListOf<SessionInfo>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()

    private val out = object : Recorder.Output {
        override fun sessionStart(s: SessionInfo) {
            sessions += s
            events += "start ${s.id} ${s.kind.wire} ${s.trigger?.wire} parent=${s.parentId}"
        }
        override fun audio(f: AudioFrame) { events += "audio ${f.seq}" }
        override fun sessionEnd(s: SessionInfo, reason: String, discard: Boolean) {
            events += "end ${s.id} ($reason)" + if (discard) " discard" else ""
        }
        override fun setRecording(on: Boolean, temporary: Boolean) {
            events += (if (on) "unmute" else "mute") + if (temporary) " temp" else ""
        }
        override fun commandFeedback(start: Boolean, ledShows: Boolean) {
            events += "feedback ${if (start) "start" else "end"}${if (ledShows) "" else " blink"}"
        }
        override fun schedule(ms: Long, f: () -> Unit): () -> Unit {
            val t = ms to f
            timers += t
            return { timers.remove(t) }
        }
        override fun log(line: String) {}
    }

    private val r = Recorder({ config }, out, { now }, { "s${++ids}" })

    private fun take(): List<String> = events.toList().also { events.clear() }
    private fun frame(seq: Long) = AudioFrame(seq, now, ByteArray(1))

    @Test fun `unmuting records a summary by default`() {
        r.streamStarted("dev")
        r.audio(frame(1))
        r.streamEnded("RTB stopped recording")
        assertEquals(listOf("start s1 summary button parent=null", "audio 1", "end s1 (RTB stopped recording)"), take())
        assertEquals("dev", sessions.single().deviceIdHex)
    }

    @Test fun `audio with no recording open goes nowhere`() {
        r.audio(frame(1))
        r.streamStarted("dev")
        r.streamEnded("muted")
        r.audio(frame(2))
        assertEquals(listOf("start s1 summary button parent=null", "end s1 (muted)"), take())
    }

    @Test fun `double press during a summary nests a command, and again hands back`() {
        r.streamStarted("dev")
        take()
        r.button(Button.DOUBLE_PRESS)
        r.audio(frame(1))
        assertEquals(listOf("start s2 command double_press parent=s1", "feedback start blink", "audio 1"), take())
        assertEquals("s2", r.current?.id)
        r.button(Button.DOUBLE_PRESS)
        r.audio(frame(2))
        assertEquals(listOf("end s2 (double press)", "feedback end blink", "audio 2"), take())
        assertEquals("s1", r.current?.id)
        assertEquals(0, timers.size)
    }

    @Test fun `double press while muted unmutes for a command and mutes after`() {
        r.button(Button.DOUBLE_PRESS)
        assertEquals(listOf("unmute temp"), take())
        r.streamStarted("dev")
        assertEquals(listOf("start s1 command double_press parent=null", "feedback start"), take())
        r.button(Button.DOUBLE_PRESS)
        assertEquals(listOf("end s1 (double press)", "feedback end", "mute temp"), take())
        r.streamEnded("muted from the app")
        assertEquals(emptyList<String>(), take())
    }

    @Test fun `a command ends itself after the time limit`() {
        config = config.copy(commandMaxMs = 15_000)
        r.streamStarted("dev")
        r.button(Button.DOUBLE_PRESS)
        take()
        val (ms, fire) = timers.single()
        assertEquals(15_000L, ms)
        fire()
        assertEquals(listOf("end s2 (time limit)", "feedback end blink"), take())
        assertEquals("s1", r.current?.id)
    }

    @Test fun `hold records while held`() {
        r.streamStarted("dev")
        take()
        r.button(Button.HOLD_START)
        assertEquals(listOf("start s2 command hold parent=s1", "feedback start blink"), take())
        r.button(Button.HOLD_END)
        assertEquals(listOf("end s2 (hold released)", "feedback end blink"), take())
    }

    @Test fun `hold while muted unmutes, release mutes`() {
        r.button(Button.HOLD_START)
        r.streamStarted("dev")
        r.button(Button.HOLD_END)
        assertEquals(
            listOf("unmute temp", "start s1 command hold parent=null", "feedback start", "end s1 (hold released)", "feedback end", "mute temp"),
            take(),
        )
    }

    @Test fun `a hold let go before the RTB started cancels the start`() {
        r.button(Button.HOLD_START)
        r.button(Button.HOLD_END)
        assertEquals(listOf("unmute temp", "mute temp"), take())
    }

    @Test fun `muting during a nested command ends both, command first`() {
        r.streamStarted("dev")
        r.button(Button.DOUBLE_PRESS)
        take()
        r.streamEnded("RTB stopped recording")
        assertEquals(
            listOf("end s2 (RTB stopped recording)", "feedback end", "end s1 (RTB stopped recording)"),
            take(),
        )
        assertNull(r.current)
        assertEquals(0, timers.size)
    }

    @Test fun `unmute can record commands, and double press summaries`() {
        config = config.copy(unmute = Kind.COMMAND, doublePress = Action.SUMMARY)
        r.streamStarted("dev")
        assertEquals(listOf("start s1 command button parent=null", "feedback start"), take())
        r.streamEnded("RTB stopped recording")
        assertEquals(listOf("end s1 (RTB stopped recording)", "feedback end"), take())

        r.button(Button.DOUBLE_PRESS)
        r.streamStarted("dev")
        assertEquals(listOf("unmute", "start s2 summary double_press parent=null"), take())
        r.button(Button.DOUBLE_PRESS)
        assertEquals(listOf("end s2 (double press)", "mute"), take())
    }

    @Test fun `gestures set to nothing do nothing`() {
        config = config.copy(doublePress = Action.NOTHING, hold = Action.NOTHING)
        r.streamStarted("dev")
        take()
        r.button(Button.DOUBLE_PRESS)
        r.button(Button.HOLD_START)
        r.button(Button.HOLD_END)
        assertEquals(emptyList<String>(), take())
    }

    @Test fun `a start the RTB never acted on expires`() {
        r.button(Button.DOUBLE_PRESS)
        now += 60_000
        r.streamStarted("dev")             // the user's own single press, much later
        assertEquals(listOf("unmute temp", "start s1 summary button parent=null"), take())
    }

    @Test fun `deleting a command inside a summary hands the audio back`() {
        r.streamStarted("dev")
        r.button(Button.DOUBLE_PRESS)
        take()
        assertEquals(listOf("s1", "s2"), r.live)
        assertTrue(r.discard("s2"))
        r.audio(frame(1))
        assertEquals(listOf("end s2 (deleted) discard", "audio 1"), take())
        assertEquals("s1", r.current?.id)
        assertEquals(0, timers.size)
    }

    @Test fun `deleting the summary keeps its command and mutes the RTB`() {
        r.streamStarted("dev")
        r.button(Button.DOUBLE_PRESS)
        take()
        assertTrue(r.discard("s1"))
        assertEquals(
            listOf("end s2 (its recording was deleted)", "feedback end", "end s1 (deleted) discard", "mute"),
            take(),
        )
        assertNull(r.current)
        r.streamEnded("muted from the app")
        assertEquals(emptyList<String>(), take())
    }

    @Test fun `deleting a command from a muted RTB mutes it again`() {
        r.button(Button.DOUBLE_PRESS)
        r.streamStarted("dev")
        take()
        assertTrue(r.discard("s1"))
        assertEquals(listOf("end s1 (deleted) discard", "mute temp"), take())
        assertFalse(r.discard("s1"))
        assertEquals(emptyList<String>(), r.live)
    }

    @Test fun `trigger wire names match the stream protocol`() {
        assertEquals(listOf("button", "double_press", "hold", "app"), Trigger.entries.map { it.wire })
    }

    @Test fun `a hold let go within a second was a press, not a command`() {
        config = config.copy(minCommandMs = 1_000)
        r.button(Button.HOLD_START)
        r.streamStarted("dev")
        assertTrue(r.commandOpen)
        now += 300
        r.button(Button.HOLD_END)
        assertEquals(
            listOf("unmute temp", "start s1 command hold parent=null", "feedback start", "end s1 (too short) discard", "mute temp"),
            take(),
        )
        assertFalse(r.commandOpen)
    }

    @Test fun `a command of a second or more is kept`() {
        config = config.copy(minCommandMs = 1_000)
        r.button(Button.HOLD_START)
        r.streamStarted("dev")
        now += 1_000
        r.button(Button.HOLD_END)
        assertEquals(
            listOf("unmute temp", "start s1 command hold parent=null", "feedback start", "end s1 (hold released)", "feedback end", "mute temp"),
            take(),
        )
    }

    @Test fun `a short summary is still a summary`() {
        config = config.copy(minCommandMs = 1_000)
        r.streamStarted("dev")
        r.streamEnded("RTB stopped recording")
        assertEquals(listOf("start s1 summary button parent=null", "end s1 (RTB stopped recording)"), take())
    }
}
