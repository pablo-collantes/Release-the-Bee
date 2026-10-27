#!/usr/bin/env python3
"""
rtb_receiver.py: receive RTB audio from the Release the Bee app and save it.

Accepts the app's WebSocket stream (rtb-stream/1, docs/STREAM_PROTOCOL.md) and,
optionally, the raw-TCP stream. Each session is written to

    <out>/<device>/<YYYY-MM-DD>/<HHMMSS>_<session>.aac

Concatenated ADTS frames are a valid .aac file: play it, or hand it to your
transcriber with --on-session-end. Standard library only, Python 3.9+.

  python3 receiver/rtb_receiver.py                          # WebSocket on 0.0.0.0:8765
  python3 receiver/rtb_receiver.py --ws 100.101.102.103:8765 # bind to one interface (e.g. your VPN IP)
  python3 receiver/rtb_receiver.py --token SECRET           # require "Authorization: Bearer SECRET"
  python3 receiver/rtb_receiver.py --tcp 0.0.0.0:8766       # also accept the raw-TCP sink
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
import shlex
import socket
import struct
import sys
import uuid

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_MESSAGE = 1 << 20
AUDIO_HEADER = struct.Struct(">IQ")          # seq u32, captured_at_ms u64


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


async def ws_handshake(reader, writer, token):
    """Answer the HTTP upgrade. Returns True if the connection is now a WebSocket."""
    head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
    lines = head.decode("latin-1").split("\r\n")
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()

    def refuse(status):
        writer.write(f"HTTP/1.1 {status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encode())
        return False

    if headers.get("upgrade", "").lower() != "websocket" or "sec-websocket-key" not in headers:
        return refuse("426 Upgrade Required")
    if token:
        given = headers.get("authorization", "").encode()
        if not hmac.compare_digest(given, f"Bearer {token}".encode()):
            return refuse("401 Unauthorized")
    accept = base64.b64encode(hashlib.sha1((headers["sec-websocket-key"] + WS_GUID).encode()).digest()).decode()
    writer.write(
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept}\r\n\r\n".encode()
    )
    await writer.drain()
    return True


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
    """Maps sessions to files. A session the phone drops stays open for --finish-after
    seconds, so a reconnect (resumed=true) keeps appending to the same file."""

    def __init__(self, out_dir, hook, finish_after):
        self.out = out_dir
        self.hook = hook
        self.finish_after = finish_after
        self.paths = {}          # session id -> path
        self.pending = {}        # session id -> TimerHandle that finishes it

    def open(self, device, session, started_at_ms):
        timer = self.pending.pop(session, None)
        if timer:
            timer.cancel()
        path = self.paths.get(session)
        if path is None:
            t = datetime.datetime.fromtimestamp(started_at_ms / 1000)
            safe_dev = "".join(c for c in (device or "unknown") if c.isalnum())[:32] or "unknown"
            path = os.path.join(self.out, safe_dev, f"{t:%Y-%m-%d}", f"{t:%H%M%S}_{session[:8]}.aac")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            self.paths[session] = path
        return path

    def dropped(self, session):
        """Connection lost mid-session: finish it later unless the phone resumes it."""
        if session in self.paths and session not in self.pending:
            loop = asyncio.get_running_loop()
            self.pending[session] = loop.call_later(self.finish_after, self.finish, session, "not resumed")

    def finish(self, session, reason):
        self.pending.pop(session, None)
        path = self.paths.pop(session, None)
        if path is None:
            return
        size = os.path.getsize(path) if os.path.exists(path) else 0
        log(f"session finished ({reason}): {path} ({size / 1024:.0f} KiB)")
        if self.hook and size:
            asyncio.get_running_loop().create_task(self.run_hook(path))

    async def run_hook(self, path):
        cmd = self.hook.replace("{path}", shlex.quote(path))
        proc = await asyncio.create_subprocess_shell(cmd)
        rc = await proc.wait()
        log(f"hook exited {rc}: {cmd}")


# --------------------------------------------------------------------------- handlers

async def handle_ws(reader, writer, args, rec):
    peer = writer.get_extra_info("peername")[0]
    try:
        if not await ws_handshake(reader, writer, args.token):
            log(f"{peer}: refused (not a WebSocket upgrade, or wrong token)")
            await writer.drain()
            writer.close()
            return
    except (asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError, ConnectionError):
        writer.close()
        return

    log(f"{peer}: connected")
    device, session, f, frames, gaps, last_seq = None, None, None, 0, 0, None
    try:
        while True:
            op, data = await ws_message(reader, writer)
            if op == 0x1:
                msg = json.loads(data)
                kind = msg.get("type")
                if kind == "hello":
                    device = msg.get("device")
                    log(f"{peer}: {msg.get('app', '?')} for RTB {device} ({msg.get('codec')}, {msg.get('protocol')})")
                elif kind == "session_start":
                    if f:
                        f.close()
                    session = msg.get("session") or str(uuid.uuid4())
                    path = rec.open(device, session, msg.get("started_at") or _now_ms())
                    f = open(path, "ab")
                    frames, gaps, last_seq = 0, 0, None
                    log(f"{peer}: {'resumed' if msg.get('resumed') else 'new'} session -> {path}")
                elif kind == "session_end":
                    if f:
                        f.close()
                        f = None
                    if session:
                        log(f"{peer}: session ended ({msg.get('reason')}); {frames} frames, {gaps} gaps")
                        rec.finish(session, msg.get("reason", "ended"))
                    session = None
                else:
                    log(f"{peer}: ignoring message type {kind!r}")
            else:
                if len(data) <= AUDIO_HEADER.size:
                    continue
                seq, _captured_at = AUDIO_HEADER.unpack_from(data)
                if f is None:                       # audio with no session_start: make one
                    session = str(uuid.uuid4())
                    path = rec.open(device, session, _now_ms())
                    f = open(path, "ab")
                    log(f"{peer}: audio without a session; writing to {path}")
                if last_seq is not None and seq != (last_seq + 1) & 0xFFFFFFFF:
                    gaps += 1
                    if gaps <= 3:
                        log(f"{peer}: seq jumped {last_seq} -> {seq}")
                last_seq = seq
                f.write(data[AUDIO_HEADER.size:])
                frames += 1
                if frames % 8 == 0:
                    f.flush()
    except (asyncio.IncompleteReadError, ConnectionError, WsClosed, json.JSONDecodeError) as e:
        log(f"{peer}: disconnected ({e.__class__.__name__}{': ' + str(e) if str(e) else ''})")
    finally:
        if f:
            f.close()
        if session:
            rec.dropped(session)
        writer.close()


async def handle_tcp(reader, writer, args, rec):
    peer = writer.get_extra_info("peername")[0]
    session = str(uuid.uuid4())
    path = rec.open(f"tcp{peer.replace('.', '').replace(':', '')}", session, _now_ms())
    log(f"{peer}: raw TCP stream -> {path}")
    with open(path, "ab") as f:
        while True:
            chunk = await reader.read(65536)
            if not chunk:
                break
            f.write(chunk)
    writer.close()
    rec.finish(session, "tcp closed")


def _now_ms():
    return int(datetime.datetime.now().timestamp() * 1000)


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
    rec = Recorder(args.out, args.on_session_end, args.finish_after)
    servers = []
    if args.ws:
        host, port = _split(args.ws)
        servers.append(await asyncio.start_server(lambda r, w: handle_ws(r, w, args, rec), host, port))
        log(f"WebSocket listening on {host}:{port}{' (token required)' if args.token else ''}")
        if host in ("0.0.0.0", "::"):
            for ip in _local_ips():
                log(f"  app URL could be ws://{ip}:{port}/")
    if args.tcp:
        host, port = _split(args.tcp)
        servers.append(await asyncio.start_server(lambda r, w: handle_tcp(r, w, args, rec), host, port))
        log(f"raw TCP listening on {host}:{port} (no auth: bind it to a private interface)")
    if not servers:
        sys.exit("nothing to listen on: pass --ws and/or --tcp")
    if args.ws and not args.token:
        log("no --token: anyone who can reach this port can send audio. Fine on a private VPN/LAN.")
    log(f"saving to {os.path.abspath(args.out)}")
    await asyncio.gather(*(s.serve_forever() for s in servers))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ws", default="0.0.0.0:8765", help="host:port for the WebSocket listener ('' disables it)")
    ap.add_argument("--tcp", default="", help="host:port for a raw-TCP listener (off by default)")
    ap.add_argument("--out", default="recordings", help="directory for recordings (default ./recordings)")
    ap.add_argument("--token", default=os.environ.get("RTB_TOKEN"),
                    help="require 'Authorization: Bearer TOKEN' (or set RTB_TOKEN)")
    ap.add_argument("--on-session-end", metavar="CMD",
                    help="shell command to run per finished recording; {path} is replaced with its path")
    ap.add_argument("--finish-after", type=int, default=600, metavar="SECS",
                    help="how long a dropped session waits for the phone to resume it (default 600)")
    args = ap.parse_args()
    try:
        asyncio.run(serve(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
