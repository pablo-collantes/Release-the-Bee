# Receiver API: recordings, transcripts and summaries

The receiver does more than write audio. When a recording ends it processes it:

```
.aac → clean-up + 16 kHz WAV (ffmpeg) → speakers (diarization) → transcript (whisper)
     → summary or command (a small local LLM) → an *entry*
```

The app lists entries, shows each one's summary and transcript, and plays its audio. All of
it is served from the same host and port as the WebSocket, with the same bearer token.

An **entry** is one recording: the audio of one `session` from
[rtb-stream/1](STREAM_PROTOCOL.md). Its `id` is the session id the app generated.

## Entry kinds

| `kind` | Started by (default) | Processed into |
|---|---|---|
| `summary` | unmuting the RTB (single press) | title, summary, action items |
| `command` | a button gesture (double press, hold) | one action: `CREATE_NOTE`, `ADD_TO_NOTE`, `CREATE_REMINDER` or `UNKNOWN` |

The phone knows which gesture started a recording, so it tells the receiver the kind up
front. The LLM never has to guess whether speech was meant as a command.

## Entry JSON

```json
{
  "id": "5f0c2a91-2b1c-4c55-9d8e-0a1b2c3d4e5f",
  "device": "0123456789abcdef",
  "kind": "summary",
  "parent": null,
  "trigger": "button",
  "started_at": 1761651534000,
  "ended_at": 1761658138000,
  "duration_s": 6604.2,
  "status": "done",
  "error": null,
  "title": "Quarterly planning sync",
  "preview": "Agreed to ship the beta on the 14th; Ana owns the release notes…",
  "speakers": 3,
  "transcript": {
    "language": "en",
    "segments": [
      {"start": 0.0, "end": 4.2, "speaker": "S1", "text": "Okay, let's start with the timeline."}
    ]
  },
  "summary": {
    "title": "Quarterly planning sync",
    "summary": "Markdown. A few short paragraphs.",
    "action_items": ["Ana: release notes by Friday"],
    "model": "gemma-4-e4b"
  },
  "command": null,
  "audio": {"bytes": 6601986, "mime": "audio/mp4"},
  "updated_at": 1761658412345
}
```

| Field | Notes |
|---|---|
| `kind` | `summary` or `command` |
| `parent` | for a command recorded while a summary recording was running: the summary's id. Otherwise `null` |
| `trigger` | `button`, `double_press`, `hold`, `app`, or `null` if unknown |
| `started_at`, `ended_at` | ms since epoch, phone clock. `ended_at` is `null` while recording |
| `status` | `recording` → `queued` → `diarizing` → `transcribing` → `summarizing` → `done`, or `failed` (see `error`). Steps that don't run are skipped (commands go straight to `transcribing`) |
| `title`, `preview` | `null` until summarized. `preview` is at most 200 characters. Without an LLM, `preview` is the start of the transcript and `title` stays `null` |
| `speakers` | number of distinct speakers, `null` before diarization or if it's unavailable. Commands aren't diarized |
| `transcript.segments[].speaker` | `S1`, `S2`, … in order of first appearance. `null` if diarization didn't run |
| `summary` | only for `kind: summary`, once done |
| `command` | only for `kind: command`, once done: `{"action", "note", "text", "when", "done", "model"}`. `note` is the target note's title (`ADD_TO_NOTE`, `CREATE_NOTE`), `when` is ISO 8601 with offset (`CREATE_REMINDER`), `text` is the body. `done` is the user's: `false` when first processed, changed with `PATCH`, kept when the entry is reprocessed |
| `audio` | size and type of what `/audio` serves: `audio/mp4` once processed, `audio/aac` (no seeking) before that or without ffmpeg |
| `updated_at` | server clock (ms) of the entry's last change; what `since` compares against |

## HTTP

Every request needs `Authorization: Bearer <token>` if the receiver was started with a
token. Wrong or missing token → `401`.

