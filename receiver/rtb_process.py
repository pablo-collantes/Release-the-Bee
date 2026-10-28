#!/usr/bin/env python3
"""
rtb_process.py: turn one finished recording into a transcript, speakers, and a summary
or a command. docs/SERVER_API.md describes the result.

    .aac ─ ffmpeg ─▶ 16 kHz mono, high-pass 80 Hz ─ diarization ─▶ who speaks when
         ─ normalize, keep only speech ─ whisper ─▶ segments with S1, S2…
         ─ LLM ─▶ summary | command

rtb_receiver.py calls process() for one recording at a time. Every step is optional: a
step whose tool isn't configured or installed is skipped, and the entry finishes with
what it has. Standard library only, except diarization, which needs sherpa-onnx
(pip install sherpa-onnx). Those run in a child process, so a long diarization never
holds up the receiver.

Try it on one file (prints the result as JSON):

  python3 receiver/rtb_process.py recordings/<device>/<date>/<file>.aac \\
      --whisper-url http://127.0.0.1:8009 --sherpa-dir ~/models/sherpa-onnx-diarization \\
      --llm-url http://127.0.0.1:8742/v1
"""
import argparse
import bisect
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import wave

RATE = 16000
CLEAN_FILTER = "highpass=f=80"
# dynaudnorm evens out loudness over ~15 s windows, so quiet speakers reach the same
# level as the wearer. Applied to what whisper hears, not to what diarization hears.
NORMALIZE_FILTER = "dynaudnorm=f=500:g=31:p=0.9:m=20"
CHUNK_S = 600                   # whisper request size: the server is shared, keep turns short
PAD_S = 1.0                     # audio kept after each speech turn...
PRE_PAD_S = 2.0                 # ...and before it, so whisper hears how a sentence starts
ADTS_RATES = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]


class Cancelled(Exception):
    """The entry changed under us (reprocess, or the phone resumed the recording)."""


def log(msg):
    print(f"{datetime.datetime.now():%H:%M:%S}  {msg}", file=sys.stderr, flush=True)


# --------------------------------------------------------------------------- configuration

def add_arguments(ap):
    """Processing flags, shared by rtb_receiver.py and this file's own CLI."""
    g = ap.add_argument_group("processing (each step is skipped if not configured)")
    g.add_argument("--ffmpeg", default="ffmpeg", help="ffmpeg binary (default: ffmpeg on PATH)")
    g.add_argument("--whisper-url", help="whisper.cpp server, e.g. http://127.0.0.1:8009")
    g.add_argument("--whisper-cli", help="whisper.cpp's whisper-cli, used if there's no --whisper-url")
    g.add_argument("--whisper-model", help="ggml model for --whisper-cli")
    g.add_argument("--language", default="auto", help="spoken language, e.g. en (default: detect)")
    g.add_argument("--sherpa-dir", help="sherpa-onnx models for diarization (see README)")
    g.add_argument("--speaker-model", default="wespeaker_en_voxceleb_resnet34_LM.onnx",
                   help="speaker-embedding model inside --sherpa-dir")
    g.add_argument("--speakers", type=int, default=0, help="number of speakers if you know it (default: estimate)")
    g.add_argument("--speaker-threshold", type=float, default=0.6,
                   help="clustering threshold: higher merges more voices into one speaker (default 0.6)")
    g.add_argument("--llm-url", help="OpenAI-compatible endpoint, e.g. http://127.0.0.1:8742/v1")
    g.add_argument("--llm-model", default="gemma-4-e4b", help="model name sent to --llm-url")
    g.add_argument("--llm-key", default=os.environ.get("RTB_LLM_KEY"), help="API key for --llm-url (or RTB_LLM_KEY)")
    g.add_argument("--llm-context", type=int, default=32768,
                   help="the LLM's context in tokens; longer transcripts are summarized in parts")
    g.add_argument("--llm-chunk", type=int, default=8000,
                   help="transcripts longer than this many tokens are summarized in parts, then merged "
                        "(default 8000: a small model covers a long meeting more evenly that way)")
    return ap


def tools(args):
    """What's available right now, as a dict of step -> bool."""
    ffmpeg = shutil.which(args.ffmpeg) is not None
    cli = bool(args.whisper_cli and args.whisper_model and shutil.which(args.whisper_cli))
    return {
        "ffmpeg": ffmpeg,
        "whisper": ffmpeg and bool(args.whisper_url or cli),
        "diarize": ffmpeg and _sherpa_ok() and bool(_diarize_models(args)),
        "llm": bool(args.llm_url),
    }


_SHERPA = None


def _sherpa_ok():
    """sherpa-onnx is imported only in a child process; check once that it would work."""
    global _SHERPA
    if _SHERPA is None:
        r = subprocess.run([sys.executable, "-c", "import sherpa_onnx, numpy"], capture_output=True)
        _SHERPA = r.returncode == 0
    return _SHERPA


