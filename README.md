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
| Receiver: saves sessions, resumes after network drops, runs a hook per recording | Works and is tested |
| Local transcription with diarization | Next |
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
4. **In the app:**
   1. Under *Setup*, tap **Allow** and **Unrestrict**.
   2. Import the seal token.
   3. Under *Send audio to*, enter `ws://<your-machine>:8765/` and the token, then tap
      **Save destination**.
   4. Tap **Start**. The first start scans for your RTB. After that the app reconnects on
      its own, including after a reboot.

Recordings land in `recordings/<device>/<date>/<time>_<session>.aac`. To transcribe each
one when it finishes:

```sh
python3 receiver/rtb_receiver.py --token … --on-session-end \
  'ffmpeg -loglevel error -i {path} -ar 16000 -ac 1 {path}.wav && whisper-cli -m path/to/ggml-large-v3.bin -f {path}.wav -otxt'
```

(`{path}` is shell-quoted for you, so `{path}.wav` becomes `<recording>.aac.wav`.)

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

- Tap **Unrestrict** in the app (it opens Android's "unrestricted battery" prompt).
- **Samsung:** also add the app under *Settings → Battery → Background usage limits →
  Never sleeping apps*.
- From a computer, this does the same as Unrestrict:
  `adb shell cmd deviceidle whitelist +io.github.pablocollantes.releasethebee`

## Security

- **The seal token is a per-device secret.** The app stores it encrypted under an
  Android Keystore key, never logs it, and opts out of backups. It never belongs in
  this repository.
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
receiver/   rtb_receiver.py: standard-library receiver
tools/      parse_bee_token.py (token capture), secret_guard.py (git hooks)
docs/       RTB BLE protocol, phone→receiver stream protocol, transport recipes
```

## License

[MIT](LICENSE)
