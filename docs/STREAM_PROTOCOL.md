# rtb-stream/1: phone → receiver

What the app sends to your machine. It's small on purpose, so you can write your own
receiver in any language. [`receiver/rtb_receiver.py`](../receiver/rtb_receiver.py) is
the reference receiver, written with only the Python standard library.

## WebSocket

The app connects to the `ws://` or `wss://` URL you configure. If you set a token, the
upgrade request carries:

```
Authorization: Bearer <token>
```

Reject the upgrade with `401` if it doesn't match. The app pings every 20 s and
reconnects with backoff (1 s → 60 s) whenever the connection drops.

### Text messages (JSON)

Sent once after every connect:

```json
{"type":"hello","protocol":"rtb-stream/1","app":"release-the-bee-android/0.1.0",
 "device":"<deviceId hex>","codec":"aac-adts","sample_rate":16000,"channels":1}
```

When the RTB starts streaming:

```json
{"type":"session_start","session":"<uuid>","device":"<deviceId hex>",
 "started_at":1761580000000,"resumed":false,
 "kind":"summary","trigger":"button","parent":null}
```

`resumed: true` means the network dropped mid-session and the app reconnected. The
`session` id is the same, so append to the same recording.

`hello`'s `device` can be `null` (the app hasn't met its RTB yet); `session_start`
always carries it, so file recordings by the session's `device`.

`kind` says what the recording is for: `summary` (the default if missing) or `command`.
`trigger` says which button gesture started it: `button`, `double_press`, `hold`, `app`.
See [SERVER_API.md](SERVER_API.md) for what the receiver does with each kind.

**Nested recordings.** A command can be recorded in the middle of a summary recording
(the user double-presses during a meeting). The app then sends a `session_start` with
`parent` set to the open summary's id. Audio goes to the command until its
`session_end`, then back to the parent, which never closed. There is at most one level
of nesting. The command's audio is not part of the parent's recording.

When the RTB stops (the link dropped, the user paused, or the app stopped):

```json
{"type":"session_end","session":"<uuid>","reason":"paused"}
```

A `session_end` can be missing, because the phone may die or lose signal. Give an
unfinished session a grace period, then close it yourself (the reference receiver
waits 10 minutes).

### Binary messages (audio)

```
[seq u32 BE][captured_at_ms u64 BE][ADTS AAC bytes]
```

- `seq` is the RTB's frame counter. A jump means frames were lost over BLE.
- `captured_at_ms` is the phone's clock when the frame arrived over BLE, not when it
  was sent. Audio buffered during a network outage keeps its real time.
- The ADTS bytes are one decrypted BLE notification (normally 491 bytes). ADTS frames
  span messages, so concatenate the payloads before decoding.

### Messages to the phone

The receiver tells the app when a recording's entry changes (queued, transcribed,
summarized), so the app can refresh without polling:

```json
{"type":"entry","id":"<session uuid>","kind":"summary","status":"done","title":"…"}
```

The app ignores message types it doesn't know.

## Buffering

While the receiver is unreachable, the app keeps up to about 15 minutes of audio in
memory and sends it once the receiver is back, oldest first. Past that limit it drops
the oldest audio. Audio OkHttp had already accepted when a connection silently died
is lost (at most about 64 KB, a few seconds).

## Raw TCP

The "Raw TCP" destination sends only the concatenated ADTS bytes: no headers, no
sessions, no auth. Anything that writes a socket to a file is a receiver:

```sh
nc -l 8741 > rtb.aac
```

Use it on a private network only.