def _diarize_models(args):
    if not args.sherpa_dir:
        return None
    d = os.path.expanduser(args.sherpa_dir)
    seg = os.path.join(d, "sherpa-onnx-pyannote-segmentation-3-0", "model.onnx")
    emb = os.path.join(d, args.speaker_model)
    return (seg, emb) if os.path.isfile(seg) and os.path.isfile(emb) else None


# --------------------------------------------------------------------------- audio

def adts_duration(path):
    """Seconds of audio in an ADTS .aac file, from its frame headers (no decoding)."""
    with open(path, "rb") as f:
        data = f.read()
    i, frames, rate = 0, 0, RATE
    n = len(data)
    while i + 7 <= n:
        if data[i] == 0xFF and (data[i + 1] & 0xF6) == 0xF0:
            length = ((data[i + 3] & 3) << 11) | (data[i + 4] << 3) | (data[i + 5] >> 5)
            if length >= 7:
                rate = ADTS_RATES[(data[i + 2] >> 2) & 0xF] if (data[i + 2] >> 2) & 0xF < 13 else rate
                frames += (data[i + 6] & 3) + 1
                i += length
                continue
        i += 1                                          # resync after a damaged frame
    return round(frames * 1024 / rate, 1)


def ffmpeg(args, *argv):
    r = subprocess.run([args.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *argv],
                       capture_output=True, text=True)
    # A recording cut mid-frame makes the AAC decoder complain about its last packet.
    # The output is fine; only fail if nothing was written.
    if r.returncode != 0 and not os.path.exists(argv[-1]):
        raise RuntimeError(f"ffmpeg failed: {r.stderr.strip()[-300:]}")


def remux(args, aac, m4a):
    """ADTS → MP4 container without re-encoding, so players can seek."""
    tmp = m4a + ".tmp"
    ffmpeg(args, "-i", aac, "-c", "copy", "-movflags", "+faststart", "-f", "mp4", tmp)
    os.replace(tmp, m4a)


def read_wav(path):
    with wave.open(path, "rb") as w:
        return w.readframes(w.getnframes())


def write_wav(path, pcm):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)


# --------------------------------------------------------------------------- speech-only chunks

def pack(regions, total_s, chunk_s=CHUNK_S, pad=PAD_S, pre=PRE_PAD_S):
    """Speech regions → whisper requests. Each chunk is a list of (src_start, src_end)
    pieces that are concatenated; silence between them is cut out. Without regions,
    the whole recording is cut every chunk_s seconds."""
    if regions is None:
        return [[(t, min(t + chunk_s, total_s))] for t in range(0, int(total_s + 0.999), chunk_s)] or [[(0, total_s)]]
    merged = []
    for a, b in sorted(regions):
        a, b = max(0.0, a - pre), min(total_s, b + pad)
        if merged and a <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], b)
        else:
            merged.append([a, b])
    chunks, cur, cur_len = [], [], 0.0
    for a, b in merged:
        if cur and cur_len + (b - a) > chunk_s:
            chunks.append(cur)
            cur, cur_len = [], 0.0
        cur.append((a, b))
        cur_len += b - a
    if cur:
        chunks.append(cur)
    return chunks


def chunk_time(pieces, t, end=False):
    """Map a time inside a packed chunk back to the original recording. At the seam
    between two pieces a start belongs to the next piece and an end to the previous."""
    off = 0.0
    for a, b in pieces:
        n = b - a
        if t < off + n or (end and t <= off + n + 1e-6):
            return a + max(0.0, t - off)
        off += n
    return pieces[-1][1]


# --------------------------------------------------------------------------- whisper

def _multipart(fields, wav_bytes):
    boundary = "rtb" + uuid.uuid4().hex
    out = bytearray()
    for k, v in fields.items():
        out += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    out += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="audio.wav"\r\n'
            "Content-Type: audio/wav\r\n\r\n").encode()
    out += wav_bytes + f"\r\n--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


# Decoding options that keep whisper honest on long, quiet wearable audio:
# max_context=0 stops it from conditioning on its own previous text, which is what
# turns one misheard line into "I think it's a good idea." x131 (seen on real audio).
WHISPER_FIELDS = {
    "response_format": "verbose_json", "temperature": "0", "temperature_inc": "0.2",
    "max_context": "0", "suppress_nst": "true", "no_speech_thold": "0.6", "logprob_thold": "-1.0",
    "split_on_word": "true", "no_language_probabilities": "true",
}


def whisper_server(args, wav_path, language):
    url = args.whisper_url.rstrip("/")
    if not url.endswith("/inference"):
        url += "/inference"
    fields = dict(WHISPER_FIELDS, language=language)
    body, ctype = _multipart(fields, open(wav_path, "rb").read())
    req = urllib.request.Request(url, data=body, headers={"Content-Type": ctype})
    res = _retry(lambda: json.load(urllib.request.urlopen(req, timeout=1800)), "whisper")
    segs = [{"start": s.get("start", 0.0), "end": s.get("end", 0.0), "text": s.get("text", ""),
             "avg_logprob": s.get("avg_logprob"), "no_speech_prob": s.get("no_speech_prob")}
            for s in res.get("segments", [])]
    return segs, _lang_code(res.get("language"))


