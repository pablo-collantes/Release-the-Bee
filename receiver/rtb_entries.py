"""
rtb_entries.py: the receiver's entries (docs/SERVER_API.md), kept as JSON files.

Each recording <stem>.aac gets <stem>.json next to it (and <stem>.m4a once processed).
The index is rebuilt from disk at startup, so the recordings folder is the whole state:
copy it, back it up, or delete an entry by deleting its files. Recordings saved before
entries existed (no .json) are picked up as summaries named after their file. Entries
deleted through the API are remembered in deleted.json for 90 days, so phones that sync
with ?since= learn about them.

A single worker thread processes queued entries, oldest first. Standard library only.
"""
import datetime
import json
import os
import re
import threading
import time
import traceback

import rtb_process

STATUSES = ("recording", "queued", "transcribing", "diarizing", "summarizing", "done", "failed")
BODIES = ("transcript", "summary", "command")
LEGACY_NAME = re.compile(r"^(\d{6})_([0-9A-Za-z]+)\.aac$")
DATE_DIR = re.compile(r"^\d{4}-\d{2}-\d{2}$")
DELETED_FILE = "deleted.json"
WORDS_FILE = "words.json"
TOMBSTONE_DAYS = 90
FILES = (".aac", ".m4a", ".json", ".json.tmp", ".m4a.tmp")    # everything a recording leaves on disk


def now_ms():
    return int(time.time() * 1000)


def new_entry(id, device, kind="summary", trigger=None, parent=None, started_at=None):
    return {
        "id": id, "device": device, "kind": kind if kind in ("summary", "command") else "summary",
        "parent": parent, "trigger": trigger, "started_at": started_at, "ended_at": None,
        "duration_s": None, "status": "recording", "error": None, "title": None, "preview": None,
        "speakers": None, "transcript": None, "summary": None, "command": None, "audio": None,
        "updated_at": now_ms(),
    }


