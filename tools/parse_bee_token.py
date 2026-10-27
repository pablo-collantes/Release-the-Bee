#!/usr/bin/env python3
"""
parse_bee_token.py  —  pull the Bee 108-byte seal token out of a Bluetooth HCI snoop log.

WHAT IT DOES
  Scans a btsnoop capture (or an Android bugreport .zip, or a folder) for the Bee
  "SetEncryptionKey" write and prints the 108-byte seal token it carries, broken into
  its fields. Release the Bee needs the whole 108-byte token (it carries the
  16-byte ctrKey that decrypts the audio).

  Wire write the phone sends to the Bee:
      18 c0  <------------------------ 108-byte token --------------------------->
      18 c0  534b5631  <deviceId 8B>  <ctrKey 16B>  <signature 80B>
      |op 0xC018|  |"SKV1"|

HOW TO CAPTURE THE LOG ON A GALAXY Z FOLD 6 (or any modern Samsung/Android 13+)
  1. Settings > Developer options > turn ON "Bluetooth HCI snoop log" (set to "Enabled"/"All").
     (No Developer options? Settings > About phone > Software information > tap "Build number" 7x.)
  2. Toggle Bluetooth OFF then ON so the fresh log starts clean.
  3. Open the stock Bee app and pair / set up the Bee so it gets sealed. The token is written
     during that sealing step. (If the Bee is already sealed, 5x-reset it first so it re-seals.)
  4. Pull the log. Easiest: `adb bugreport bee.zip`  — the snoop log is inside the zip at
     FS/data/log/bt/btsnoop_hci.log. You can hand this script the whole zip; it finds it.
     (Direct path on some builds: /data/log/bt/btsnoop_hci.log — needs root to pull directly.)

USAGE
  python3 parse_bee_token.py bee.zip
  python3 parse_bee_token.py btsnoop_hci.log
  python3 parse_bee_token.py /path/to/folder          # searches it for btsnoop* files
  python3 parse_bee_token.py bee.zip --save .         # also write rtb-<deviceId>.token files

  The .token file holds the raw 108 bytes: import it in the app with "Import file…".
  Treat it like a password. Never commit it (the repo's .gitignore and secret guard
  refuse *.token files).

  Needs only Python 3 (standard library). No pip installs.
"""
import sys, os, re, struct, zipfile, argparse, glob, datetime

MAGIC = bytes.fromhex("534b5631")          # "SKV1"  — start of the token
OPCODE = bytes.fromhex("18c0")             # SetEncryptionKey, 0xC018 little-endian
TOKEN_LEN = 108                            # magic(4) + deviceId(8) + ctrKey(16) + sig(80)

# btsnoop timestamps are microseconds since 0000-01-01; this is the offset to the unix epoch.
BTSNOOP_EPOCH_DELTA_US = 0x00dcddb30f2f8000


def read_sources(path):
    """Yield (label, bytes) for every candidate log in a file, zip, or directory."""
    if os.path.isdir(path):
        hits = sorted(glob.glob(os.path.join(path, "**", "*btsnoop*"), recursive=True))
        if not hits:
            sys.exit(f"No btsnoop* file found under {path}")
        for h in hits:
            with open(h, "rb") as f:
                yield h, f.read()
        return
    with open(path, "rb") as f:
        head = f.read(4)
        f.seek(0)
        blob = f.read()
    if head[:2] == b"PK":                  # it's a zip (bugreport)
        zf = zipfile.ZipFile(path)
        members = [n for n in zf.namelist() if "btsnoop" in n.lower()]
        if not members:
            sys.exit(f"{path} is a zip but has no btsnoop member inside it.")
        for m in members:
            yield f"{path}!{m}", zf.read(m)
        return
    yield path, blob                       # raw btsnoop (or anything; we just scan bytes)


def index_btsnoop_records(data):
    """Best-effort: map file offsets -> (unix_ts, direction) using the btsnoop record framing.
    Returns a list of (data_start, data_end, ts_unix_or_None, direction_str). Never raises."""
    recs = []
    try:
        if data[:8] != b"btsnoop\x00":
            return recs
        off = 16                                   # 8 id + 4 version + 4 datalink
        n = len(data)
        while off + 24 <= n:
            orig_len, incl_len, flags, drops = struct.unpack_from(">IIII", data, off)
            ts_us, = struct.unpack_from(">q", data, off + 16)
            dstart = off + 24
            dend = dstart + incl_len
            if incl_len < 0 or dend > n:
                break
            ts_unix = None
            try:
                ts_unix = (ts_us - BTSNOOP_EPOCH_DELTA_US) / 1_000_000.0
            except Exception:
                pass
            direction = "recv (Bee->phone)" if (flags & 0x01) else "sent (phone->Bee)"
            recs.append((dstart, dend, ts_unix, direction))
            off = dend
    except Exception:
        pass
    return recs