def whisper_cli(args, wav_path, language):
    base = wav_path[:-4]
    cmd = [args.whisper_cli, "-m", args.whisper_model, "-f", wav_path, "-oj", "-of", base, "-np",
           "-l", language, "-mc", "0", "-sns", "-nth", "0.6", "-lpt", "-1.0", "-sow"]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"whisper-cli failed: {r.stderr.strip()[-300:]}")
    with open(base + ".json", encoding="utf-8") as f:
        res = json.load(f)
    segs = [{"start": t["offsets"]["from"] / 1000, "end": t["offsets"]["to"] / 1000, "text": t["text"]}
            for t in res.get("transcription", [])]
    return segs, _lang_code(res.get("result", {}).get("language"))


# whisper.cpp reports the language by name; entries use its code.
_LANGS = dict(reversed(pair.split(":")) for pair in (
    "en:english,zh:chinese,de:german,es:spanish,ru:russian,ko:korean,fr:french,ja:japanese,pt:portuguese,"
    "tr:turkish,pl:polish,ca:catalan,nl:dutch,ar:arabic,sv:swedish,it:italian,id:indonesian,hi:hindi,"
    "fi:finnish,vi:vietnamese,he:hebrew,uk:ukrainian,el:greek,ms:malay,cs:czech,ro:romanian,da:danish,"
    "hu:hungarian,ta:tamil,no:norwegian,th:thai,ur:urdu,hr:croatian,bg:bulgarian,lt:lithuanian,la:latin,"
    "mi:maori,ml:malayalam,cy:welsh,sk:slovak,te:telugu,fa:persian,lv:latvian,bn:bengali,sr:serbian,"
    "az:azerbaijani,sl:slovenian,kn:kannada,et:estonian,mk:macedonian,br:breton,eu:basque,is:icelandic,"
    "hy:armenian,ne:nepali,mn:mongolian,bs:bosnian,kk:kazakh,sq:albanian,sw:swahili,gl:galician,mr:marathi,"
    "pa:punjabi,si:sinhala,km:khmer,sn:shona,yo:yoruba,so:somali,af:afrikaans,oc:occitan,ka:georgian,"
    "be:belarusian,tg:tajik,sd:sindhi,gu:gujarati,am:amharic,yi:yiddish,lo:lao,uz:uzbek,fo:faroese,"
    "ht:haitian creole,ps:pashto,tk:turkmen,nn:nynorsk,mt:maltese,sa:sanskrit,lb:luxembourgish,my:myanmar,"
    "bo:tibetan,tl:tagalog,mg:malagasy,as:assamese,tt:tatar,haw:hawaiian,ln:lingala,ha:hausa,ba:bashkir,"
    "jw:javanese,su:sundanese,yue:cantonese").split(","))


def _lang_code(lang):
    if not lang:
        return None
    return _LANGS.get(lang.lower(), lang.lower())


def transcribe(args, norm_wav, regions, total_s, check):
    """Whisper over the speech regions (or everything), in requests of at most CHUNK_S.
    Returns (segments, language) with times in the original recording."""
    pcm = read_wav(norm_wav)
    run = whisper_server if args.whisper_url else whisper_cli
    language = args.language or "auto"
    out, detected = [], None
    tmp = os.path.dirname(norm_wav)
    for n, pieces in enumerate(pack(regions, total_s)):
        check()
        part = b"".join(pcm[int(a * RATE) * 2: int(b * RATE) * 2] for a, b in pieces)
        if len(part) < RATE // 2:                                    # < 0.25 s: nothing to hear
            continue
        path = os.path.join(tmp, f"chunk{n}.wav")
        write_wav(path, part)
        segs, lang = run(args, path, language)
        os.remove(path)
        if language == "auto" and lang:
            language = detected = lang             # keep later chunks in the first chunk's language
        for s in segs:
            a, b = s["start"], s["end"]
            s["start"] = round(chunk_time(pieces, a), 2)
            end = chunk_time(pieces, max(a, b), end=True)
            if end - s["start"] > (b - a) + 0.5:          # it ran across a cut: don't span the silence
                end = s["start"] + (b - a)
            s["end"] = round(max(s["start"], end), 2)
            out.append(s)
    return clean_segments(out), detected or (None if language == "auto" else language)


# What whisper says over silence and noise (it learned these from subtitles).
SILENCE_PHRASES = {
    "thank you", "thank you very much", "thanks for watching", "thank you for watching",
    "thank you so much for watching", "thanks for watching and see you next time",
    "please subscribe", "like and subscribe", "you", "bye", "subtitles by the amaraorg community",
}
_TAG = re.compile(r"^[\[\(\*♪].*[\]\)\*♪]$")             # [BLANK_AUDIO], (music), *sigh*, ♪ ♪


def _norm(text):
    return re.sub(r"[^a-z0-9 ]", "", text.lower()).strip()


