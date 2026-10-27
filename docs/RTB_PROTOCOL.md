# RTB BLE protocol

How the RTB (the Bee voice-recorder wearable, an nRF54L15 device) talks over
Bluetooth Low Energy. Reverse-engineered for interoperability from BLE captures and
analysis of the companion app. Firmware updates may change it.

The app's implementation is `android/app/src/main/kotlin/…/rtb/`.

## GATT

| | UUID | Properties |
|---|---|---|
| Service | `03d5d5c4-a86c-11ee-9d89-8f2089a49e7e` | |
| Control | `05e1f93c-d8d0-5ed8-dd88-379e4c1a3e3e` | Write, Notify |
| Audio | `b189a505-a86c-11ee-a5fb-8f2089a49e7e` | Notify |

Request MTU 517 after connecting. Audio notifications are 495 bytes, so anything below
an MTU of 498 truncates them.

## Requests

Every request is written with response to the control characteristic:

```
[opcode u16, little-endian][payload]
```

All opcodes are `0xC0nn`, so every request starts `nn C0`.

| Request | Opcode | Bytes | Payload |
|---|---|---|---|
| SetTxPower | 0xC001 | `01 C0` | `[power]` |
| GetTxLevel | 0xC002 | `02 C0` | |
| GetChargingSpeed | 0xC004 | `04 C0` | |
| GetFirmwareVersion | 0xC005 | `05 C0` | |
| **SetDeviceState** | 0xC006 | `06 C0` | `[state]` (below) |
| SetBitrate | 0xC008 | `08 C0` | `[bitrate u32 LE]` |
| GetBitrate | 0xC009 | `09 C0` | |
| GetDeviceState | 0xC00A | `0A C0` | |
| GetDeviceID | 0xC00E | `0E C0` | |
| GetBatteryAndChargeState | 0xC00F | `0F C0` | |
| SetBrightness | 0xC016 | `16 C0` | `[01][brightness]` |
| GetBrightness | 0xC017 | `17 C0` | |
| **SetEncryptionKey** (seal) | 0xC018 | `18 C0` | the 108-byte seal token |
| **GetSessionNonce** | 0xC019 | `19 C0` | |
| **GetSecureIds** | 0xC01B | `1B C0` | |
| GetDSN | 0xC01D | `1D C0` | |

SetDeviceState values: `0` mute, `1` recording, `2` sleep (hardware VAD), `3` power
off, `4` sleep (software VAD). Start audio with `06 C0 01`, stop it with `06 C0 00`.

## Replies and events

Notifications on the control characteristic start with a little-endian u16 tag:

| Tag | Meaning | Rest |
|---|---|---|
| `0x8000` | OK | `[echoed opcode u16 LE][payload]` |
| `0x8001` | Error | `[echoed opcode u16 LE]` |
| `0x8002` | InvalidParam | `[echoed opcode u16 LE]` |
| `0x8003` | UnknownMessage | `[echoed opcode u16 LE]` |
| `0x8004` | SetEncryptionKey: a key is already set | |
| `0xA001` | Event: button double-press | |
| `0xA002` | Event: button hold | |
| `0xA003` | Event: device state changed | |
| `0xA004` | Event: battery level | |
| `0xA005` | Event: charging | |

OK payloads that matter:

| Echoed opcode | Payload |
|---|---|
| 0xC01B GetSecureIds | `[deviceId 8][sealed u8]`: sealed if the 9th byte is non-zero |
| 0xC019 GetSessionNonce | first 12 bytes = session nonce |
| 0xC006 SetDeviceState | `[state]` |
| 0xC018 SetEncryptionKey | ignored: the token was installed |
| 0xC00E GetDeviceID | 8 bytes |

## The seal token

An RTB streams audio only after it has been **sealed**: given its 108-byte token with
SetEncryptionKey.

```
"SKV1" (4) | deviceId (8) | ctrKey (16) | signature (80)
```

- The token is **fixed per device and signed**. The firmware checks the signature and
  binds it to the key, so you can't make one or swap in your own key. The only token a
  unit accepts is its own, which the stock app writes during pairing.
- You get it once from a Bluetooth HCI snoop log of that pairing:
  [`tools/parse_bee_token.py`](../tools/README.md).
- Replaying the same token re-seals the device, including after a factory reset
  (5 presses of the action button), so no server is involved after the first capture.
- `ctrKey` (bytes 12–28) is the AES key for the audio. **The token is a secret**: anyone
  holding it can seal that RTB and decrypt its audio.

A bare 16-byte key is rejected with InvalidParam (`02 80 18 C0`).

## Handshake

What the app runs on every connection:

```
connect → MTU 517 → discover services
→ enable notifications: control, then audio
→ GetSecureIds                     deviceId must match the token's, or this isn't your RTB
    not sealed → SetEncryptionKey(token) → GetSecureIds again (must now be sealed)
→ GetSessionNonce                  new nonce every session: never reuse one across a reconnect
→ SetDeviceState(1)                audio notifications start
```

## Audio

Each audio notification is 495 bytes, about 8 per second:

```
[seq u32 BE][491 bytes ciphertext]
```

Decrypt each frame on its own with AES-128-CTR:

- key = ctrKey
- IV = sessionNonce (12 bytes) ‖ seq (4 bytes, big-endian)
- the counter restarts for every frame

The plaintext is ADTS AAC-LC, 16 kHz, mono. ADTS frames can span notification
boundaries, so treat the plaintexts as one continuous byte stream. Written back to
back, they form a playable `.aac` file.