class EntryStore:
    """All entries, in memory and on disk. Safe to call from the event loop and the
    worker thread. on_change(entry) is called after every status change, edit and
    delete (status "deleted"), from whichever thread made it."""

    def __init__(self, root, on_change=None):
        self.root = root
        self.on_change = on_change
        self.lock = threading.Condition()
        self.entries = {}         # id -> entry dict (with bodies)
        self.stems = {}           # id -> path without extension
        self.gen = {}             # id -> bumped when the entry is reopened or reprocessed
        self.queue = set()        # ids waiting for the worker
        self.deleted = {}         # id -> ms when it was deleted (tombstones)

    # ------------------------------------------------------------------ disk

    def load(self, recent_s=0):
        """Index every recording under root. Returns the ids of entries that look like
        they were still recording (audio written in the last recent_s seconds): the
        caller gives the phone a chance to resume those before processing them."""
        self._load_deleted()
        unfinished = []
        for dirpath, _dirs, files in os.walk(self.root):
            for name in sorted(files):
                if not name.endswith(".aac"):
                    continue
                stem = os.path.join(dirpath, name[:-4])
                entry = self._read(stem + ".json") or self._legacy(stem)
                if entry is None:
                    continue
                if entry["id"] in self.entries:                 # same id twice: keep the first
                    continue
                if entry["id"] in self.deleted:                 # deleted while still recording
                    remove_files(stem)
                    continue
                recent = time.time() - os.path.getmtime(stem + ".aac") < recent_s
                st = entry["status"]
                if st == "recording" or (recent and st == "queued" and entry.get("_legacy")):
                    if recent:
                        entry["status"] = "recording"
                        unfinished.append(entry["id"])
                    else:
                        entry["status"] = "queued"
                        self._fill_end(entry, stem)
                elif st in ("transcribing", "diarizing", "summarizing"):
                    entry["status"] = "queued"                  # interrupted by a restart
                entry.pop("_legacy", None)
                self.entries[entry["id"]] = entry
                self.stems[entry["id"]] = stem
                self.gen[entry["id"]] = 0
                if entry["status"] == "queued":
                    self.queue.add(entry["id"])
                self._write(entry["id"])
        return unfinished

    def _read(self, path):
        try:
            with open(path, encoding="utf-8") as f:
                e = json.load(f)
        except (OSError, ValueError):
            return None
        if not isinstance(e, dict) or not e.get("id") or e.get("status") not in STATUSES:
            return None
        base = new_entry(e["id"], e.get("device"))
        base.update(e)
        if isinstance(base["command"], dict):
            base["command"].setdefault("done", False)      # processed before done existed
        return base

    def _legacy(self, stem):
        """A recording from before entries: <device>/<yyyy-mm-dd>/<HHMMSS>_<session8>.aac."""
        name = os.path.basename(stem) + ".aac"
        date_dir = os.path.basename(os.path.dirname(stem))
        m = LEGACY_NAME.match(name)
        if not m or not DATE_DIR.match(date_dir):
            return None
        t = datetime.datetime.strptime(f"{date_dir} {m.group(1)}", "%Y-%m-%d %H%M%S")
        e = new_entry(os.path.basename(stem), os.path.basename(os.path.dirname(os.path.dirname(stem))),
                      started_at=int(t.timestamp() * 1000))
        e["status"] = "queued"
        e["_legacy"] = True
        self._fill_end(e, stem)
        return e

    @staticmethod
    def _fill_end(e, stem):
        try:
            e["duration_s"] = rtb_process.adts_duration(stem + ".aac")
        except OSError:
            return
        if e.get("ended_at") is None and e.get("started_at"):
            e["ended_at"] = int(e["started_at"] + e["duration_s"] * 1000)
        e["audio"] = audio_info(stem)

    def _write(self, id):
        e = self.entries[id]
        path = self.stems[id] + ".json"
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(e, f, ensure_ascii=False, indent=1)
        os.replace(tmp, path)

    def _load_deleted(self):
        try:
            with open(os.path.join(self.root, DELETED_FILE), encoding="utf-8") as f:
                saved = json.load(f)
        except (OSError, ValueError):
            saved = {}
        if not isinstance(saved, dict):
            saved = {}
        cutoff = now_ms() - TOMBSTONE_DAYS * 86400 * 1000
        self.deleted = {k: v for k, v in saved.items() if isinstance(v, int) and v >= cutoff}
        if len(self.deleted) != len(saved):
            self._write_deleted()

    def _write_deleted(self):
        path = os.path.join(self.root, DELETED_FILE)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.deleted, f)
        os.replace(tmp, path)

    # ------------------------------------------------------------------ reads

    def get(self, id):
        with self.lock:
            e = self.entries.get(id)
            return json.loads(json.dumps(e)) if e else None

    def list(self, since=None):
        """Entries without their bodies, newest first. With since, only entries updated
        at or after that time (server ms)."""
        with self.lock:
            out = [light(e) for e in self.entries.values()
                   if since is None or (e.get("updated_at") or 0) >= since]
        out.sort(key=lambda e: e.get("started_at") or 0, reverse=True)
        return out

    def deleted_since(self, since):
        """Ids deleted at or after since (server ms), oldest first. Without since, none:
        a full list already leaves them out."""
        if since is None:
            return []
        with self.lock:
            return [id for id, t in sorted(self.deleted.items(), key=lambda kv: kv[1]) if t >= since]

    def is_deleted(self, id):
        return id in self.deleted

    def note_titles(self, limit=30):
        """Titles of the user's notes, from finished CREATE_NOTE and ADD_TO_NOTE commands:
        most recently started first, each once (ignoring case)."""
        with self.lock:
            notes = [(e.get("started_at") or 0, e["command"].get("note")) for e in self.entries.values()
                     if e["status"] == "done" and isinstance(e.get("command"), dict)
                     and e["command"].get("action") in ("CREATE_NOTE", "ADD_TO_NOTE")]
        out, seen = [], set()
        for _, note in sorted(notes, key=lambda n: n[0], reverse=True):
            if not isinstance(note, str) or not note.strip() or note.strip().casefold() in seen:
                continue
            seen.add(note.strip().casefold())
            out.append(note.strip())
            if len(out) == limit:
                break
        return out

    def words(self):
        """The user's word list (rtb_process.clean_words), oldest first."""
        try:
            with open(os.path.join(self.root, WORDS_FILE), encoding="utf-8") as f:
                saved = json.load(f)
        except (OSError, ValueError):
            return []
        return rtb_process.clean_words(saved.get("words") if isinstance(saved, dict) else None)

    def set_words(self, words):
        """Replace the word list; returns it as kept."""
        words = rtb_process.clean_words(words)
        path = os.path.join(self.root, WORDS_FILE)
        with self.lock:
            with open(path + ".tmp", "w", encoding="utf-8") as f:
                json.dump({"words": words}, f, ensure_ascii=False, indent=1)
            os.replace(path + ".tmp", path)
        return words

    def audio_file(self, id):
        """(path, mime) of the entry's audio: the M4A remux if it exists, else the raw AAC."""
        stem = self.stems.get(id)
        if stem is None:
            return None, None
        if os.path.exists(stem + ".m4a"):
            return stem + ".m4a", "audio/mp4"
        if os.path.exists(stem + ".aac"):
            return stem + ".aac", "audio/aac"
        return None, None

    def find_stem(self, stem):
        with self.lock:
            for id, s in self.stems.items():
                if s == stem:
                    return id
        return None

    # ------------------------------------------------------------------ writes

    def create(self, entry, stem):
        with self.lock:
            self.entries[entry["id"]] = entry
            self.stems[entry["id"]] = stem
            self.gen[entry["id"]] = 0
            self._write(entry["id"])
            snapshot = light(entry)
        self._notify(snapshot)

    def update(self, id, gen=None, notify=False, **fields):
        """Change an entry and save it. With gen, the change is dropped (returns False)
        if the entry was reopened or reprocessed since the caller started. Phones are told
        about status changes, or about any change with notify."""
        with self.lock:
            e = self.entries.get(id)
            if e is None or (gen is not None and self.gen.get(id) != gen):
                return False
            status_changed = "status" in fields and fields["status"] != e["status"]
            cmd = fields.get("command")
            if isinstance(cmd, dict) and "done" not in cmd:
                # done is the user's, not the model's: reprocessing keeps it.
                old = e.get("command")
                fields["command"] = dict(cmd, done=bool(isinstance(old, dict) and old.get("done")))
            e.update(fields)
            e["updated_at"] = now_ms()
            if e["status"] == "queued":
                self.queue.add(id)
                self.lock.notify_all()
            else:
                self.queue.discard(id)
            self._write(id)
            snapshot = light(e)
        if status_changed or notify:
            self._notify(snapshot)
        return True

    def edit_command(self, id, changes):
        """The user changed a command (done, when, text, note): merge the changes in and
        tell the phones. Returns the full entry, or None if it has no command body."""
        with self.lock:                         # one step: the worker may be saving a new body
            e = self.entries.get(id)
            if e is None or not isinstance(e.get("command"), dict):
                return None
            cmd = dict(e["command"], **changes)
            # The list shows the title and preview: keep them in step with a new time or text.
            text = cmd.get("text") or ""
            self.update(id, notify=True, command=cmd, title=rtb_process.command_title(cmd),
                        preview=rtb_process.preview_of(text + (f" ({cmd['when']})" if cmd.get("when") else "")))
            return self.get(id)

    def delete(self, id, keep_files=False):
        """Forget an entry and delete its files, and stop the worker if it's on it. Its
        id is remembered (deleted_since). keep_files: the session is still open, and the
        recorder deletes the files when it finishes. Returns False if there's no entry."""
        with self.lock:
            e = self.entries.pop(id, None)
            if e is None:
                return False
            stem = self.stems.pop(id)
            self.gen.pop(id, None)                       # the worker's next check or save fails
            self.queue.discard(id)
            self.deleted[id] = now_ms()
            self._write_deleted()
            if not keep_files:
                remove_files(stem)
        self._notify({"id": id, "kind": e["kind"], "status": "deleted", "title": None})
        return True

    def reopen(self, id, **fields):
        """The phone resumed a recording we had closed: back to recording, and anything
        the worker is doing with it is dropped."""
        with self.lock:
            self.gen[id] = self.gen.get(id, 0) + 1
        self.update(id, status="recording", **fields)

    def rekey(self, old, new):
        """A legacy entry (id = file stem) turns out to be a live session: use its uuid."""
        with self.lock:
            e = self.entries.pop(old)
            stem = self.stems.pop(old)
            self.gen.pop(old, None)
            self.queue.discard(old)
            e["id"] = new
            self.entries[new], self.stems[new], self.gen[new] = e, stem, 0
            self._write(new)

    def reprocess(self, id):
        """Queue an entry again. Returns False if it's still recording."""
        with self.lock:
            e = self.entries.get(id)
            if e is None or e["status"] == "recording":
                return False
            self.gen[id] = self.gen.get(id, 0) + 1
        self.update(id, status="queued", error=None)
        return True

    def _notify(self, entry):
        if self.on_change:
            try:
                self.on_change(entry)
            except Exception:                                  # a push must never break a write
                traceback.print_exc()

    # ------------------------------------------------------------------ worker side

    def next_job(self, timeout=None):
        """Block until something is queued; returns (id, gen, entry, aac path), oldest first."""
        with self.lock:
            if not self.queue:
                self.lock.wait(timeout)
            if not self.queue:
                return None
            id = min(self.queue, key=lambda i: (self.entries[i].get("started_at") or 0, i))
            self.queue.discard(id)
            return id, self.gen[id], json.loads(json.dumps(self.entries[id])), self.stems[id] + ".aac"