def clean_segments(segs):
    """Drop whisper's usual hallucinations: tags, low-confidence segments it thought were
    silence, subtitle filler stretched over a long pause, and repetition loops."""
    out = []
    for s in segs:
        text = " ".join(s.get("text", "").split())
        if not re.search(r"[^\W\d_]", text) or _TAG.match(text):
            continue                                            # no letters: "06 ........"
        nsp, lp = s.get("no_speech_prob"), s.get("avg_logprob")
        if nsp is not None and lp is not None and nsp > 0.6 and lp < -1.0:
            continue                                            # whisper's own "this was silence" rule
        words = len(text.split())
        dur = s["end"] - s["start"]
        if _norm(text) in SILENCE_PHRASES and dur > 2.0 and words / dur < 0.75:
            continue                                            # "Thank you." spread over 15 s of nothing
        if dur > 10 and words / dur < 0.3:
            continue                                            # same, in any language (people say 2-3 words/s)
        text = _collapse_repeats(text)
        out.append({"start": s["start"], "end": s["end"], "text": text})
    return _drop_loops(out)


def _drop_loops(segs, max_period=4):
    """A block of 1-4 segments repeated 3+ times in a row is a decoding loop, not someone
    repeating themselves (whisper's short segments split one looping sentence into
    several). Keep the first copy."""
    keys = [_norm(s["text"]) for s in segs]
    out, i, n = [], 0, len(segs)
    while i < n:
        for p in range(1, max_period + 1):
            r = 1
            while i + (r + 1) * p <= n and keys[i + r * p: i + (r + 1) * p] == keys[i: i + p]:
                r += 1
            if r >= 3:
                out += segs[i: i + p]
                i += r * p
                break
        else:
            out.append(segs[i])
            i += 1
    return out


def merge_fragments(segments, max_chars=300):
    """Join pieces of one sentence by the same speaker into one segment for reading."""
    out = []
    for s in segments:
        prev = out[-1] if out else None
        if (prev and prev.get("speaker") == s.get("speaker") and s["start"] - prev["end"] < 1.0
                and not re.search(r"[.?!…]$", prev["text"]) and len(prev["text"]) + len(s["text"]) < max_chars):
            prev["text"] = f"{prev['text']} {s['text']}"
            prev["end"] = s["end"]
        else:
            out.append(dict(s))
    return out


def _collapse_repeats(text):
    """'Test. Test. Test. Test.' → 'Test.' (3+ identical sentences in a row)."""
    parts = re.split(r"(?<=[.!?])\s+", text)
    out = []
    for p in parts:
        if len(out) >= 2 and _norm(out[-1]) == _norm(p) == _norm(out[-2]):
            continue
        out.append(p)
    return " ".join(out)


# --------------------------------------------------------------------------- sherpa-onnx (child process)

def _sherpa(args, *argv):
    """Run this file in a child process for a sherpa-onnx step; returns its JSON output."""
    cmd = [sys.executable, os.path.abspath(__file__), *argv]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"{argv[0]} failed: {r.stderr.strip()[-300:]}")
    return json.loads(r.stdout)


def _threads():
    return max(1, min(16, (os.cpu_count() or 2) // 2))


def _load_float(path):
    import numpy as np
    return np.frombuffer(read_wav(path), dtype=np.int16).astype(np.float32) / 32768.0


def _child_diarize(wav, seg_model, emb_model, speakers, threshold):
    import sherpa_onnx
    cfg = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=seg_model),
            num_threads=_threads()),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=emb_model, num_threads=_threads()),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=speakers if speakers > 0 else -1,
                                                    threshold=threshold),
        min_duration_on=0.3, min_duration_off=0.5)
    sd = sherpa_onnx.OfflineSpeakerDiarization(cfg)
    result = sd.process(_load_float(wav)).sort_by_start_time()
    return [(round(r.start, 3), round(r.end, 3), r.speaker) for r in result]


def assign_speakers(segments, turns, max_gap=1.0):
    """Give each segment the diarization speaker it overlaps most. A segment that overlaps
    no turn takes the nearest one within max_gap seconds. Speakers are renamed S1, S2, …
    in order of first appearance. Returns (segments, number of speakers).

    Clustering also leaves a few "speakers" that talk for a second or two in total
    (a cough, a door, one misheard turn). Those turns are ignored, so their segments go
    to whoever is speaking around them."""
    talk = {}
    for ts, te, spk in turns:
        talk[spk] = talk.get(spk, 0.0) + (te - ts)
    total = sum(talk.values())
    turns = sorted(t for t in turns if talk[t[2]] >= min(10.0, 0.02 * total))
    starts = [t[0] for t in turns]
    longest = max((t[1] - t[0] for t in turns), default=0)
    names, out = {}, []
    for s in segments:
        a, b = s["start"], s["end"]
        lo = bisect.bisect_left(starts, a - longest - max_gap)
        hi = bisect.bisect_right(starts, b + max_gap)
        overlap, nearest = {}, (None, None)
        for ts, te, spk in turns[lo:hi]:
            ov = min(b, te) - max(a, ts)
            if ov > 0:
                overlap[spk] = overlap.get(spk, 0.0) + ov
            elif -ov <= max_gap and (nearest[0] is None or -ov < nearest[0]):
                nearest = (-ov, spk)                    # on a tie the earlier turn wins
        if overlap:
            spk = max(overlap, key=lambda k: (overlap[k], -k if isinstance(k, int) else 0))
        else:
            spk = nearest[1]
        label = None
        if spk is not None:
            label = names.setdefault(spk, f"S{len(names) + 1}")
        out.append(dict(s, speaker=label))
    return out, len(names)


