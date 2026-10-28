# Release the Bee

Keep your Bee wearable connected to your Android phone and stream its audio to **your
own machine**, where you transcribe it locally with whatever model you like. No cloud,
no account, no stock app after the first pairing.

We call the device the **RTB** (as in *Release the Bee*).

> Unofficial and unaffiliated with Bee or Amazon. It talks to hardware you own, using
> the token your own RTB was sealed with. See [docs/RTB_PROTOCOL.md](docs/RTB_PROTOCOL.md).

```
 ┌──────┐  BLE: encrypted AAC  ┌────────────────────────────┐  any network you like  ┌─────────────────────┐
 │ RTB  │ ───────────────────▶ │ Release the Bee (Android)  │ ─────────────────────▶ │ receiver on your box │
 └──────┘  seal, nonce, audio  │ foreground service:        │  ws:// · wss:// · TCP  │ saves .aac sessions, │
     ▲                         │ reconnects, re-seals,      │  Tailscale, WireGuard, │ runs your transcriber│
     └── stays connected ──────│ decrypts on the phone      │  OpenVPN, LAN, CF…     └─────────────────────┘
                               └────────────────────────────┘
```

## Status

| Part | State |
|---|---|
| BLE protocol: seal, nonce, decrypt | Proven on real hardware with an earlier test rig |
| Android app: always-on service, auto-reconnect, auto-reseal, WebSocket/TCP/on-phone sinks | **New, first release.** Builds and passes unit tests; end-to-end hardware testing in progress |
| Receiver: saves sessions, resumes after network drops, serves entries over HTTP | Works and is tested |
| Local transcription, speakers, summaries and voice commands | **New.** Works on real recordings (whisper.cpp, sherpa-onnx, Gemma 4 E4B) |
| Cloudflare relay, spill-to-disk buffering, extracting the token inside the app | Planned |

## Quick start

1. **Get your RTB's seal token.** It's captured once from the stock app's pairing:
   [tools/README.md](tools/README.md).
2. **Build and install the app** (JDK 17, Android SDK 35, Android 13+ phone):
   ```sh
   cd android
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. **Start the receiver** on your machine:
   ```sh
   python3 receiver/rtb_receiver.py --token "$(openssl rand -hex 16)"
   ```
4. **In the app,** open **Settings** (the gear, or tap the status card):
   1. Under *This phone*, tap **Allow** and **Unrestrict**.
   2. Under *RTB key*, import the seal token. A bare 16-byte ctrKey also works: it
      decrypts audio but can't reseal the RTB after a reset, so keep the full token if
      you have it.
   3. Under *Send audio to*, enter `ws://<your-machine>:8740/` and the token, then tap
      **Save destination**.
   4. Tap **Start**. The first start scans for your RTB. After that the app reconnects on
      its own, including after a reboot.

   The main screen lists your recordings, newest first, once the receiver serves them
   ([docs/SERVER_API.md](docs/SERVER_API.md)): the summary, the transcript with speakers,
   and a player that streams the audio from the receiver. The RTB's own button mutes and
   unmutes it.

Recordings land in `recordings/<device>/<date>/<time>_<session>.aac`. With only the
standard library, the receiver saves them and serves the audio. To get transcripts and
summaries, set up processing.

## Transcripts, speakers and summaries

When a recording ends, the receiver turns it into an *entry*
([docs/SERVER_API.md](docs/SERVER_API.md)), one at a time, oldest first:

```
.aac → 16 kHz WAV, high-pass → who speaks when → whisper on the speech only → summary or command
```

Each step runs if its tool is configured and is skipped otherwise. The entry still
finishes with what it has.