def light(e):
    """The list form of an entry: everything but the bodies, except a command's, which
    is small and is what the app shows."""
    out = {k: v for k, v in e.items() if k not in BODIES}
    for k in BODIES:
        out[k] = None
    if e.get("kind") == "command":
        out["command"] = e.get("command")
    return out


def remove_files(stem, exts=FILES):
    for ext in exts:
        try:
            os.remove(stem + ext)
        except FileNotFoundError:
            pass


def audio_info(stem):
    for ext, mime in ((".m4a", "audio/mp4"), (".aac", "audio/aac")):
        if os.path.exists(stem + ext):
            return {"bytes": os.path.getsize(stem + ext), "mime": mime}
    return None


class Worker(threading.Thread):
    """Processes one entry at a time with rtb_process.process()."""

    def __init__(self, store, args):
        super().__init__(daemon=True, name="rtb-worker")
        self.store = store
        self.args = args

    def run(self):
        while True:
            job = self.store.next_job(timeout=60)
            if job:
                self.handle(*job)

    def handle(self, id, gen, entry, aac):
        store = self.store

        def check():
            if store.gen.get(id) != gen:
                raise rtb_process.Cancelled()

        def save(**fields):
            if fields.get("status") == "done":
                fields["audio"] = audio_info(aac[:-4])
            if not store.update(id, gen, **fields):
                raise rtb_process.Cancelled()

        rtb_process.log(f"processing {entry['kind']} {id}")
        notes = store.note_titles() if entry["kind"] == "command" else ()
        words = store.words()
        try:
            if not os.path.exists(aac) or os.path.getsize(aac) == 0:
                store.update(id, gen, status="done", duration_s=0, audio=None)
                return
            rtb_process.process(self.args, aac, entry, save, check, notes=notes, words=words)
        except rtb_process.Cancelled:
            rtb_process.log(f"processing {id} cancelled (entry changed)")
        except Exception as e:
            traceback.print_exc()
            store.update(id, gen, status="failed", error=f"{e.__class__.__name__}: {e}"[:500])
        finally:
            if store.is_deleted(id):                     # deleted mid-run: what we wrote since
                remove_files(aac[:-4], (".m4a", ".m4a.tmp"))