# --------------------------------------------------------------------------- LLM

SUMMARY_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "summary": {"type": "string"},
        "action_items": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["title", "summary", "action_items"],
    "additionalProperties": False,
}

NOTES_SCHEMA = {
    "type": "object",
    "properties": {
        "notes": {"type": "array", "items": {"type": "string"}},
        "action_items": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["notes", "action_items"],
    "additionalProperties": False,
}

ACTIONS = ["CREATE_NOTE", "ADD_TO_NOTE", "CREATE_REMINDER", "UNKNOWN"]
# The model only extracts the parts of a reminder's time; the code does the date
# arithmetic. Small models pick the right day from a calendar but miscount "in 20 minutes"
# or "Friday" when asked for an ISO timestamp (seen with Gemma E4B).
COMMAND_SCHEMA = {
    "type": "object",
    "properties": {
        "action": {"type": "string", "enum": ACTIONS},
        "note": {"type": ["string", "null"]},
        "text": {"type": ["string", "null"]},
        "date": {"type": ["string", "null"]},
        "time": {"type": ["string", "null"]},
        "in_minutes": {"type": ["integer", "null"]},
    },
    "required": ["action", "note", "text", "date", "time", "in_minutes"],
    "additionalProperties": False,
}

SUMMARY_PROMPT = """You write notes about a recording from a wearable microphone. The \
transcript comes from speech recognition: expect misheard words. Speakers are labelled \
S1, S2, … when known; the labels can be wrong.

Return JSON with:
- "title": 3 to 8 words naming the main topic. No quotes, no date.
- "summary": Markdown, at most about 200 words. Start with one sentence saying what \
the recording is (a meeting, a call, a lecture, a chat, music or TV in the background…) and its main topic. Then 3 to \
7 bullet points with the key points, decisions and open questions, most important \
first. Use names if people are named, otherwise the speaker labels.
- "action_items": tasks someone agreed to do or was asked to do, each written as \
"Who: what (by when)". Leave out "by when" if no time was said. [] if there are none.

Only write what the transcript supports. Don't invent names, numbers or dates."""

NOTES_PROMPT = """You take notes on one part of a long recording from a wearable \
microphone. The transcript comes from speech recognition: expect misheard words. \
Speakers are labelled S1, S2, … when known.

Return JSON with:
- "notes": 3 to 8 short bullet points covering the topics, facts, decisions and open \
questions in this part. Use names if people are named, otherwise the speaker labels.
- "action_items": tasks someone agreed to do or was asked to do in this part, each \
written as "Who: what (by when)". [] if there are none.

Only write what the transcript supports."""

MERGE_PROMPT = """You write notes about a long recording from a wearable microphone. You \
get notes taken on each part of it, in order.

Return JSON with:
- "title": 3 to 8 words naming the main topic of the whole recording. No quotes, no date.
- "summary": Markdown, at most about 250 words. Start with one sentence saying what the \
recording is (a meeting, a call, a lecture, a chat, music or TV in the background…) and its main topic. Then 4 to 8 \
bullet points with the key points, decisions and open questions of the whole \
recording, most important first. Merge repeated points.
- "action_items": every distinct action item from the parts, merged when they are the \
same task, each "Who: what (by when)". [] if there are none.

Only write what the notes support."""

COMMAND_PROMPT = """You turn a short voice command into one action for a phone app. The \
transcript comes from speech recognition: expect misheard words.

It was spoken on {now}. The coming week:
{calendar}

Actions:
- CREATE_NOTE: make a new note. "note": a short title for it. "text": what to write in it.
- ADD_TO_NOTE: add to an existing note or list. "note": the title of the note the user \
named. "text": what to add.
- CREATE_REMINDER: remind the user. "text": what to remind them about. For the time, \
either "in_minutes" ("in 20 minutes" is 20, "in two hours" is 120), or "date" \
(YYYY-MM-DD, copied from the list above) and "time" (HH:MM, 24-hour: "3" or "3 pm" is \
15:00, "morning" is 09:00, "noon" is 12:00, "afternoon" is 15:00, "evening" is 19:00, \
"tonight" is 20:00). Leave out what the user didn't say: null.
- UNKNOWN: anything else, or speech that isn't a command. "text": the transcript.

Fields that don't apply to the action are null. Keep the user's wording in "text", \
without the command words ("remind me to", "add to my … note").

Examples:
"Remind me tomorrow at 3 to call Ana about the invoice." → \
{{"action": "CREATE_REMINDER", "note": null, "text": "Call Ana about the invoice", "date": "{tomorrow}", "time": "15:00", "in_minutes": null}}
"Add milk and eggs to my shopping list." → \
{{"action": "ADD_TO_NOTE", "note": "Shopping list", "text": "Milk and eggs", "date": null, "time": null, "in_minutes": null}}
"New note: gift ideas for Sam, a climbing chalk bag." → \
{{"action": "CREATE_NOTE", "note": "Gift ideas for Sam", "text": "A climbing chalk bag", "date": null, "time": null, "in_minutes": null}}
"Remind me in two hours to move the car." → \
{{"action": "CREATE_REMINDER", "note": null, "text": "Move the car", "date": null, "time": null, "in_minutes": 120}}"""


def _post_json(url, body, key, timeout=900):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers)
    return json.load(urllib.request.urlopen(req, timeout=timeout))


