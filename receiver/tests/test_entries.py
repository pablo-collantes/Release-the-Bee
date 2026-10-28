import datetime
import json
import os
import tempfile
import time
import unittest

import helpers  # noqa: F401  (puts receiver/ on the path)
import rtb_entries
from helpers import adts


class EntryStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.changes = []

    def tearDown(self):
        self.tmp.cleanup()

    def store(self):
        s = rtb_entries.EntryStore(self.root, on_change=self.changes.append)
        s.load()
        return s

    def write_aac(self, rel, frames=10, age_s=3600):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(adts(frames))
        t = time.time() - age_s
        os.utime(path, (t, t))
        return path

    def test_create_update_persist(self):
        s = self.store()
        stem = os.path.join(self.root, "dev", "2026-10-28", "101500_abcd1234")
        os.makedirs(os.path.dirname(stem))
        s.create(rtb_entries.new_entry("abcd1234-0000", "dev", kind="command", trigger="hold",
                                       parent="p1", started_at=1000), stem)
        self.assertTrue(s.update("abcd1234-0000", status="queued", ended_at=2000))
        with open(stem + ".json") as f:
            on_disk = json.load(f)
        self.assertEqual(on_disk["status"], "queued")
        self.assertEqual(on_disk["kind"], "command")
        self.assertEqual(on_disk["parent"], "p1")
        self.assertEqual([c["status"] for c in self.changes], ["recording", "queued"])

        s.update("abcd1234-0000", title="x")              # not a status change: no push
        self.assertEqual(len(self.changes), 2)

        open(stem + ".aac", "wb").close()                  # the index is rebuilt from the files
        again = rtb_entries.EntryStore(self.root)
        again.load()
        self.assertEqual(again.get("abcd1234-0000")["title"], "x")
        self.assertIn("abcd1234-0000", again.queue)

    def test_legacy_backfill(self):
        self.write_aac("0123456789abcdef/2026-10-28/123854_0a1b2c3d.aac", frames=100)
        s = self.store()
        e = s.get("123854_0a1b2c3d")
        self.assertIsNotNone(e)
        self.assertEqual(e["kind"], "summary")
        self.assertEqual(e["device"], "0123456789abcdef")
        self.assertEqual(e["status"], "queued")
        expected = datetime.datetime(2026, 10, 28, 12, 38, 54).timestamp() * 1000
        self.assertEqual(e["started_at"], int(expected))
        self.assertAlmostEqual(e["duration_s"], 6.4, places=1)       # 100 frames x 64 ms
        self.assertEqual(e["ended_at"], int(expected) + 6400)
        self.assertEqual(e["audio"], {"bytes": 100 * 27, "mime": "audio/aac"})
        self.assertTrue(os.path.exists(os.path.join(self.root, "0123456789abcdef/2026-10-28/123854_0a1b2c3d.json")))

    def test_files_that_are_not_recordings_are_ignored(self):
        self.write_aac("somewhere/else.aac")
        self.assertEqual(self.store().entries, {})

    def test_recent_legacy_file_waits_for_a_resume(self):
        self.write_aac("dev/2026-10-28/150000_aaaa1111.aac", age_s=5)
        s = rtb_entries.EntryStore(self.root)
        unfinished = s.load(recent_s=600)
        self.assertEqual(unfinished, ["150000_aaaa1111"])
        self.assertEqual(s.get("150000_aaaa1111")["status"], "recording")
        self.assertNotIn("150000_aaaa1111", s.queue)

    def test_interrupted_processing_is_queued_again(self):
        path = self.write_aac("dev/2026-10-28/090000_bbbb2222.aac")
        e = rtb_entries.new_entry("uuid-b", "dev", started_at=1)
        e["status"] = "diarizing"
        with open(path[:-4] + ".json", "w") as f:
            json.dump(e, f)
        s = self.store()
        self.assertEqual(s.get("uuid-b")["status"], "queued")
        self.assertIn("uuid-b", s.queue)

    def test_list_is_light_newest_first_and_since(self):
        s = self.store()
        for i, started in enumerate([3000, 1000, 2000]):
            stem = os.path.join(self.root, f"e{i}")
            s.create(rtb_entries.new_entry(f"e{i}", "dev", started_at=started), stem)
            s.update(f"e{i}", status="done", transcript={"segments": []}, summary={"title": "t"})
        self.assertEqual([e["id"] for e in s.list()], ["e0", "e2", "e1"])
        self.assertTrue(all(e["transcript"] is None and e["summary"] is None and e["command"] is None
                            for e in s.list()))
        self.assertIsNotNone(s.get("e0")["transcript"])                # the full entry keeps them
        mark = rtb_entries.now_ms() + 1
        time.sleep(0.005)
        s.update("e1", status="failed", error="boom")
        self.assertEqual([e["id"] for e in s.list(since=mark)], ["e1"])

    def test_stale_worker_updates_are_dropped(self):
        s = self.store()
        s.create(rtb_entries.new_entry("x", "dev", started_at=1), os.path.join(self.root, "x"))
        s.update("x", status="queued")
        job = s.next_job(timeout=0)
        self.assertEqual(job[0], "x")
        gen = job[1]
        self.assertTrue(s.reprocess("x"))                              # user asks again mid-run
        self.assertFalse(s.update("x", gen, status="done"))
        self.assertEqual(s.get("x")["status"], "queued")

    def test_reprocess_refuses_while_recording(self):
        s = self.store()
        s.create(rtb_entries.new_entry("r", "dev", started_at=1), os.path.join(self.root, "r"))
        self.assertFalse(s.reprocess("r"))
        s.update("r", status="done")
        self.assertTrue(s.reprocess("r"))
        self.assertIn("r", s.queue)

    def test_jobs_come_oldest_first(self):
        s = self.store()
        for id, started in [("late", 300), ("early", 100), ("mid", 200)]:
            s.create(rtb_entries.new_entry(id, "dev", started_at=started), os.path.join(self.root, id))
            s.update(id, status="queued")
        order = [s.next_job(timeout=0)[0] for _ in range(3)]
        self.assertEqual(order, ["early", "mid", "late"])
        self.assertIsNone(s.next_job(timeout=0))

    def test_rekey(self):
        self.write_aac("dev/2026-10-28/150000_aaaa1111.aac")
        s = self.store()
        s.rekey("150000_aaaa1111", "aaaa1111-full-uuid")
        self.assertIsNone(s.get("150000_aaaa1111"))
        self.assertEqual(s.get("aaaa1111-full-uuid")["id"], "aaaa1111-full-uuid")
        again = self.store()
        self.assertIsNotNone(again.get("aaaa1111-full-uuid"))


if __name__ == "__main__":
    unittest.main()
