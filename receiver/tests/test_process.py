import datetime
import os
import tempfile
import time
import unittest
from unittest import mock

import helpers  # noqa: F401
import rtb_process as p
from helpers import Args, adts


def seg(a, b, text="hello there"):
    return {"start": a, "end": b, "text": text}


class SpeakerAssignmentTest(unittest.TestCase):
    def test_max_overlap_wins(self):
        turns = [(0.0, 4.0, 7), (4.0, 10.0, 3)]
        out, n = p.assign_speakers([seg(1, 3), seg(3, 9), seg(3.4, 4.5)], turns)
        # 3..9 overlaps speaker 7 for 1 s and speaker 3 for 5 s; 3.4..4.5 is 0.6 s vs 0.5 s.
        self.assertEqual([s["speaker"] for s in out], ["S1", "S2", "S1"])
        self.assertEqual(n, 2)

    def test_labels_follow_first_appearance(self):
        turns = [(0, 2, 5), (2, 4, 0), (4, 6, 5)]
        out, n = p.assign_speakers([seg(0, 2), seg(2, 4), seg(4, 6)], turns)
        self.assertEqual([s["speaker"] for s in out], ["S1", "S2", "S1"])
        self.assertEqual(n, 2)

    def test_overlap_is_summed_per_speaker(self):
        # Speaker 1 has two short turns inside the segment (2 s total), speaker 2 one 1.5 s turn.
        turns = [(0, 1, 1), (1, 2.5, 2), (2.5, 3.5, 1)]
        out, _ = p.assign_speakers([seg(0, 3.5)], turns)
        self.assertEqual(out[0]["speaker"], "S1")

    def test_no_overlap_takes_nearest_within_gap(self):
        turns = [(0, 1, 4), (10, 11, 9)]
        out, n = p.assign_speakers([seg(1.5, 2.0), seg(5, 6), seg(9.2, 9.8)], turns, max_gap=1.0)
        self.assertEqual([s["speaker"] for s in out], ["S1", None, "S2"])
        self.assertEqual(n, 2)

    def test_tiny_clusters_are_ignored(self):
        # Speaker 8 talks for 0.5 s of 120 s: noise. Its segment goes to the speaker around it.
        turns = [(0, 60, 1), (60, 60.5, 8), (60.5, 120, 2)]
        out, n = p.assign_speakers([seg(10, 20), seg(60, 60.5), seg(70, 80)], turns)
        self.assertEqual([s["speaker"] for s in out], ["S1", "S1", "S2"])
        self.assertEqual(n, 2)
        # In a short recording, a short turn is still a real speaker.
        out, n = p.assign_speakers([seg(0, 4), seg(4, 5)], [(0, 4, 1), (4, 5, 2)])
        self.assertEqual(n, 2)

    def test_no_turns(self):
        out, n = p.assign_speakers([seg(0, 1)], [])
        self.assertEqual((out[0]["speaker"], n), (None, 0))

    def test_keeps_segment_fields(self):
        out, _ = p.assign_speakers([seg(0, 1, "hi")], [(0, 1, 0)])
        self.assertEqual(out[0], {"start": 0, "end": 1, "text": "hi", "speaker": "S1"})