def _retry(fn, what, tries=4):
    """Retry a call to a local server that may be restarting (connection refused)."""
    delay = 10
    for attempt in range(tries):
        try:
            return fn()
        except urllib.error.HTTPError:
            raise
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            if attempt == tries - 1:
                raise RuntimeError(f"{what}: {getattr(e, 'reason', e)}") from e
            log(f"{what} unreachable ({getattr(e, 'reason', e)}), retrying in {delay} s")
            time.sleep(delay)
            delay *= 3


_FORMAT_MODE = {}           # llm url -> response_format style the server accepted


def chat_json(args, system, user, schema, max_tokens=1200):
    """One chat completion that must return a JSON object. Uses JSON-schema constrained
    output when the server supports it (llama.cpp, LM Studio, vLLM, recent Ollama), and
    falls back to plain JSON mode, then to parsing the reply."""
    url = args.llm_url.rstrip("/")
    if not url.endswith("/chat/completions"):
        url += "/chat/completions"
    body = {"model": args.llm_model, "temperature": 0.2, "max_tokens": max_tokens,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
    modes = ["schema", "json", "none"]
    if url in _FORMAT_MODE:
        modes = modes[modes.index(_FORMAT_MODE[url]):]
    last = None
    for mode in modes:
        if mode == "schema":
            body["response_format"] = {"type": "json_schema",
                                       "json_schema": {"name": "result", "strict": True, "schema": schema}}
        elif mode == "json":
            body["response_format"] = {"type": "json_object"}
        else:
            body.pop("response_format", None)
        try:
            res = _retry(lambda: _post_json(url, body, args.llm_key), "llm")
        except urllib.error.HTTPError as e:
            if e.code in (400, 422, 500) and mode != "none":
                last = e
                continue                                # response_format not supported: try the next
            raise RuntimeError(f"llm: HTTP {e.code} {e.read()[:200]!r}") from e
        _FORMAT_MODE[url] = mode
        content = res["choices"][0]["message"].get("content") or ""
        try:
            return parse_json_reply(content), res.get("model") or args.llm_model
        except ValueError as e:
            last = e
            if res["choices"][0].get("finish_reason") == "length":
                body["max_tokens"] = max_tokens * 2
            continue
    raise RuntimeError(f"llm: no usable JSON ({last})")


def parse_json_reply(text):
    """The JSON object in a model reply, tolerating thinking tags and code fences."""
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text).strip()
    try:
        obj = json.loads(text)
    except json.JSONDecodeError:
        a, b = text.find("{"), text.rfind("}")
        if a < 0 or b <= a:
            raise ValueError("no JSON object in reply")
        try:
            obj = json.loads(text[a:b + 1])
        except json.JSONDecodeError as e:
            raise ValueError(f"bad JSON: {e}")
    if not isinstance(obj, dict):
        raise ValueError("reply is not a JSON object")
    return obj


def transcript_text(segments):
    """Segments → one line per speaker turn ("S1: …"), the cheapest form for the LLM."""
    lines, cur, buf = [], object(), []
    for s in segments:
        spk = s.get("speaker")
        if spk != cur and buf:
            lines.append((f"{cur}: " if cur else "") + " ".join(buf))
            buf = []
        cur = spk
        buf.append(s["text"])
    if buf:
        lines.append((f"{cur}: " if cur else "") + " ".join(buf))
    return "\n".join(lines)


def est_tokens(text):
    return len(text) // 3 + 1                  # conservative: English runs ~4 characters a token


def split_lines(text, budget):
    """Cut a transcript into parts of at most `budget` tokens, at line breaks."""
    parts, cur, size = [], [], 0
    for line in text.split("\n"):
        n = est_tokens(line) + 1
        while n > budget:                                     # one enormous turn: cut it
            cut = budget * 3
            if cur:
                parts.append("\n".join(cur))
                cur, size = [], 0
            parts.append(line[:cut])
            line = line[cut:]
            n = est_tokens(line) + 1
        if cur and size + n > budget:
            parts.append("\n".join(cur))
            cur, size = [], 0
        cur.append(line)
        size += n
    if cur:
        parts.append("\n".join(cur))
    return parts


