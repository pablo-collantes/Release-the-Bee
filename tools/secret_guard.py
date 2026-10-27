#!/usr/bin/env python3
"""Keep RTB seal tokens (and other per-device secrets) out of git.

An RTB seal token is 108 bytes: b"SKV1" + deviceId(8) + ctrKey(16) + signature(80).
It unlocks one physical device's audio and must never be published. This scanner
looks for it in every form it tends to get pasted in (raw bytes, hex, base64), and
for any extra fixed strings listed in .git/info/rtb-denylist. That file lives inside
.git, so it is never committed or pushed: put your own ctrKey, deviceId and BLE MAC
there, one per line.

  tools/secret_guard.py --staged        # pre-commit hook
  tools/secret_guard.py --range A..B    # pre-push hook (every commit in the range)
  tools/secret_guard.py --all           # every tracked file (CI)
  tools/secret_guard.py FILE...         # ad-hoc

Exit status 1 if anything is found. Findings name the file and the reason, never the
matched bytes.
"""
import base64
import fnmatch
import os
import re
import subprocess
import sys

MAGIC = b"SKV1"
TOKEN_LEN = 108

# Paths that must never be tracked, whatever they contain.
BLOCKED_PATHS = [
    "beelocal/*",
    "*.token", "*.token.*",
    "*btsnoop*",
    "bugreport*.zip",
    "*.keystore", "*.jks",
    "NOTES.md",
    "local.properties",
]

HEX_MAGIC = MAGIC.hex()                                   # "534b5631"
HEX_NOISE = re.compile(r"0x|[\s:,\"'+\\_]")               # separators people paste hex with
B64_TOKEN = re.compile(rb"U0tWM[A-Za-z0-9+/]{100,}")      # base64 of b"SKV1..." (108 B -> 144 chars)
HEX_RUN = re.compile(r"[0-9a-f]*")


def repo_root():
    out = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else os.getcwd()


def load_denylist(root):
    path = os.path.join(root, ".git", "info", "rtb-denylist")
    entries = []
    if not os.path.exists(path):
        return entries
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = line.strip().lower()
            if not s or s.startswith("#"):
                continue
            compact = re.sub(r"[\s:\-]", "", s)
            if len(compact) < 8:
                continue                                  # too short to match without false positives
            entries.append((s, compact))
    return entries


def path_blocked(path):
    base = os.path.basename(path)
    for pat in BLOCKED_PATHS:
        if fnmatch.fnmatch(path, pat) or fnmatch.fnmatch(base, pat):
            return pat
    return None


def scan_bytes(data, denylist):
    """Return a list of reasons this content is unsafe (empty if clean)."""
    reasons = []
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        text = None

    if text is None:
        # Binary: a raw token is the magic followed by the rest of the 108 bytes.
        i = data.find(MAGIC)
        while i != -1:
            if len(data) - i >= TOKEN_LEN:
                reasons.append("raw seal token (SKV1 magic + 104 bytes)")
                break
            i = data.find(MAGIC, i + 1)
        for _, compact in denylist:
            if re.fullmatch(r"[0-9a-f]+", compact) and len(compact) % 2 == 0:
                if bytes.fromhex(compact) in data:
                    reasons.append("denylisted value (binary)")
                    break
        return reasons

    low = text.lower()
    # Hex: "534b5631" followed by enough hex to carry deviceId + ctrKey.
    i = low.find(HEX_MAGIC)
    while i != -1:
        tail = HEX_NOISE.sub("", low[i + len(HEX_MAGIC): i + 1200])
        if len(HEX_RUN.match(tail).group(0)) >= 48:
            reasons.append("hex seal token (534b5631 + key material)")
            break
        i = low.find(HEX_MAGIC, i + 1)
    if B64_TOKEN.search(data):
        reasons.append("base64 seal token")
    if denylist:
        squashed = re.sub(r"[\s:\-\"'+,_]", "", low)
        for plain, compact in denylist:
            if plain in low or compact in squashed:
                reasons.append("denylisted value (.git/info/rtb-denylist)")
                break
    return reasons


def git_bytes(*args):
    return subprocess.run(["git", *args], capture_output=True, check=True).stdout


def staged_items():
    names = git_bytes("diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z").split(b"\0")
    for n in filter(None, names):
        p = n.decode()
        yield p, lambda p=p: git_bytes("show", f":{p}")


def range_items(rev_range):
    commits = git_bytes("rev-list", *rev_range.split()).decode().split()
    for c in commits:
        names = git_bytes("diff-tree", "-r", "--root", "--no-commit-id", "--name-only",
                          "--diff-filter=ACMR", "-z", c).split(b"\0")
        for n in filter(None, names):
            p = n.decode()
            yield f"{c[:9]}:{p}", lambda c=c, p=p: git_bytes("show", f"{c}:{p}")


def tracked_items():
    names = git_bytes("ls-files", "-z").split(b"\0")
    for n in filter(None, names):
        p = n.decode()
        if os.path.isfile(p):
            yield p, lambda p=p: open(p, "rb").read()


def file_items(paths):
    for p in paths:
        yield p, lambda p=p: open(p, "rb").read()


def main(argv):
    root = repo_root()
    os.chdir(root)
    denylist = load_denylist(root)
    if not argv or argv[0] == "--staged":
        items = staged_items()
    elif argv[0] == "--range":
        items = range_items(argv[1])
    elif argv[0] == "--all":
        items = tracked_items()
    else:
        items = file_items(argv)

    findings = []
    for label, read in items:
        path = label.split(":", 1)[-1]
        pat = path_blocked(path)
        if pat:
            findings.append((label, f"path matches blocked pattern {pat!r}"))
            continue
        for reason in scan_bytes(read(), denylist):
            findings.append((label, reason))

    if findings:
        print("secret_guard: refusing — possible RTB secret(s):", file=sys.stderr)
        for label, reason in findings:
            print(f"  {label}: {reason}", file=sys.stderr)
        print("Remove them (a seal token belongs in the app, imported at runtime), "
              "then try again.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