class CleanSegmentsTest(unittest.TestCase):
    def test_drops_silence_filler_and_tags(self):
        segs = [seg(0, 16.9, " Thank you."), seg(17, 18, "[BLANK_AUDIO]"), seg(18, 19, " (music)"),
                seg(19, 20, " ..."), seg(20, 21, " Thank you."), seg(21, 24, "Real words here.")]
        texts = [s["text"] for s in p.clean_segments(segs)]
        self.assertEqual(texts, ["Thank you.", "Real words here."])       # a quick "Thank you." stays

    def test_drops_letterless_and_impossibly_slow_segments(self):
        segs = [seg(0, 2.6, "06 .........."), seg(3, 5, "08, 09 ..."), seg(30.7, 60.7, "Terima kasih telah menonton."),
                seg(61, 63, "It was 2024."), seg(64, 76, "So I think we should wait, maybe a week or two, then decide."),
                seg(80, 115, "I'm going to go ahead and start the video.")]
        self.assertEqual([s["text"] for s in p.clean_segments(segs)],
                         ["It was 2024.", "So I think we should wait, maybe a week or two, then decide."])

    def test_whisper_silence_rule(self):
        s = dict(seg(0, 5, "Something."), no_speech_prob=0.9, avg_logprob=-1.5)
        self.assertEqual(p.clean_segments([s]), [])
        s = dict(seg(0, 5, "Something."), no_speech_prob=0.9, avg_logprob=-0.2)
        self.assertEqual(len(p.clean_segments([s])), 1)

    def test_collapses_loops(self):
        loop = [seg(i, i + 1, "I think it's a good idea.") for i in range(10)]
        out = p.clean_segments([seg(-1, 0, "Start.")] + loop + [seg(20, 21, "End.")])
        self.assertEqual([s["text"] for s in out], ["Start.", "I think it's a good idea.", "End."])
        self.assertEqual(out[1]["end"], 1)                                  # the first copy, as it was
        two = p.clean_segments([seg(0, 1, "Yeah."), seg(1, 2, "Yeah.")])        # people do say it twice
        self.assertEqual(len(two), 2)

    def test_collapses_loops_of_split_sentences(self):
        block = ["So the plan is to move the review to", "Thursday afternoon instead of the", "morning."]
        loop = [seg(i, i + 1, block[i % 3]) for i in range(12)]
        out = p.clean_segments([seg(-2, -1, "Before.")] + loop + [seg(20, 21, "After.")])
        self.assertEqual([s["text"] for s in out], ["Before."] + block + ["After."])
        self.assertEqual(out[3]["end"], 3)

    def test_merge_fragments(self):
        segs = [dict(seg(0, 1, "So we can just"), speaker="S1"), dict(seg(1, 2, "adjust it."), speaker="S1"),
                dict(seg(2, 3, "Right"), speaker="S2"), dict(seg(3.1, 4, "okay."), speaker="S1"),
                dict(seg(9, 10, "Later"), speaker="S1"), dict(seg(12, 13, "on."), speaker="S1")]
        self.assertEqual([(s["text"], s["start"], s["end"]) for s in p.merge_fragments(segs)],
                         [("So we can just adjust it.", 0, 2), ("Right", 2, 3), ("okay.", 3.1, 4),
                          ("Later", 9, 10), ("on.", 12, 13)])

    def test_collapses_repeats_inside_a_segment(self):
        out = p.clean_segments([seg(0, 9, "A quick test. A quick test. A quick test. A quick test. Done.")])
        self.assertEqual(out[0]["text"], "A quick test. A quick test. Done.")


class PackingTest(unittest.TestCase):
    def test_regions_are_padded_merged_and_mapped_back(self):
        chunks = p.pack([(10.0, 12.0), (12.3, 14.0), (100.0, 101.0)], total_s=200, chunk_s=600, pad=0.2, pre=0.2)
        self.assertEqual(chunks, [[(9.8, 14.2), (99.8, 101.2)]])
        pieces = chunks[0]
        self.assertAlmostEqual(p.chunk_time(pieces, 0.0), 9.8)
        self.assertAlmostEqual(p.chunk_time(pieces, 4.4), 99.8)            # a start at the seam
        self.assertAlmostEqual(p.chunk_time(pieces, 4.4, end=True), 14.2)  # an end at the seam
        self.assertAlmostEqual(p.chunk_time(pieces, 5.0), 100.4)

    def test_chunks_respect_the_size(self):
        regions = [(i * 40.0, i * 40.0 + 30) for i in range(10)]          # 10 x 30 s of speech
        chunks = p.pack(regions, total_s=400, chunk_s=100, pad=0, pre=0)
        self.assertEqual([len(c) for c in chunks], [3, 3, 3, 1])

    def test_without_speech_turns(self):
        self.assertEqual(p.pack(None, total_s=1300, chunk_s=600), [[(0, 600)], [(600, 1200)], [(1200, 1300)]])