def summarize(args, segments, check):
    text = transcript_text(segments)
    room = args.llm_context - 2500                           # prompt + answer
    budget = min(args.llm_chunk, room) if args.llm_chunk > 0 else room
    if est_tokens(text) <= budget:
        out, model = chat_json(args, SUMMARY_PROMPT, "Transcript:\n" + text, SUMMARY_SCHEMA)
    else:
        # Too long for one request: notes per part, then one summary of the notes.
        parts = split_lines(text, budget)
        notes, actions = [], []
        for i, part in enumerate(parts, 1):
            check()
            log(f"summary: part {i}/{len(parts)}")
            res, model = chat_json(args, NOTES_PROMPT, f"Part {i} of {len(parts)}:\n{part}", NOTES_SCHEMA)
            notes.append(f"Part {i}:\n" + "\n".join(f"- {n}" for n in _strings(res.get("notes"))))
            actions += _strings(res.get("action_items"))
        merged = "\n\n".join(notes)
        if actions:
            merged += "\n\nAction items found in the parts:\n" + "\n".join(f"- {a}" for a in actions)
        while est_tokens(merged) > room:                    # absurdly long: keep the newest notes
            merged = merged[len(merged) // 4:]
        check()
        out, model = chat_json(args, MERGE_PROMPT, merged, SUMMARY_SCHEMA, max_tokens=1500)
    return {
        "title": str(out.get("title") or "").strip().strip('"') or None,
        "summary": str(out.get("summary") or "").strip(),
        "action_items": _strings(out.get("action_items")),
        "model": model,
    }


def _strings(v):
    return [str(x).strip() for x in v if str(x).strip()] if isinstance(v, list) else []


def command(args, segments, started_at_ms, check):
    spoken = datetime.datetime.fromtimestamp(started_at_ms / 1000).astimezone()
    # One week only: listing two made Gemma E4B pick the later Monday for "next Monday".
    days = [spoken + datetime.timedelta(days=i) for i in range(8)]
    calendar = "\n".join(f"{d:%A} {d:%Y-%m-%d}" + (" (today)" if i == 0 else " (tomorrow)" if i == 1 else "")
                         for i, d in enumerate(days))
    system = COMMAND_PROMPT.format(now=f"{spoken:%A %Y-%m-%d %H:%M} ({spoken.tzname()}, UTC{spoken:%z})",
                                   calendar=calendar, tomorrow=f"{days[1]:%Y-%m-%d}")
    text = " ".join(s["text"] for s in segments)
    check()
    out, model = chat_json(args, system, f"Command: {text}", COMMAND_SCHEMA, max_tokens=400)
    action = out.get("action") if out.get("action") in ACTIONS else "UNKNOWN"
    result = {"action": action, "note": _str_or_none(out.get("note")),
              "text": _str_or_none(out.get("text")) or text, "when": None, "model": model}
    if action == "CREATE_REMINDER":
        result["when"] = reminder_time(spoken, out.get("date"), out.get("time"), out.get("in_minutes"))
    if action not in ("CREATE_NOTE", "ADD_TO_NOTE"):
        result["note"] = None
    return result


def reminder_time(spoken, date, time_of_day, in_minutes):
    """ISO 8601 with offset from the parts the model extracted, or None. A time without a
    date is the next time it comes round; a date without a time is 09:00."""
    try:
        if in_minutes not in (None, ""):
            t = spoken + datetime.timedelta(minutes=int(in_minutes))
            return t.replace(second=0, microsecond=0).isoformat()
        hm = None
        if time_of_day:
            m = re.fullmatch(r"\s*(\d{1,2})(?::(\d{2}))?\s*", str(time_of_day))
            if m and int(m.group(1)) < 24 and int(m.group(2) or 0) < 60:
                hm = (int(m.group(1)), int(m.group(2) or 0))
        day = datetime.date.fromisoformat(str(date).strip()) if date else None
    except (ValueError, TypeError):
        return None
    if day is None and hm is None:
        return None
    h, mi = hm or (9, 0)
    if day is None:
        t = spoken.replace(hour=h, minute=mi, second=0, microsecond=0)
        if t <= spoken:
            t += datetime.timedelta(days=1)
    else:
        naive = datetime.datetime(day.year, day.month, day.day, h, mi)
        t = naive.astimezone()                    # local offset on that day (DST-aware)
    return t.isoformat()


def _str_or_none(v):
    return str(v).strip() if v not in (None, "") and str(v).strip().lower() != "null" else None


def command_title(cmd):
    a, note, text = cmd["action"], cmd.get("note"), cmd.get("text") or ""
    if a == "CREATE_NOTE":
        return f"New note: {note or text}"[:120]
    if a == "ADD_TO_NOTE":
        return f"Add to {note}" if note else "Add to note"
    if a == "CREATE_REMINDER":
        return f"Reminder: {text}"[:120]
    return "Command not understood"


def preview_of(markdown):
    plain = re.sub(r"[#*_`>\[\]]", "", markdown or "")
    plain = re.sub(r"^\s*[-+]\s+", "", plain, flags=re.M)
    plain = " ".join(plain.split())
    return plain if len(plain) <= 200 else plain[:199].rsplit(" ", 1)[0] + "…"


# --------------------------------------------------------------------------- the pipeline

def process(args, aac, entry, save, check=lambda: None):
    """Process one recording. save(**fields) stores results and status as each step
    finishes, so a failure later on keeps what was done; check() is called between
    requests and raises Cancelled to stop. Returns per-step timings in seconds."""
    have = tools(args)
    stem = aac[:-4]
    timings = {}
    t0 = time.time()
    # Commands are short and deliberate: whisper hears all of it, undiarized.
    diarize = have["whisper"] and have["diarize"] and entry.get("kind") != "command"
    save(status="diarizing" if diarize else "transcribing", error=None)
    if have["ffmpeg"]:
        remux(args, aac, stem + ".m4a")
    duration = adts_duration(aac)
    segments, language, speakers = [], None, None
    tmp = tempfile.mkdtemp(prefix="rtb-")
    try:
        if have["whisper"]:
            clean = os.path.join(tmp, "clean.wav")
            norm = os.path.join(tmp, "norm.wav")
            ffmpeg(args, "-i", aac, "-af", CLEAN_FILTER, "-ar", str(RATE), "-ac", "1", "-c:a", "pcm_s16le", clean)
            ffmpeg(args, "-i", clean, "-af", NORMALIZE_FILTER, "-c:a", "pcm_s16le", norm)
            total = os.path.getsize(clean) / (2 * RATE)
            timings["prepare"] = round(time.time() - t0, 1)
            turns = None
            if diarize:
                t = time.time()
                seg_model, emb_model = _diarize_models(args)
                try:
                    turns = [tuple(x) for x in _sherpa(args, "_diarize", clean, seg_model, emb_model,
                                                       str(args.speakers), str(args.speaker_threshold))]
                    log(f"diarization: {sum(b - a for a, b, _ in turns):.0f} s of speech in {total:.0f} s")
                except RuntimeError as e:                        # optional step: keep going
                    log(f"diarization failed, continuing without speakers: {e}")
                timings["diarize"] = round(time.time() - t, 1)
                save(status="transcribing", duration_s=duration)
            t = time.time()
            # Whisper only hears the speech turns (padded). Silence is where it invents
            # text, and pyannote finds quiet far-field speech that silero VAD missed
            # (tested on real recordings from the wearable).
            regions = [(a, b) for a, b, _ in turns] if turns is not None else None
            segments, language = transcribe(args, norm, regions, total, check)
            timings["whisper"] = round(time.time() - t, 1)
            if turns is not None:
                segments, speakers = assign_speakers(segments, turns)
                # Text where pyannote heard no voice is noise whisper made up.
                segments = [x for x in segments if x["speaker"]]
                speakers = speakers or None
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    segments = merge_fragments([dict(x, speaker=x.get("speaker")) for x in segments])
    fields = {"duration_s": duration, "speakers": speakers,
              "transcript": {"language": language if segments else None, "segments": segments}
              if have["whisper"] else None}
    if segments and have["llm"]:
        save(status="summarizing", **fields)
        fields = {}
        t = time.time()
        if entry.get("kind") == "command":
            cmd = command(args, segments, entry.get("started_at") or time.time() * 1000, check)
            fields.update(command=cmd, summary=None, title=command_title(cmd),
                          preview=preview_of(cmd["text"] + (f" ({cmd['when']})" if cmd["when"] else "")))
        else:
            summ = summarize(args, segments, check)
            fields.update(summary=summ, command=None, title=summ["title"], preview=preview_of(summ["summary"]))
        timings["llm"] = round(time.time() - t, 1)
    elif segments:
        fields.update(summary=None, command=None, title=None,
                      preview=preview_of(" ".join(x["text"] for x in segments)))
    timings["total"] = round(time.time() - t0, 1)
    # Reprocessing replaces everything: no stale title or summary from an earlier run.
    save(status="done", **dict({"summary": None, "command": None, "title": None, "preview": None}, **fields))
    log(f"processed {os.path.basename(aac)}: {duration:.0f} s audio, {len(segments)} segments, "
        f"{speakers} speakers, timings {timings}")
    return timings


# --------------------------------------------------------------------------- CLI

def main():
    if len(sys.argv) > 1 and sys.argv[1] == "_diarize":                # child process step
        print(json.dumps(_child_diarize(sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5]), float(sys.argv[6]))))
        return
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("aac", help="a recording (.aac)")
    ap.add_argument("--kind", choices=["summary", "command"], default="summary")
    ap.add_argument("--started-at", type=int, help="ms since epoch (default: file time)")
    add_arguments(ap)
    args = ap.parse_args()
    started = args.started_at or int(os.path.getmtime(args.aac) * 1000)
    log(f"tools: {tools(args)}")
    result = {}

    def save(**fields):
        if "status" in fields:
            log(f"status: {fields['status']}")
        result.update(fields)
    process(args, args.aac, {"kind": args.kind, "started_at": started}, save)
    json.dump(result, sys.stdout, indent=1, ensure_ascii=False)
    print()


if __name__ == "__main__":
    main()
