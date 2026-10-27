# Tools

## `parse_bee_token.py`: get your RTB's seal token

Your RTB streams audio only after the app seals it with its own 108-byte token
([why](../docs/RTB_PROTOCOL.md#the-seal-token)). The token is fixed per device and
signed by the vendor, so you can't make one. You capture it once, when the stock app
pairs with your RTB, and the app replays it from then on.

**The token is a secret.** Anyone with it can decrypt your RTB's audio. Don't post it,
don't commit it, and delete the capture once you've imported it.

### You need

- Python 3 (standard library only, nothing to install)
- `adb` (Android platform-tools), with USB or wireless debugging enabled on the phone
- The stock Bee app, one more time

### 1. Capture the pairing

1. On the phone, open **Settings → Developer options** and set **Bluetooth HCI snoop
   log** to *Enabled*. (No Developer options? Tap **Build number** 7 times under
   Settings → About phone → Software information.)
2. Turn Bluetooth **off and on** to start a fresh log.
3. If the RTB is already sealed, press its action button **5 times quickly** to reset
   it, so it gets sealed again during the capture.
4. Open the stock Bee app and pair or set up the RTB.
5. Pull the log:
   ```sh
   adb bugreport bee.zip
   ```
   This takes 1–3 minutes and makes a large zip. That's normal.

### 2. Extract the token

```sh
python3 tools/parse_bee_token.py bee.zip --save .
```

It accepts a bugreport `.zip`, a raw `btsnoop_hci.log`, or a folder to search. For each
RTB it finds, it prints the token fields and writes `rtb-<deviceId>.token` (the raw
108 bytes, readable only by you).

### 3. Import it into the app

Use either method:

- **File:** copy `rtb-<deviceId>.token` to the phone (`adb push rtb-*.token /sdcard/Download/`),
  then tap **Import file…**.
- **Paste:** copy the `full token` line (216 hex characters) and tap **Save pasted token**.

The app checks the token's shape, encrypts it with a key held in the Android Keystore,
and from then on seals the RTB automatically whenever it's needed. Then clean up:

```sh
rm bee.zip rtb-*.token
adb shell rm /sdcard/Download/rtb-*.token
```

Turn the HCI snoop log back off, too.

### Nothing found?

- The snoop log has to be on **before** the pairing, and the RTB must actually get sealed
  (reset it first if it was already sealed).
- Pull the bugreport soon after pairing. Toggling Bluetooth starts a new log.
- Try the raw log if you have it: `python3 tools/parse_bee_token.py btsnoop_hci.log`.

## `secret_guard.py`: keep tokens out of git

Run by the git hooks in [`.githooks/`](../.githooks). It refuses any commit or push that
contains a seal token, as raw bytes, hex (any spacing) or base64, plus `*.token`, snoop
logs, bugreports and keystores. Enable it once per clone:

```sh
git config core.hooksPath .githooks
```

To also block values specific to *your* RTB (its ctrKey, deviceId, BLE address), list
them one per line in `.git/info/rtb-denylist`. That file is inside `.git`, so it is never
committed or pushed.