class LlmHelpersTest(unittest.TestCase):
    def test_language_codes(self):
        self.assertEqual(p._lang_code("english"), "en")
        self.assertEqual(p._lang_code("Javanese"), "jw")
        self.assertEqual(p._lang_code("en"), "en")
        self.assertIsNone(p._lang_code(None))

    def test_parse_reply(self):
        self.assertEqual(p.parse_json_reply('```json\n{"a": 1}\n```'), {"a": 1})
        self.assertEqual(p.parse_json_reply('<think>hmm</think>Sure: {"a": 2} done'), {"a": 2})
        with self.assertRaises(ValueError):
            p.parse_json_reply("no json here")

    def test_transcript_text_groups_turns(self):
        segs = [dict(seg(0, 1, "Hi."), speaker="S1"), dict(seg(1, 2, "How are you?"), speaker="S1"),
                dict(seg(2, 3, "Fine."), speaker="S2")]
        self.assertEqual(p.transcript_text(segs), "S1: Hi. How are you?\nS2: Fine.")
        self.assertEqual(p.transcript_text([dict(seg(0, 1, "Hi."), speaker=None)]), "Hi.")

    def test_split_lines(self):
        text = "\n".join(f"S1: line number {i} " + "word " * 20 for i in range(100))
        parts = p.split_lines(text, budget=300)
        self.assertGreater(len(parts), 5)
        self.assertTrue(all(p.est_tokens(x) <= 300 + 5 for x in parts))
        self.assertEqual("\n".join(parts), text)

    def test_reminder_time(self):
        os.environ["TZ"] = "America/Chicago"                  # DST ends on 2026-11-01
        time.tzset()
        try:
            spoken = datetime.datetime(2026, 10, 28, 15, 40).astimezone()
            r = p.reminder_time
            self.assertEqual(r(spoken, None, None, 20), "2026-10-28T16:00:00-05:00")
            self.assertEqual(r(spoken, "2026-10-29", "15:00", None), "2026-10-29T15:00:00-05:00")
            self.assertEqual(r(spoken, "2026-11-02", "21:30", None), "2026-11-02T21:30:00-06:00")
            self.assertEqual(r(spoken, "2026-10-30", None, None), "2026-10-30T09:00:00-05:00")
            self.assertEqual(r(spoken, None, "20:00", None), "2026-10-28T20:00:00-05:00")   # later today
            self.assertEqual(r(spoken, None, "8:00", None), "2026-10-29T08:00:00-05:00")    # gone: tomorrow
            self.assertIsNone(r(spoken, None, None, None))
            self.assertIsNone(r(spoken, "next week", None, None))
            self.assertIsNone(r(spoken, None, "25:00", None))
        finally:
            os.environ.pop("TZ", None)
            time.tzset()

    def test_preview(self):
        self.assertEqual(p.preview_of("# Title\n- **One** point\n- two"), "Title One point two")
        long = p.preview_of("word " * 100)
        self.assertLessEqual(len(long), 200)
        self.assertTrue(long.endswith("…"))


class CommandPromptTest(unittest.TestCase):
    def command(self, notes):
        """Run command() against a fake LLM; returns (system prompt, result)."""
        prompts = []

        def chat_json(args, system, user, schema, max_tokens=1200):
            prompts.append(system)
            return {"action": "ADD_TO_NOTE", "note": "Groceries", "text": "Milk", "date": None, "time": None,
                    "in_minutes": None}, "m"
        with mock.patch.object(p, "chat_json", chat_json):
            out = p.command(Args(), [seg(0, 2, "Add milk to my grocery list.")], 1761651534000, lambda: None, notes)
        return prompts[0], out

    def test_existing_note_titles_are_in_the_prompt(self):
        system, out = self.command(["Groceries", "Gift ideas for\nSam"])
        self.assertIn("\n\nNotes the user already has. For ADD_TO_NOTE, use one of these titles exactly when it "
                      "is the one meant:\nGroceries\nGift ideas for Sam", system)
        self.assertTrue(system.endswith("Gift ideas for Sam"))
        self.assertEqual((out["action"], out["note"], out["text"]), ("ADD_TO_NOTE", "Groceries", "Milk"))

    def test_no_notes_no_section(self):
        system, _ = self.command(())
        self.assertNotIn("Notes the user already has", system)
        self.assertEqual(system, self.command([])[0])


class AdtsTest(unittest.TestCase):
    def test_duration_and_resync(self):
        with tempfile.NamedTemporaryFile(suffix=".aac", delete=False) as f:
            f.write(adts(50) + b"\x00garbage" + adts(50))
        try:
            self.assertAlmostEqual(p.adts_duration(f.name), 6.4, places=1)        # 100 x 1024 / 16000
        finally:
            os.remove(f.name)


if __name__ == "__main__":
    unittest.main()
