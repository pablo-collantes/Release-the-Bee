import asyncio
import os
import struct
import tempfile
import time
import unittest

import helpers  # noqa: F401
import rtb_entries
import rtb_receiver

START = 1761651534000           # 2025-10-28 12:38:54 UTC


def audio(seq, payload):
    return struct.pack(">IQ", seq, START + seq * 64) + payload


class Rig:
    def __init__(self, finish_after=600):
        self.tmp = tempfile.TemporaryDirectory()
        self.pushes = []
        self.store = rtb_entries.EntryStore(self.tmp.name, on_change=self.pushes.append)
        self.rec = rtb_receiver.Recorder(self.tmp.name, self.store, finish_after=finish_after)

    def stream(self):
        s = rtb_receiver.Stream(self.rec)
        s.hello({"device": "dev1"})
        return s

    def data(self, id):
        with open(self.store.stems[id] + ".aac", "rb") as f:
            return f.read()

    def files(self):
        """Every file the recorder wrote (the tombstones aside)."""
        out = []
        for d, _dirs, names in os.walk(self.tmp.name):
            out += [os.path.relpath(os.path.join(d, n), self.tmp.name) for n in names if n != "deleted.json"]
        return sorted(out)

    def delete(self, id, rec=True):
        r = rtb_receiver.route("DELETE", f"/api/entries/{id}", {}, self.store, None, rec=self.rec if rec else None)
        return r.status


def run(coro):
    return asyncio.run(coro)