| Step | Needs | Flags |
|---|---|---|
| Seekable M4A, audio clean-up | `ffmpeg` | `--ffmpeg` (default: on `PATH`) |
| Transcript | a [whisper.cpp](https://github.com/ggml-org/whisper.cpp) server, or `whisper-cli` | `--whisper-url`, or `--whisper-cli` + `--whisper-model`; `--language` |
| Speakers, and skipping silence (where whisper invents text) | `sherpa-onnx` and its models | `--sherpa-dir`; `--speakers N` if you know it |
| Summary (unmute) or command (double press, hold) | any OpenAI-compatible chat endpoint | `--llm-url`, `--llm-model`, `--llm-key`, `--llm-context`, `--llm-chunk` |

Install sherpa-onnx in a venv and fetch its models once (about 33 MB):

```sh
python3 -m venv receiver/.venv
receiver/.venv/bin/pip install sherpa-onnx numpy
mkdir -p ~/models/sherpa-onnx-diarization && cd ~/models/sherpa-onnx-diarization
rel=https://github.com/k2-fsa/sherpa-onnx/releases/download
curl -L $rel/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 | tar xj
curl -LO $rel/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx
```

A small LLM is enough: the prompts are tuned for Gemma 4 E4B. Serve it with llama.cpp,
thinking off, and a context that holds a long meeting (longer transcripts are summarized
in parts):

```sh
llama-server -m gemma-4-E4B-it-Q4_K_M.gguf --alias gemma-4-e4b --port 8742 -c 32768 -ngl 99 --reasoning off
```

Then start the receiver with the venv's Python:

```sh
receiver/.venv/bin/python receiver/rtb_receiver.py --token … \
  --whisper-url http://127.0.0.1:8009 \
  --sherpa-dir ~/models/sherpa-onnx-diarization \
  --llm-url http://127.0.0.1:8742/v1 --llm-model gemma-4-e4b
```

`receiver/.venv/bin/python receiver/rtb_process.py <recording.aac> [same flags]` processes
one file and prints the result. `--on-session-end CMD` still runs your own command per
recording.

On an AMD Strix Halo (whisper large-v3 on the iGPU with Vulkan, diarization on 16 CPU
threads, Gemma 4 E4B Q4_K_M), a 1 h 50 min meeting takes about 9 minutes: 4 for
diarization, 4 for whisper, 30 s for the summary. A voice command takes 2 to 3 seconds.

### As systemd user services

`~/.config/systemd/user/rtb-llm.service`:

```ini
[Unit]
Description=Gemma 4 E4B for Release the Bee
StartLimitIntervalSec=0

[Service]
ExecStart=/usr/bin/llama-server -m %h/models/gemma-4-e4b/gemma-4-E4B-it-Q4_K_M.gguf \
  --alias gemma-4-e4b --host 127.0.0.1 --port 8742 -c 32768 -ngl 99 --reasoning off
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

`~/.config/systemd/user/rtb-receiver.service` (bound to a Tailscale address, so it waits
for that address to exist):

```ini
[Unit]
Description=Release the Bee receiver
Wants=rtb-llm.service
After=rtb-llm.service
StartLimitIntervalSec=0

[Service]
WorkingDirectory=%h/Release-the-Bee
ExecStartPre=/bin/sh -c 'until ip -4 addr | grep -qF " 100.x.y.z/"; do sleep 2; done'
ExecStart=%h/Release-the-Bee/receiver/.venv/bin/python receiver/rtb_receiver.py \
  --ws 100.x.y.z:8740 --out %h/Release-the-Bee/recordings \
  --whisper-url http://127.0.0.1:8009 --sherpa-dir %h/models/sherpa-onnx-diarization \
  --llm-url http://127.0.0.1:8742/v1 --llm-model gemma-4-e4b
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
```

```sh
systemctl --user daemon-reload && systemctl --user enable --now rtb-llm rtb-receiver
journalctl --user -u rtb-receiver -f
```

For a token, add `Environment=RTB_TOKEN=…` (or keep it in a file and use
`EnvironmentFile=`) rather than putting it on the command line.

## Getting audio to your machine

The app doesn't depend on any particular network: you give it a URL. Tailscale,
WireGuard, OpenVPN, ZeroTier, plain Wi-Fi, a Cloudflare Tunnel and your own reverse
proxy all work. Recipes: [docs/TRANSPORTS.md](docs/TRANSPORTS.md).

The wire format is small enough to implement your own receiver:
[docs/STREAM_PROTOCOL.md](docs/STREAM_PROTOCOL.md).

## Staying connected

The app runs a foreground service (type `connectedDevice`) that owns the Bluetooth link.
After the first connection it dials the RTB with `autoConnect`, so Android itself waits
for the RTB to come back in range: no polling, and little battery cost while it's away.

Android kills background work aggressively, so:

- Tap **Unrestrict** in the app's Settings (it opens Android's "unrestricted battery" prompt).
- **Samsung:** also add the app under *Settings → Battery → Background usage limits →
  Never sleeping apps*.
- From a computer, this does the same as Unrestrict:
  `adb shell cmd deviceidle whitelist +io.github.pablocollantes.releasethebee`

## Security

- **The seal token is a per-device secret** (and so is its ctrKey). The app stores it
  encrypted under an Android Keystore key, never logs it, and opts out of backups. It
  never belongs in this repository.
- **This repo guards against leaking one.** Enable the hooks once per clone:
  `git config core.hooksPath .githooks`. Commits and pushes containing a token (raw, hex
  or base64), `*.token` files, snoop logs or keystores are refused. See
  [tools/README.md](tools/README.md#secret_guardpy-keep-tokens-out-of-git).
- **The receiver** should run with `--token` and be reached over a VPN or `wss://`.
  Audio is decrypted on the phone, so the network path has to protect it.

## Building

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
```

Optional: a stable debug key keeps app data (including the imported token) across
reinstalls on different machines. It's gitignored, so make your own:

```sh
keytool -genkey -keystore android/debug.keystore -alias androiddebugkey -keyalg RSA \
  -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" \
  -storepass android -keypass android
```

## Layout

```
android/    the app (Kotlin, no AndroidX; OkHttp for WebSockets)
receiver/   rtb_receiver.py (WebSocket + HTTP), rtb_entries.py (entries on disk),
            rtb_process.py (transcript, speakers, summary), tests/ (python3 -m unittest
            discover -s receiver/tests)
tools/      parse_bee_token.py (token capture), secret_guard.py (git hooks)
docs/       RTB BLE protocol, phone→receiver stream protocol, receiver API, transports
```

## License

[MIT](LICENSE)
