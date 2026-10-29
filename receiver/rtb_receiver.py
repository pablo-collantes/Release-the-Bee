#!/usr/bin/env python3
"""
rtb_receiver.py: receive RTB audio from the Release the Bee app, save it, and turn each
recording into an entry with a transcript and a summary (or a command).

Accepts the app's WebSocket stream (rtb-stream/1, docs/STREAM_PROTOCOL.md) and,
optionally, the raw-TCP stream. Each session is written to

    <out>/<device>/<YYYY-MM-DD>/<HHMMSS>_<session>.aac      (+ .json entry, + .m4a)

The same port serves the entries over plain HTTP (docs/SERVER_API.md), and pushes
{"type":"entry",…} to connected phones whenever one changes. Receiving needs only the
Python standard library (3.9+). Processing uses whatever you configure: ffmpeg, a
whisper.cpp server, sherpa-onnx for diarization, an OpenAI-compatible LLM. Each step
is skipped if it isn't configured. See rtb_process.py.

  python3 receiver/rtb_receiver.py                          # WebSocket + HTTP on 0.0.0.0:8740
  python3 receiver/rtb_receiver.py --ws 100.101.102.103:8740 # bind to one interface (e.g. your VPN IP)
  python3 receiver/rtb_receiver.py --token SECRET           # require "Authorization: Bearer SECRET"
  python3 receiver/rtb_receiver.py --tcp 0.0.0.0:8741       # also accept the raw-TCP sink
  python3 receiver/rtb_receiver.py --whisper-url http://127.0.0.1:8009 --llm-url http://127.0.0.1:8742/v1
  python3 receiver/rtb_receiver.py --on-session-end 'my-transcribe {path}'
"""
import argparse
import asyncio
import base64
import datetime
import hashlib
import hmac
import json
import os
import re
import shlex
import socket
import struct
import sys
import urllib.parse
import uuid

import rtb_entries
import rtb_process

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_MESSAGE = 1 << 20
MAX_BODY = 1 << 16                           # HTTP request bodies (PATCH)
AUDIO_HEADER = struct.Struct(">IQ")          # seq u32, captured_at_ms u64
ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def log(msg):
    print(f"{datetime.datetime.now():%H:%M:%S}  {msg}", flush=True)


# --------------------------------------------------------------------------- WebSocket (RFC 6455, server side)

class WsClosed(Exception):
    pass


def ws_frame(opcode, payload=b""):
    n = len(payload)
    if n < 126:
        head = struct.pack(">BB", 0x80 | opcode, n)
    elif n < 1 << 16:
        head = struct.pack(">BBH", 0x80 | opcode, 126, n)
    else:
        head = struct.pack(">BBQ", 0x80 | opcode, 127, n)
    return head + payload