def annotate(offset, recs):
    for dstart, dend, ts_unix, direction in recs:
        if dstart <= offset < dend:
            when = ""
            if ts_unix:
                try:
                    when = datetime.datetime.fromtimestamp(ts_unix, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
                except Exception:
                    when = ""
            return direction, when
    return None, ""


def find_tokens(data):
    """Return list of dicts for every SKV1 token found in the byte blob."""
    out = []
    for m in re.finditer(re.escape(MAGIC), data):
        start = m.start()
        token = data[start:start + TOKEN_LEN]
        if len(token) < TOKEN_LEN:
            continue                                # truncated; not a full token
        preceded_by_opcode = data[start - 2:start] == OPCODE if start >= 2 else False
        out.append({
            "offset": start,
            "token": token,
            "wire": (OPCODE + token) if preceded_by_opcode else None,
            "deviceId": token[4:12],
            "ctrKey": token[12:28],
            "sig": token[28:108],
            "opcode_present": preceded_by_opcode,
        })
    return out


def main():
    ap = argparse.ArgumentParser(
        description="Extract the Bee 108-byte seal token from a Bluetooth HCI snoop log.",
        epilog="Example: python3 parse_bee_token.py bee.zip",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("path", help="btsnoop log file, Android bugreport .zip, or a folder to search")
    ap.add_argument("--save", metavar="DIR",
                    help="write each token found to DIR/rtb-<deviceId>.token (raw 108 bytes, mode 0600)")
    args = ap.parse_args()

    seen = {}          # token bytes -> record (dedupe across rotated logs)
    scanned = []
    for label, blob in read_sources(args.path):
        scanned.append(f"{label} ({len(blob):,} bytes)")
        recs = index_btsnoop_records(blob)
        for t in find_tokens(blob):
            t["source"] = label
            t["direction"], t["when"] = annotate(t["offset"], recs)
            seen.setdefault(bytes(t["token"]), t)

    print("Scanned:")
    for s in scanned:
        print(f"  - {s}")
    print()

    if not seen:
        print("No Bee seal token (SKV1 / 534b5631) found in the capture.")
        print("Checklist: HCI snoop log was ON *before* pairing; the Bee actually got sealed;")
        print("you pulled the log that covers the pairing (bugreport, or btsnoop_hci.log).")
        sys.exit(2)

    tokens = list(seen.values())
    print(f"Found {len(tokens)} Bee seal token(s):\n")
    for i, t in enumerate(tokens, 1):
        hdr = f"=== Token {i} of {len(tokens)} ==="
        print(hdr)
        print(f"  source        : {t['source']}  @ byte offset {t['offset']}")
        if t["when"] or t["direction"]:
            print(f"  packet        : {t['direction'] or '?'}  {t['when']}")
        print(f"  opcode 18c0   : {'present (confirmed SetEncryptionKey write)' if t['opcode_present'] else 'not adjacent (token found by magic only)'}")
        print(f"  magic  SKV1   : {t['token'][0:4].hex()}")
        print(f"  deviceId (8B) : {t['deviceId'].hex()}")
        print(f"  ctrKey  (16B) : {t['ctrKey'].hex()}")
        print(f"  signature(80B): {t['sig'].hex()}")
        print(f"  >> full token : {t['token'].hex()}")
        print(f"                  ^ paste THIS (all 216 hex characters) into Release the Bee")
        if args.save:
            os.makedirs(args.save, exist_ok=True)
            out = os.path.join(args.save, f"rtb-{t['deviceId'].hex()}.token")
            fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "wb") as f:
                f.write(bytes(t["token"]))
            print(f"  saved         : {out}   (import it in the app; keep it private)")
        if t["wire"]:
            print(f"  full wire     : {t['wire'].hex()}   (18c0 + token; for a replay re-seal)")
        print()

    if len(tokens) > 1:
        print("Multiple devices/pairings in this log — match by deviceId to pick yours.")


if __name__ == "__main__":
    main()