| Request | Response |
|---|---|
| `GET /api/entries` | `{"entries": [ … ], "deleted": [], "now": <ms>}`, newest first, with `transcript` and `summary` set to `null`. `command` is kept for commands (it's small) and `null` otherwise |
| `GET /api/entries?since=<ms>` | only entries changed at or after `<ms>` (server clock), for cheap refreshes. `deleted` lists the ids deleted at or after `<ms>` |
| `GET /api/entries/<id>` | the full entry, `404` if unknown |
| `GET /api/entries/<id>/audio` | the recording as `audio/mp4`, made to listen to: the mic's start-up clicks removed and boosted to a normal level (the RTB records quietly), at the recording's own bitrate (16–32 kbps). The raw `.aac` stays as it came. Supports `Range` |
| `POST /api/entries/<id>/reprocess` | queue it again (after changing models). `202`, or `409` while it's still recording |
| `PATCH /api/entries/<id>` | change a processed command (see below). `200` with the full entry |
| `DELETE /api/entries/<id>` | delete the entry and its audio. `200` `{"id": "<id>", "deleted": true}`, also if it was already deleted. `404` if unknown |
| `GET /api/words` | the user's word list: `{"words": ["Jev", "RTB", …]}`, oldest first |
| `PUT /api/words` | replace it: body `{"words": [ … ]}`. `200` with the list as kept: trimmed, each once ignoring case, at most 100 of at most 40 characters. `400` if the body isn't that shape |

The list response also carries `"now": <server ms>`, which the client passes as `since`
next time.

### Changing a command

`PATCH /api/entries/<id>` takes `{"command": { … }}` with any of these fields. The
others are left as they are, and unknown fields are ignored.

| Field | Value |
|---|---|
| `done` | `true` or `false` |
| `when` | ISO 8601 with an offset (`2026-10-29T15:00:00-05:00`, or `Z`), or `null` |
| `text` | a string |
| `note` | a string, or `null` |

The change bumps `updated_at`, so the next `?since=` returns the entry, and it is pushed
to connected phones. Errors: `400` if the body isn't JSON, has no `command` object, or a
field has the wrong type; `404` for an unknown id; `409` `{"error": "not a processed
command"}` if the entry has no `command` yet (a summary, or a command still being
processed); `413` if the body is over 64 KiB.

### Deleting an entry

`DELETE /api/entries/<id>` removes the entry from the list at once and deletes its files
(`.aac`, `.m4a`, `.json`). If the worker is processing it, it stops. If the recording is
still going (or the phone dropped and may resume it), the audio that still arrives is
thrown away and the files are deleted when the session ends. Deleting a summary doesn't
delete the commands recorded inside it: they are entries of their own.

A deleted id stays deleted: if the phone resumes that session later, its audio is
discarded and no entry comes back. The receiver remembers deleted ids for 90 days
(`deleted.json` in the recordings folder). A client that last synced longer ago than
that should fetch the whole list again.

## Pushes over the WebSocket

The app is already connected to the WebSocket to send audio, so the receiver uses that
connection to tell it when an entry changes. That way the app never polls:

```json
{"type":"entry","id":"5f0c2a91-…","kind":"summary","status":"done","title":"Quarterly planning sync"}
```

It is sent on every status change and after a `PATCH`. When an entry is deleted,
`status` is `deleted` and `title` is `null`:

```json
{"type":"entry","id":"5f0c2a91-…","kind":"command","status":"deleted","title":null}
```

The app then fetches `GET /api/entries/<id>` if it needs the body, or drops the entry
if it was deleted.

## Processing details (reference receiver)

- **Audio clean-up** before whisper: decode to 16 kHz mono, high-pass at 80 Hz (removes
  handling rumble from a wearable), and normalize loudness (`dynaudnorm`) so quiet
  speakers reach the same level.
- **Diarization first:** sherpa-onnx (pyannote segmentation + a speaker-embedding
  model), optional. Its speech turns double as voice activity detection: whisper only
  hears them (padded), because silence is where it invents text ("Thank you.", one line
  repeated a hundred times). On a wearable's quiet far-field speech, pyannote finds
  speech that Silero VAD misses. Without diarization, whisper hears everything and
  segments have `speaker: null`. Commands aren't diarized.
- **Transcription:** an HTTP whisper.cpp server (`--whisper-url`), or `whisper-cli`
  (`--whisper-cli` + `--whisper-model`), in requests of at most 10 minutes of speech,
  without conditioning on its own previous text (`max_context=0`, which stops
  repetition loops). With a word list (`PUT /api/words`), whisper is primed with it
  instead: the words are its initial prompt, carried into every 30-second window
  (`carry_initial_prompt`), and `max_context` is sized to the prompt so little of its own
  text comes back. A segment made only of those words (whisper reading the prompt back
  over noise) is dropped. The summary and command prompts get the same words, spelled.
  Leftover filler, loops, and text where diarization heard no voice are dropped. Fragments of one sentence by one speaker are joined into one segment.
- **Summaries and commands:** any OpenAI-compatible chat endpoint (`--llm-url`,
  `--llm-model`): llama.cpp's `llama-server`, LM Studio, Ollama, vLLM. The default prompt
  is tuned for a small model (Gemma E4B). Output is constrained by a JSON schema where
  the server supports it. A transcript longer than `--llm-chunk` (8000 tokens) is
  summarized in parts, then the parts' notes are merged: a small model covers a long
  meeting more evenly that way. Commands resolve relative times ("tomorrow
  at 3") against when they were spoken, in the receiver's time zone.
- One recording is processed at a time, oldest first. Each step is skipped if its tool
  isn't configured, and the entry still ends `done` with what it has.