def unmask(payload, mask):
    if not payload:
        return payload
    m = (mask * (len(payload) // 4 + 1))[: len(payload)]
    return (int.from_bytes(payload, "big") ^ int.from_bytes(m, "big")).to_bytes(len(payload), "big")


async def read_request(reader):
    """Request line and headers of the next HTTP request (an upgrade or a plain request)."""
    head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
    lines = head.decode("latin-1").split("\r\n")
    parts = lines[0].split(" ")
    method, target = (parts[0], parts[1]) if len(parts) >= 3 else ("", "/")
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return method, target, headers


def authorized(headers, token):
    if not token:
        return True
    given = headers.get("authorization", "").encode()
    return hmac.compare_digest(given, f"Bearer {token}".encode())


async def ws_accept(writer, headers):
    accept = base64.b64encode(hashlib.sha1((headers["sec-websocket-key"] + WS_GUID).encode()).digest()).decode()
    writer.write(
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n".encode()
    )
    await writer.drain()


async def ws_message(reader, writer):
    """Next complete text (1) or binary (2) message as (opcode, bytes). Answers pings."""
    buf = bytearray()
    msg_op = None
    while True:
        b1, b2 = await reader.readexactly(2)
        fin, op, masked, n = b1 & 0x80, b1 & 0x0F, b2 & 0x80, b2 & 0x7F
        if n == 126:
            n = struct.unpack(">H", await reader.readexactly(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", await reader.readexactly(8))[0]
        if n > MAX_MESSAGE:
            raise WsClosed("frame too large")
        mask = await reader.readexactly(4) if masked else None
        payload = await reader.readexactly(n)
        if mask:
            payload = unmask(payload, mask)
        if op == 0x8:                                   # close: echo it and stop
            writer.write(ws_frame(0x8, payload[:2]))
            raise WsClosed("closed by client")
        if op == 0x9:                                   # ping
            writer.write(ws_frame(0xA, payload))
            continue
        if op == 0xA:                                   # pong
            continue
        if op in (0x1, 0x2):
            msg_op, buf = op, bytearray(payload)
        elif op == 0x0 and msg_op is not None:
            buf += payload
        else:
            raise WsClosed(f"unexpected opcode {op}")
        if len(buf) > MAX_MESSAGE:
            raise WsClosed("message too large")
        if fin:
            return msg_op, bytes(buf)


# --------------------------------------------------------------------------- sessions on disk

class Recorder:
    """Maps sessions to files and entries. A session the phone drops stays open for
    --finish-after seconds, so a reconnect (resumed=true) keeps appending to the same
    file. When a session finishes, its entry is queued for processing, or its files are
    deleted if the entry was deleted meanwhile."""

    def __init__(self, out_dir, store, hook=None, finish_after=600):
        self.out = out_dir
        self.store = store
        self.hook = hook
        self.finish_after = finish_after
        self.open_sessions = {}  # session id -> {"path", "parent", "last_at", "deleted"}
        self.pending = {}        # session id -> TimerHandle that finishes it

    def is_open(self, session):
        return session in self.open_sessions

    def parent_of(self, session):
        s = self.open_sessions.get(session)
        return s and s["parent"]

    def open(self, device, session, started_at_ms, kind="summary", trigger=None, parent=None):
        timer = self.pending.pop(session, None)
        if timer:
            timer.cancel()
        s = self.open_sessions.get(session)
        if s:
            return s["path"]
        if self.store.is_deleted(session):               # resumed after the user deleted it
            self.open_sessions[session] = {"path": os.devnull, "parent": parent, "last_at": None, "deleted": True}
            return os.devnull
        meta = {"kind": kind, "trigger": trigger, "parent": parent}
        entry = self.store.get(session)
        if entry:                                        # resumed after we had finished it
            stem = self.store.stems[session]
            self._drop_stale(stem)
            self.store.reopen(session)
        else:
            t = datetime.datetime.fromtimestamp(started_at_ms / 1000)
            safe_dev = "".join(c for c in (device or "unknown") if c.isalnum())[:32] or "unknown"
            stem = os.path.join(self.out, safe_dev, f"{t:%Y-%m-%d}", f"{t:%H%M%S}_{session[:8]}")
            os.makedirs(os.path.dirname(stem), exist_ok=True)
            old = self.store.find_stem(stem)
            if old:                                      # a legacy file this session already wrote
                timer = self.pending.pop(old, None)      # (adopted at startup under its file name)
                if timer:
                    timer.cancel()
                self.open_sessions.pop(old, None)
                self.store.rekey(old, session)
                self._drop_stale(stem)
                self.store.reopen(session, **meta)
            else:
                self.store.create(rtb_entries.new_entry(session, device, started_at=started_at_ms, **meta), stem)
        self.open_sessions[session] = {"path": stem + ".aac", "parent": parent, "last_at": None, "deleted": False}
        return stem + ".aac"

    @staticmethod
    def _drop_stale(stem):
        if os.path.exists(stem + ".m4a"):                # the old remux is missing the new audio
            os.remove(stem + ".m4a")

    def adopt(self, entry_id):
        """At startup: an entry whose file was still growing. Wait for the phone to resume
        it like any dropped session."""
        stem = self.store.stems[entry_id]
        self.open_sessions[entry_id] = {"path": stem + ".aac", "parent": None, "last_at": None, "deleted": False}
        self.dropped(entry_id)

    def delete(self, session):
        """Delete an entry. If its session is still open, audio may keep arriving: the
        entry goes now, its files when the session finishes. Returns False if unknown."""
        s = self.open_sessions.get(session)
        if s is None:
            return self.store.delete(session)
        s["deleted"] = True
        return self.store.delete(session, keep_files=True)

    def touch(self, session, captured_at_ms):
        s = self.open_sessions.get(session)
        if s and captured_at_ms:
            s["last_at"] = captured_at_ms

    def dropped(self, session):
        """Connection lost mid-session: finish it later unless the phone resumes it."""
        if session in self.open_sessions and session not in self.pending:
            loop = asyncio.get_running_loop()
            self.pending[session] = loop.call_later(self.finish_after, self.finish, session, "not resumed")

    def finish(self, session, reason):
        timer = self.pending.pop(session, None)
        if timer:
            timer.cancel()
        s = self.open_sessions.pop(session, None)
        if s is None:
            return
        path = s["path"]
        if s["deleted"] or self.store.is_deleted(session):
            log(f"session finished ({reason}), deleted: {path}")
            if path != os.devnull:
                rtb_entries.remove_files(path[:-4])
            return
        size = os.path.getsize(path) if os.path.exists(path) else 0
        log(f"session finished ({reason}): {path} ({size / 1024:.0f} KiB)")
        duration = rtb_process.adts_duration(path) if size else 0.0
        entry = self.store.get(session) or {}
        ended = s["last_at"] or (entry.get("started_at") or 0) + int(duration * 1000) or rtb_entries.now_ms()
        self.store.update(session, status="queued" if size else "done", ended_at=ended, duration_s=duration,
                          audio=rtb_entries.audio_info(path[:-4]))
        if self.hook and size:
            asyncio.get_running_loop().create_task(self.run_hook(path))

    async def run_hook(self, path):
        cmd = self.hook.replace("{path}", shlex.quote(path))
        proc = await asyncio.create_subprocess_shell(cmd)
        rc = await proc.wait()
        log(f"hook exited {rc}: {cmd}")


class Stream:
    """One phone connection: which session its audio goes to right now.

    A command recorded during a summary arrives as a session_start with parent set.
    Audio then goes to the command until its session_end, and back to the parent,
    which never closed (docs/STREAM_PROTOCOL.md, "Nested recordings")."""

    def __init__(self, rec, peer="?"):
        self.rec = rec
        self.peer = peer
        self.device = None
        self.stack = []          # open sessions on this connection, innermost (receiving audio) last
        self.f = None            # file of stack[-1]
        self.frames = 0
        self.gaps = 0
        self.last_seq = None

    def current(self):
        return self.stack[-1] if self.stack else None

    def _switch(self):
        """Point self.f at the innermost session's file."""
        if self.f:
            self.f.close()
            self.f = None
        if self.stack:
            self.f = open(self.rec.open_sessions[self.stack[-1]]["path"], "ab")

    def hello(self, msg):
        self.device = msg.get("device")
        log(f"{self.peer}: {msg.get('app', '?')} for RTB {self.device} ({msg.get('codec')}, {msg.get('protocol')})")

    def session_start(self, msg):
        session = msg.get("session") or str(uuid.uuid4())
        parent = msg.get("parent") or None
        if session in self.stack:                       # repeated start: inner sessions are over
            while self.stack[-1] != session:
                self.rec.dropped(self.stack.pop())
        else:
            if parent and parent in self.stack:
                while self.stack[-1] != parent:
                    self.rec.dropped(self.stack.pop())
            else:
                # A new top-level recording (or a command whose parent was opened on an
                # earlier connection). Whatever was open here never got a session_end.
                while self.stack:
                    self.rec.dropped(self.stack.pop())
            path = self.rec.open(msg.get("device") or self.device, session,
                                 msg.get("started_at") or rtb_entries.now_ms(),
                                 kind=msg.get("kind") or "summary", trigger=msg.get("trigger"), parent=parent)
            self.stack.append(session)
            log(f"{self.peer}: {'resumed' if msg.get('resumed') else 'new'} {msg.get('kind') or 'summary'}"
                f"{' (inside ' + parent[:8] + ')' if parent else ''} session -> {path}")
        self.frames, self.gaps = 0, 0
        self._switch()

    def session_end(self, msg):
        session = msg.get("session") or self.current()
        if not session:
            return
        log(f"{self.peer}: session {session[:8]} ended ({msg.get('reason')}); {self.frames} frames, {self.gaps} gaps")
        parent = self.rec.parent_of(session)
        if session in self.stack:
            self.stack.remove(session)
        if self.f:                                      # flush before the size check in finish()
            self.f.close()
            self.f = None
        if msg.get("discard"):                          # the phone threw it away: delete it
            self.rec.delete(session)
        self.rec.finish(session, msg.get("reason", "ended"))
        if not self.stack and parent and self.rec.is_open(parent):
            # The command ended; its summary is still open (maybe from before a reconnect).
            self.rec.open(self.device, parent, rtb_entries.now_ms())
            self.stack.append(parent)
        self._switch()

    def audio(self, data):
        if len(data) <= AUDIO_HEADER.size:
            return
        seq, captured_at = AUDIO_HEADER.unpack_from(data)
        if not self.stack:                              # audio with no session_start: make one
            session = str(uuid.uuid4())
            path = self.rec.open(self.device, session, captured_at or rtb_entries.now_ms())
            self.stack.append(session)
            self._switch()
            log(f"{self.peer}: audio without a session; writing to {path}")
        if self.last_seq is not None and seq != (self.last_seq + 1) & 0xFFFFFFFF:
            self.gaps += 1
            if self.gaps <= 3:
                log(f"{self.peer}: seq jumped {self.last_seq} -> {seq}")
        self.last_seq = seq
        self.f.write(data[AUDIO_HEADER.size:])
        self.rec.touch(self.stack[-1], captured_at)
        self.frames += 1
        if self.frames % 8 == 0:
            self.f.flush()

    def close(self):
        """Disconnected: every open session waits for the phone to resume it."""
        if self.f:
            self.f.close()
            self.f = None
        while self.stack:
            self.rec.dropped(self.stack.pop())


# --------------------------------------------------------------------------- HTTP API

class Response:
    def __init__(self, status, body=b"", ctype="application/json", headers=None, file=None):
        self.status = status
        self.body = body
        self.ctype = ctype
        self.headers = headers or {}
        self.file = file             # (path, start, end inclusive) to stream instead of body


def json_response(status, obj):
    return Response(status, json.dumps(obj, ensure_ascii=False).encode())


REASONS = {200: "OK", 202: "Accepted", 206: "Partial Content", 400: "Bad Request", 401: "Unauthorized",
           404: "Not Found", 405: "Method Not Allowed", 409: "Conflict", 413: "Content Too Large",
           416: "Range Not Satisfiable", 426: "Upgrade Required", 500: "Internal Server Error"}


def route(method, target, headers, store, token, body=b"", rec=None):
    """Answer one plain HTTP request (docs/SERVER_API.md). Pure: no sockets, so tests
    can call it directly. rec is the Recorder, so a delete knows which sessions are
    still open (without it, the files go right away)."""
    if not authorized(headers, token):
        r = json_response(401, {"error": "unauthorized"})
        r.headers["WWW-Authenticate"] = "Bearer"
        return r
    if len(body) > MAX_BODY:
        return json_response(413, {"error": "body over 64 KiB"})
    url = urllib.parse.urlsplit(target)
    parts = [p for p in url.path.split("/") if p]
    if not parts:
        return json_response(426, {"error": "this is the WebSocket URL; the API is under /api/entries"})
    if parts[0] != "api" or len(parts) < 2 or parts[1] != "entries" or len(parts) > 4:
        return json_response(404, {"error": "not found"})
    if len(parts) == 2:
        if method not in ("GET", "HEAD"):
            return json_response(405, {"error": "method not allowed"})
        query = urllib.parse.parse_qs(url.query)
        since = None
        if "since" in query:
            try:
                since = int(query["since"][0])
            except ValueError:
                return json_response(400, {"error": "since must be ms since epoch"})
        now = rtb_entries.now_ms()          # before reading: a change made meanwhile is sent twice, not missed
        return json_response(200, {"entries": store.list(since), "deleted": store.deleted_since(since), "now": now})
    id = urllib.parse.unquote(parts[2])
    entry = store.get(id) if ID.match(id) else None
    if entry is None:
        if len(parts) == 3 and method == "DELETE" and store.is_deleted(id):
            return json_response(200, {"id": id, "deleted": True})
        return json_response(404, {"error": "no such entry"})
    if len(parts) == 3:
        if method == "PATCH":
            try:
                changes = command_changes(body)
            except ValueError as e:
                return json_response(400, {"error": str(e)})
            entry = store.edit_command(id, changes)
            if entry is None:
                return json_response(409, {"error": "not a processed command"})
            return json_response(200, entry)
        if method == "DELETE":
            if rec:
                rec.delete(id)
            else:
                store.delete(id)
            return json_response(200, {"id": id, "deleted": True})
        if method not in ("GET", "HEAD"):
            return json_response(405, {"error": "method not allowed"})
        return json_response(200, entry)
    if parts[3] == "audio":
        if method not in ("GET", "HEAD"):
            return json_response(405, {"error": "method not allowed"})
        path, mime = store.audio_file(id)
        if not path:
            return json_response(404, {"error": "no audio"})
        return file_response(path, mime, headers.get("range"))
    if parts[3] == "reprocess":
        if method != "POST":
            return json_response(405, {"error": "method not allowed"})
        if not store.reprocess(id):
            return json_response(409, {"error": "still recording"})
        return json_response(202, {"id": id, "status": "queued"})
    return json_response(404, {"error": "not found"})


def command_changes(body):
    """The command fields a PATCH body changes. Unknown keys are ignored; anything
    malformed raises ValueError with the message for the 400."""
    try:
        obj = json.loads(body)
    except ValueError:
        raise ValueError("body must be JSON") from None
    cmd = obj.get("command") if isinstance(obj, dict) else None
    if not isinstance(cmd, dict):
        raise ValueError('body must be {"command": {…}}')
    changes = {}
    for k, v in cmd.items():
        if k == "done" and not isinstance(v, bool):
            raise ValueError("done must be true or false")
        if k == "text" and not isinstance(v, str):
            raise ValueError("text must be a string")
        if k == "note" and not (v is None or isinstance(v, str)):
            raise ValueError("note must be a string or null")
        if k == "when" and not (v is None or iso_with_offset(v)):
            raise ValueError("when must be ISO 8601 with an offset, or null")
        if k in ("done", "text", "note", "when"):
            changes[k] = v
    return changes


def iso_with_offset(v):
    if not isinstance(v, str):
        return False
    try:
        t = datetime.datetime.fromisoformat(v[:-1] + "+00:00" if v.endswith(("Z", "z")) else v)
    except ValueError:
        return False
    return t.tzinfo is not None


def file_response(path, mime, range_header):
    """The whole file, or one byte range of it (players seek with Range requests)."""
    size = os.path.getsize(path)
    headers = {"Accept-Ranges": "bytes"}
    if range_header:
        m = re.fullmatch(r"\s*bytes=(\d*)-(\d*)\s*", range_header)
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1))
                end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
            else:                                         # bytes=-N: the last N bytes
                start, end = max(0, size - int(m.group(2))), size - 1
            if start >= size or end < start:
                headers["Content-Range"] = f"bytes */{size}"
                return Response(416, b"", mime, headers)
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
            return Response(206, ctype=mime, headers=headers, file=(path, start, end))
        # A malformed or multi-range header: ignore it and send everything (RFC 9110 allows that).
    return Response(200, ctype=mime, headers=headers, file=(path, 0, size - 1))


async def send_response(writer, r, head_only=False):
    length = (r.file[2] - r.file[1] + 1) if r.file else len(r.body)
    lines = [f"HTTP/1.1 {r.status} {REASONS.get(r.status, '')}", f"Content-Type: {r.ctype}",
             f"Content-Length: {max(0, length)}", "Connection: close", "Cache-Control: no-store"]
    lines += [f"{k}: {v}" for k, v in r.headers.items()]
    writer.write(("\r\n".join(lines) + "\r\n\r\n").encode())
    if head_only:
        pass
    elif r.file:
        path, start, end = r.file
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                writer.write(chunk)
                left -= len(chunk)
                await writer.drain()
    else:
        writer.write(r.body)
    await writer.drain()


# --------------------------------------------------------------------------- handlers

class Hub:
    """Shared state: the recorder, the entry store, and the phones to push to."""

    def __init__(self, args, rec, store):
        self.args = args
        self.rec = rec
        self.store = store
        self.clients = set()          # writers of connected WebSockets

    def push(self, entry):
        msg = ws_frame(0x1, json.dumps({"type": "entry", "id": entry["id"], "kind": entry["kind"],
                                        "status": entry["status"], "title": entry.get("title")}).encode())
        for w in list(self.clients):
            if w.is_closing():
                self.clients.discard(w)
            else:
                w.write(msg)


async def handle_conn(reader, writer, hub):
    peer = (writer.get_extra_info("peername") or ("?",))[0]
    try:
        method, target, headers = await read_request(reader)
    except (asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError, ConnectionError):
        writer.close()
        return
    is_ws = headers.get("upgrade", "").lower() == "websocket" and "sec-websocket-key" in headers
    try:
        if not is_ws:
            length = headers.get("content-length", "0")
            # One byte past the limit is enough for route() to answer 413.
            n = min(int(length), MAX_BODY + 1) if length.isdigit() else 0
            body = await asyncio.wait_for(reader.readexactly(n), 10) if n else b""
            try:
                r = route(method, target, headers, hub.store, hub.args.token, body, hub.rec)
            except Exception as e:                                       # a bug: say so, don't hang up
                log(f"{peer}: {method} {target} failed: {e!r}")
                r = json_response(500, {"error": "internal error"})
            if r.status == 401:
                log(f"{peer}: {method} {urllib.parse.urlsplit(target).path} refused (wrong token)")
            await send_response(writer, r, head_only=method == "HEAD")
            return
        if not authorized(headers, hub.args.token):
            log(f"{peer}: refused WebSocket (wrong token)")
            writer.write(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            await writer.drain()
            is_ws = False                                   # so the finally below closes it
            return
        await ws_accept(writer, headers)
    except (asyncio.TimeoutError, asyncio.IncompleteReadError, ConnectionError):
        is_ws = False
        return
    finally:
        if not is_ws:
            writer.close()
    await handle_ws(reader, writer, peer, hub)


async def handle_ws(reader, writer, peer, hub):
    log(f"{peer}: connected")
    stream = Stream(hub.rec, peer)
    hub.clients.add(writer)
    try:
        while True:
            op, data = await ws_message(reader, writer)
            if op == 0x1:
                msg = json.loads(data)
                kind = msg.get("type")
                if kind == "hello":
                    stream.hello(msg)
                elif kind == "session_start":
                    stream.session_start(msg)
                elif kind == "session_end":
                    stream.session_end(msg)
                else:
                    log(f"{peer}: ignoring message type {kind!r}")
            else:
                stream.audio(data)
    except (asyncio.IncompleteReadError, ConnectionError, WsClosed, json.JSONDecodeError) as e:
        log(f"{peer}: disconnected ({e.__class__.__name__}{': ' + str(e) if str(e) else ''})")
    finally:
        hub.clients.discard(writer)
        stream.close()
        writer.close()


async def handle_tcp(reader, writer, hub):
    rec = hub.rec
    peer = writer.get_extra_info("peername")[0]
    session = str(uuid.uuid4())
    path = rec.open(f"tcp{peer.replace('.', '').replace(':', '')}", session, rtb_entries.now_ms())
    log(f"{peer}: raw TCP stream -> {path}")
    with open(path, "ab") as f:
        while True:
            chunk = await reader.read(65536)
            if not chunk:
                break
            f.write(chunk)
    writer.close()
    rec.finish(session, "tcp closed")


def _split(hostport):
    host, _, port = hostport.rpartition(":")
    return host or "0.0.0.0", int(port)


def _local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    try:                                                # the address used for outbound traffic
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.0.2.1", 9))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


async def serve(args):
    loop = asyncio.get_running_loop()
    os.makedirs(args.out, exist_ok=True)
    hub = None
    # Status changes come from the worker thread too: hop onto the loop to push them.
    store = rtb_entries.EntryStore(args.out, on_change=lambda e: loop.call_soon_threadsafe(hub.push, e))
    unfinished = store.load(recent_s=args.finish_after)
    rec = Recorder(args.out, store, args.on_session_end, args.finish_after)
    hub = Hub(args, rec, store)
    for id in unfinished:
        rec.adopt(id)
    have = rtb_process.tools(args)
    log(f"{len(store.entries)} entries, {len(store.queue)} queued; processing steps: "
        + (", ".join(k for k, v in have.items() if v) or "none"))
    if not args.no_process:
        rtb_entries.Worker(store, args).start()

    servers = []
    if args.ws:
        host, port = _split(args.ws)
        servers.append(await asyncio.start_server(lambda r, w: handle_conn(r, w, hub), host, port))
        log(f"WebSocket + HTTP listening on {host}:{port}{' (token required)' if args.token else ''}")
        if host in ("0.0.0.0", "::"):
            for ip in _local_ips():
                log(f"  app URL could be ws://{ip}:{port}/")
    if args.tcp:
        host, port = _split(args.tcp)
        servers.append(await asyncio.start_server(lambda r, w: handle_tcp(r, w, hub), host, port))
        log(f"raw TCP listening on {host}:{port} (no auth: bind it to a private interface)")
    if not servers:
        sys.exit("nothing to listen on: pass --ws and/or --tcp")
    if args.ws and not args.token:
        log("no --token: anyone who can reach this port can send audio and read entries. Fine on a private VPN/LAN.")
    log(f"saving to {os.path.abspath(args.out)}")
    await asyncio.gather(*(s.serve_forever() for s in servers))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ws", default="0.0.0.0:8740", help="host:port for the WebSocket + HTTP listener ('' disables it)")
    ap.add_argument("--tcp", default="", help="host:port for a raw-TCP listener (off by default)")
    ap.add_argument("--out", default="recordings", help="directory for recordings (default ./recordings)")
    ap.add_argument("--token", default=os.environ.get("RTB_TOKEN"),
                    help="require 'Authorization: Bearer TOKEN' (or set RTB_TOKEN)")
    ap.add_argument("--on-session-end", metavar="CMD",
                    help="shell command to run per finished recording; {path} is replaced with its path")
    ap.add_argument("--finish-after", type=int, default=600, metavar="SECS",
                    help="how long a dropped session waits for the phone to resume it (default 600)")
    ap.add_argument("--no-process", action="store_true", help="only save audio; leave entries queued")
    rtb_process.add_arguments(ap)
    args = ap.parse_args()
    try:
        asyncio.run(serve(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