class NestingTest(unittest.TestCase):
    def test_command_inside_summary(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.session_start({"session": "summary-1", "started_at": START, "kind": "summary", "trigger": "button"})
            s.audio(audio(1, b"A1"))
            s.session_start({"session": "command-1", "started_at": START + 5000, "kind": "command",
                             "trigger": "double_press", "parent": "summary-1"})
            s.audio(audio(2, b"C1"))
            s.audio(audio(3, b"C2"))
            s.session_end({"session": "command-1", "reason": "released"})
            self.assertEqual(rig.store.get("command-1")["status"], "queued")
            self.assertEqual(rig.store.get("summary-1")["status"], "recording")
            s.audio(audio(4, b"A2"))
            s.session_end({"session": "summary-1", "reason": "paused"})

            self.assertEqual(rig.data("summary-1"), b"A1A2")       # the command's audio isn't in it
            self.assertEqual(rig.data("command-1"), b"C1C2")
            cmd = rig.store.get("command-1")
            self.assertEqual((cmd["kind"], cmd["parent"], cmd["trigger"]), ("command", "summary-1", "double_press"))
            self.assertEqual(cmd["ended_at"], START + 3 * 64)
            summ = rig.store.get("summary-1")
            self.assertEqual((summ["kind"], summ["parent"], summ["status"]), ("summary", None, "queued"))
            self.assertEqual(summ["ended_at"], START + 4 * 64)
            self.assertEqual(rig.rec.open_sessions, {})
            rig.tmp.cleanup()
        run(go())

    def test_reconnect_during_command_returns_to_parent(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.session_start({"session": "summary-1", "started_at": START})
            s.audio(audio(1, b"A1"))
            s.session_start({"session": "command-1", "started_at": START + 1000, "kind": "command",
                             "parent": "summary-1"})
            s.audio(audio(2, b"C1"))
            s.close()                                                  # network drops
            self.assertEqual(set(rig.rec.pending), {"summary-1", "command-1"})

            s2 = rig.stream()                                          # phone resumes only the command
            s2.session_start({"session": "command-1", "started_at": START + 1000, "kind": "command",
                              "parent": "summary-1", "resumed": True})
            self.assertEqual(set(rig.rec.pending), {"summary-1"})
            s2.audio(audio(3, b"C2"))
            s2.session_end({"session": "command-1"})
            self.assertEqual(s2.current(), "summary-1")                # back to the parent
            self.assertEqual(rig.rec.pending, {})
            s2.audio(audio(4, b"A2"))
            s2.session_end({"session": "summary-1"})
            self.assertEqual(rig.data("summary-1"), b"A1A2")
            self.assertEqual(rig.data("command-1"), b"C1C2")
            rig.tmp.cleanup()
        run(go())

    def test_new_top_level_session_leaves_the_old_one_to_the_grace_period(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.session_start({"session": "one", "started_at": START})
            s.audio(audio(1, b"x"))
            s.session_start({"session": "two", "started_at": START + 60000})
            self.assertIn("one", rig.rec.pending)
            self.assertEqual(s.stack, ["two"])
            rig.rec.finish("one", "test")
            self.assertEqual(rig.store.get("one")["status"], "queued")
            rig.tmp.cleanup()
        run(go())

    def test_audio_without_session_and_empty_sessions(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.audio(audio(1, b"orphan"))
            self.assertEqual(len(s.stack), 1)
            orphan = s.current()
            s.close()
            self.assertEqual(rig.data(orphan), b"orphan")
            s.session_start({"session": "empty", "started_at": START})
            s.session_end({"session": "empty"})
            self.assertEqual(rig.store.get("empty")["status"], "done")  # nothing to process
            rig.tmp.cleanup()
        run(go())

    def test_resume_after_finish_reopens_the_entry(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.session_start({"session": "late", "started_at": START})
            s.audio(audio(1, b"a"))
            s.close()
            rig.rec.finish("late", "not resumed")                       # grace period ran out
            self.assertEqual(rig.store.get("late")["status"], "queued")
            s2 = rig.stream()
            s2.session_start({"session": "late", "started_at": START, "resumed": True})
            self.assertEqual(rig.store.get("late")["status"], "recording")
            s2.audio(audio(2, b"b"))
            s2.session_end({"session": "late"})
            self.assertEqual(rig.data("late"), b"ab")
            rig.tmp.cleanup()
        run(go())

    def test_live_legacy_file_is_adopted_by_its_session(self):
        """The receiver restarted mid-recording: the file has no entry yet, and the phone
        resumes the session under its full id."""
        async def go():
            rig = Rig()
            started = START
            t = time.localtime(started / 1000)
            rel = os.path.join("dev1", time.strftime("%Y-%m-%d", t), time.strftime("%H%M%S", t) + "_abcd1234.aac")
            path = os.path.join(rig.tmp.name, rel)
            os.makedirs(os.path.dirname(path))
            with open(path, "wb") as f:
                f.write(b"old")
            unfinished = rig.store.load(recent_s=600)
            for id in unfinished:
                rig.rec.adopt(id)
            legacy = os.path.basename(path)[:-4]
            self.assertEqual(rig.store.get(legacy)["status"], "recording")
            self.assertIn(legacy, rig.rec.pending)

            s = rig.stream()
            s.session_start({"session": "abcd1234-5678", "started_at": started, "resumed": True})
            self.assertIsNone(rig.store.get(legacy))
            self.assertEqual(rig.rec.pending, {})
            s.audio(audio(1, b"new"))
            s.session_end({"session": "abcd1234-5678"})
            self.assertEqual(rig.data("abcd1234-5678"), b"oldnew")
            self.assertEqual(rig.store.get("abcd1234-5678")["status"], "queued")
            rig.tmp.cleanup()
        run(go())


class DeleteTest(unittest.TestCase):
    def test_delete_while_recording(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.session_start({"session": "rec-1", "started_at": START})
            s.audio(audio(1, b"A1"))
            self.assertEqual(rig.delete("rec-1"), 200)
            self.assertIsNone(rig.store.get("rec-1"))                  # gone from the list right away
            self.assertEqual(rig.pushes[-1]["status"], "deleted")
            s.audio(audio(2, b"A2"))                                   # audio still arriving is fine
            s.session_end({"session": "rec-1", "reason": "paused"})
            self.assertEqual(rig.files(), [])
            self.assertIsNone(rig.store.get("rec-1"))
            self.assertEqual(rig.rec.open_sessions, {})

            s.session_start({"session": "rec-2", "started_at": START + 60000})
            s.audio(audio(3, b"B1"))
            self.assertEqual(rig.delete("rec-2", rec=False), 200)      # deleted behind the recorder's back
            s.audio(audio(4, b"B2"))
            s.session_end({"session": "rec-2"})
            self.assertEqual(rig.files(), [])
            rig.tmp.cleanup()
        run(go())

    def test_delete_while_waiting_for_a_resume(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.session_start({"session": "drop-1", "started_at": START})
            s.session_start({"session": "drop-2", "started_at": START + 60000})
            s.audio(audio(1, b"A1"))
            s.close()                                                  # network drops
            self.assertEqual(set(rig.rec.pending), {"drop-1", "drop-2"})
            self.assertEqual(rig.delete("drop-1"), 200)
            self.assertEqual(rig.delete("drop-2"), 200)
            rig.rec.finish("drop-1", "not resumed")                     # the grace period runs out
            s2 = rig.stream()                                          # the phone resumes the other
            s2.session_start({"session": "drop-2", "started_at": START + 60000, "resumed": True})
            s2.audio(audio(2, b"A2"))
            s2.session_end({"session": "drop-2"})
            self.assertEqual(rig.files(), [])
            self.assertEqual((rig.store.entries, rig.rec.open_sessions, rig.rec.pending), ({}, {}, {}))
            rig.tmp.cleanup()
        run(go())

    def test_deleting_a_summary_keeps_its_commands(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.session_start({"session": "summary-1", "started_at": START})
            s.audio(audio(1, b"A1"))
            s.session_start({"session": "command-1", "started_at": START + 5000, "kind": "command",
                             "parent": "summary-1"})
            s.audio(audio(2, b"C1"))
            self.assertEqual(rig.delete("summary-1"), 200)
            s.audio(audio(3, b"C2"))
            s.session_end({"session": "command-1"})
            self.assertEqual(s.current(), "summary-1")                 # back to the (deleted) parent
            s.audio(audio(4, b"A2"))
            s.session_end({"session": "summary-1"})
            self.assertIsNone(rig.store.get("summary-1"))
            self.assertEqual(rig.store.get("command-1")["status"], "queued")
            self.assertEqual(rig.data("command-1"), b"C1C2")
            stem = os.path.relpath(rig.store.stems["command-1"], rig.tmp.name)
            self.assertEqual(rig.files(), [stem + ".aac", stem + ".json"])
            rig.tmp.cleanup()
        run(go())

    def test_session_end_with_discard(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.session_start({"session": "summary-1", "started_at": START})
            s.audio(audio(1, b"A1"))
            s.session_start({"session": "oops-1", "started_at": START + 5000, "kind": "command",
                             "parent": "summary-1"})
            s.audio(audio(2, b"C1"))
            s.session_end({"session": "oops-1", "reason": "released", "discard": True})
            self.assertIsNone(rig.store.get("oops-1"))
            self.assertTrue(rig.store.is_deleted("oops-1"))
            self.assertEqual(rig.pushes[-1], {"id": "oops-1", "kind": "command", "status": "deleted", "title": None})
            self.assertEqual(s.current(), "summary-1")
            s.audio(audio(3, b"A2"))
            s.session_end({"session": "summary-1"})
            self.assertEqual(rig.data("summary-1"), b"A1A2")
            stem = os.path.relpath(rig.store.stems["summary-1"], rig.tmp.name)
            self.assertEqual(rig.files(), [stem + ".aac", stem + ".json"])   # nothing of the command

            s.session_end({"session": "summary-1", "discard": True})  # after it finished: same as DELETE
            self.assertEqual(rig.files(), [])
            self.assertIsNone(rig.store.get("summary-1"))
            rig.tmp.cleanup()
        run(go())

    def test_resuming_a_deleted_session_saves_nothing(self):
        async def go():
            rig = Rig()
            s = rig.stream()
            s.session_start({"session": "gone-1", "started_at": START})
            s.audio(audio(1, b"A1"))
            s.session_end({"session": "gone-1"})
            self.assertEqual(rig.delete("gone-1"), 200)
            self.assertEqual(rig.files(), [])
            pushes = len(rig.pushes)

            s2 = rig.stream()
            s2.session_start({"session": "gone-1", "started_at": START, "resumed": True})
            s2.audio(audio(2, b"A2"))
            s2.close()                                                 # and drops again
            s3 = rig.stream()
            s3.session_start({"session": "gone-1", "started_at": START, "resumed": True})
            s3.audio(audio(3, b"A3"))
            s3.session_end({"session": "gone-1"})
            self.assertEqual(rig.files(), [])
            self.assertIsNone(rig.store.get("gone-1"))
            self.assertEqual(len(rig.pushes), pushes)                  # nothing new to tell the phone
            self.assertEqual(rig.delete("gone-1"), 200)
            rig.tmp.cleanup()
        run(go())


if __name__ == "__main__":
    unittest.main()
