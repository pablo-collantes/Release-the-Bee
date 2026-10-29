import asyncio
import base64
import json
import os
import struct
import tempfile
import time
import unittest

import helpers  # noqa: F401
import rtb_entries
import rtb_receiver
from helpers import Args


def body(r):
    return json.loads(r.body)


class RouteTest(unittest.TestCase):
    """route() is the whole HTTP API without sockets."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pushes = []
        self.store = rtb_entries.EntryStore(self.tmp.name, on_change=self.pushes.append)
        self.stem = os.path.join(self.tmp.name, "e1")
        self.store.create(rtb_entries.new_entry("e1", "dev", started_at=1000), self.stem)
        self.store.update("e1", status="done", transcript={"language": "en", "segments": []},
                          summary={"title": "T", "summary": "S", "action_items": [], "model": "m"})
        with open(self.stem + ".aac", "wb") as f:
            f.write(bytes(range(256)) * 4)                                # 1024 bytes

    def tearDown(self):
        self.tmp.cleanup()

    def get(self, target, token=None, method="GET", body=b"", **headers):
        return rtb_receiver.route(method, target, {k.lower(): v for k, v in headers.items()}, self.store, token,
                                  body)

    def patch(self, id, obj):
        return self.get(f"/api/entries/{id}", method="PATCH",
                        body=obj if isinstance(obj, bytes) else json.dumps(obj).encode())

    def add_command(self, id="c1"):
        self.store.create(rtb_entries.new_entry(id, "dev", kind="command", started_at=2000),
                          os.path.join(self.tmp.name, id))
        self.store.update(id, status="done", command={"action": "CREATE_REMINDER", "note": None, "text": "Call Ana",
                                                      "when": "2026-10-29T15:00:00-05:00", "model": "m"})

    def test_token(self):
        self.assertEqual(self.get("/api/entries", token="s3cret").status, 401)
        self.assertEqual(self.get("/api/entries", token="s3cret", Authorization="Bearer nope").status, 401)
        r = self.get("/api/entries", token="s3cret", Authorization="Bearer s3cret")
        self.assertEqual(r.status, 200)
        r = self.get("/api/entries/e1/audio", token="s3cret")
        self.assertEqual(r.status, 401)
        self.assertEqual(r.headers.get("WWW-Authenticate"), "Bearer")
        self.assertEqual(self.get("/api/entries").status, 200)                # no token configured

    def test_list(self):
        r = self.get("/api/entries")
        self.assertEqual(r.ctype, "application/json")
        data = body(r)
        self.assertIsInstance(data["now"], int)
        self.assertEqual([e["id"] for e in data["entries"]], ["e1"])
        e = data["entries"][0]
        self.assertIsNone(e["transcript"])
        self.assertIsNone(e["summary"])
        self.assertEqual(e["status"], "done")
        self.assertEqual(data["deleted"], [])
        later = body(self.get(f"/api/entries?since={data['now'] + 1000}"))
        self.assertEqual((later["entries"], later["deleted"]), ([], []))
        self.assertEqual(self.get("/api/entries?since=yesterday").status, 400)

    def test_detail(self):
        e = body(self.get("/api/entries/e1"))
        self.assertEqual(e["summary"]["title"], "T")
        self.assertEqual(e["transcript"]["language"], "en")
        self.assertEqual(self.get("/api/entries/nope").status, 404)
        self.assertEqual(self.get("/api/entries/..%2F..%2Fetc").status, 404)
        self.assertEqual(self.get("/api/other").status, 404)
        self.assertEqual(self.get("/api/entries/e1", method="PUT").status, 405)

    def test_list_carries_command_bodies(self):
        self.add_command()
        c, e = body(self.get("/api/entries"))["entries"]
        self.assertEqual((c["id"], c["command"]["text"], c["command"]["done"]), ("c1", "Call Ana", False))
        self.assertIsNone(c["transcript"])
        self.assertEqual((e["id"], e["command"], e["summary"]), ("e1", None, None))

    def test_patch_command(self):
        self.add_command()
        before = self.store.get("c1")["updated_at"]
        time.sleep(0.002)
        mark = rtb_entries.now_ms()
        self.pushes.clear()
        r = self.patch("c1", {"command": {"done": True, "when": "2026-10-30T09:00:00Z", "colour": "red"}})
        self.assertEqual(r.status, 200)
        e = body(r)
        self.assertEqual(e["command"], {"action": "CREATE_REMINDER", "note": None, "text": "Call Ana",
                                        "when": "2026-10-30T09:00:00Z", "model": "m", "done": True})
        self.assertGreater(e["updated_at"], before)
        self.assertEqual(self.store.get("c1"), e)
        self.assertEqual([(p["id"], p["status"]) for p in self.pushes], [("c1", "done")])   # same status, still pushed
        since = body(self.get(f"/api/entries?since={mark}"))
        self.assertEqual([x["id"] for x in since["entries"]], ["c1"])

        e = body(self.patch("c1", {"command": {"text": "Call Ana back", "when": None, "note": None}}))
        self.assertEqual((e["command"]["text"], e["command"]["when"], e["command"]["done"]), ("Call Ana back", None, True))
        with open(os.path.join(self.tmp.name, "c1.json")) as f:
            self.assertEqual(json.load(f)["command"]["text"], "Call Ana back")
        e = body(self.patch("c1", {"command": {"when": "2026-11-02T21:30:00.000-06:00", "done": False}}))
        self.assertEqual((e["command"]["when"], e["command"]["done"]), ("2026-11-02T21:30:00.000-06:00", False))

    def test_patch_errors(self):
        self.add_command()
        for bad in [b"", b"not json", b"[1]", b'{"done": true}', b'{"command": "done"}', {"command": None},
                    {"command": {"done": "yes"}}, {"command": {"done": 1}}, {"command": {"text": 5}},
                    {"command": {"text": None}}, {"command": {"note": 3}}, {"command": {"when": "tomorrow"}},
                    {"command": {"when": "2026-10-30T09:00:00"}}, {"command": {"when": 1761651534000}},
                    {"command": {"done": True, "when": "2026-10-30"}}]:
            r = self.patch("c1", bad)
            self.assertEqual(r.status, 400, bad)
            self.assertIn("error", body(r))
        self.assertIs(self.store.get("c1")["command"]["done"], False)          # nothing half-applied
        self.assertEqual(self.patch("e1", {"command": {"done": True}}).status, 409)      # a summary
        self.store.create(rtb_entries.new_entry("c2", "dev", kind="command", started_at=5),
                          os.path.join(self.tmp.name, "c2"))
        r = self.patch("c2", {"command": {"done": True}})                                 # not processed yet
        self.assertEqual((r.status, body(r)), (409, {"error": "not a processed command"}))
        self.assertEqual(self.patch("ghost", {"command": {"done": True}}).status, 404)
        self.assertEqual(self.patch("c1", {"command": {"text": "x" * 70000}}).status, 413)
        self.assertEqual(self.patch("c1", b"{" + b" " * 65534 + b"}").status, 400)       # 64 KiB exactly is fine

    def test_delete(self):
        with open(self.stem + ".m4a", "wb") as f:
            f.write(b"m4a!")
        mark = rtb_entries.now_ms()
        r = self.get("/api/entries/e1", method="DELETE")
        self.assertEqual((r.status, body(r)), (200, {"id": "e1", "deleted": True}))
        self.assertEqual(os.listdir(self.tmp.name), ["deleted.json"])
        self.assertEqual(self.get("/api/entries/e1").status, 404)
        self.assertEqual(self.get("/api/entries/e1/audio").status, 404)
        self.assertEqual(self.pushes[-1], {"id": "e1", "kind": "summary", "status": "deleted", "title": None})
        data = body(self.get(f"/api/entries?since={mark}"))
        self.assertEqual((data["entries"], data["deleted"]), ([], ["e1"]))
        self.assertEqual(body(self.get("/api/entries"))["deleted"], [])
        r = self.get("/api/entries/e1", method="DELETE")                     # again: still 200
        self.assertEqual((r.status, body(r)), (200, {"id": "e1", "deleted": True}))
        self.assertEqual(self.get("/api/entries/e1/reprocess", method="POST").status, 404)
        self.assertEqual(self.get("/api/entries/ghost", method="DELETE").status, 404)

    def test_audio_whole_and_ranges(self):
        r = self.get("/api/entries/e1/audio")
        self.assertEqual((r.status, r.ctype), (200, "audio/aac"))
        self.assertEqual(r.file[1:], (0, 1023))
        self.assertEqual(r.headers["Accept-Ranges"], "bytes")

        r = self.get("/api/entries/e1/audio", Range="bytes=100-199")
        self.assertEqual(r.status, 206)
        self.assertEqual(r.file[1:], (100, 199))
        self.assertEqual(r.headers["Content-Range"], "bytes 100-199/1024")

        r = self.get("/api/entries/e1/audio", Range="bytes=1000-")
        self.assertEqual((r.status, r.file[1:]), (206, (1000, 1023)))
        r = self.get("/api/entries/e1/audio", Range="bytes=-24")
        self.assertEqual((r.status, r.file[1:]), (206, (1000, 1023)))
        r = self.get("/api/entries/e1/audio", Range="bytes=0-99999")
        self.assertEqual(r.headers["Content-Range"], "bytes 0-1023/1024")
        r = self.get("/api/entries/e1/audio", Range="bytes=5000-")
        self.assertEqual(r.status, 416)
        self.assertEqual(r.headers["Content-Range"], "bytes */1024")
        r = self.get("/api/entries/e1/audio", Range="bytes=0-1,5-6")              # multi-range: send it all
        self.assertEqual(r.status, 200)

        with open(self.stem + ".m4a", "wb") as f:                                 # the remux wins
            f.write(b"m4a!")
        r = self.get("/api/entries/e1/audio")
        self.assertEqual((r.ctype, r.file[2]), ("audio/mp4", 3))

    def test_reprocess(self):
        self.assertEqual(self.get("/api/entries/e1/reprocess").status, 405)
        r = self.get("/api/entries/e1/reprocess", method="POST")
        self.assertEqual(r.status, 202)
        self.assertEqual(self.store.get("e1")["status"], "queued")
        self.store.create(rtb_entries.new_entry("live", "dev", started_at=5), os.path.join(self.tmp.name, "live"))
        self.assertEqual(self.get("/api/entries/live/reprocess", method="POST").status, 409)
        self.assertEqual(self.get("/api/entries/ghost/reprocess", method="POST").status, 404)


# ------------------------------------------------------------------ over a real socket

async def http(port, request):
    r, w = await asyncio.open_connection("127.0.0.1", port)
    w.write(request.encode())
    await w.drain()
    data = await r.read()
    w.close()
    head, _, payload = data.partition(b"\r\n\r\n")
    return head.decode(), payload


def client_frame(opcode, payload):
    """A masked client → server frame."""
    mask = b"\x01\x02\x03\x04"
    n = len(payload)
    head = struct.pack(">BB", 0x80 | opcode, 0x80 | n) if n < 126 else struct.pack(">BBH", 0x80 | opcode, 0xFE, n)
    return head + mask + rtb_receiver.unmask(payload, mask)


async def read_frame(r):
    b1, b2 = await r.readexactly(2)
    n = b2 & 0x7F
    if n == 126:
        n = struct.unpack(">H", await r.readexactly(2))[0]
    return b1 & 0x0F, await r.readexactly(n)


class SocketTest(unittest.TestCase):
    """HTTP and the WebSocket share one port; status changes are pushed to the phone."""

    def test_http_and_websocket_on_one_port(self):
        asyncio.run(self.scenario())

    async def scenario(self):
        tmp = tempfile.TemporaryDirectory()
        loop = asyncio.get_running_loop()
        args = Args(token="tok", out=tmp.name)
        hub = None
        store = rtb_entries.EntryStore(tmp.name, on_change=lambda e: loop.call_soon_threadsafe(hub.push, e))
        rec = rtb_receiver.Recorder(tmp.name, store)
        hub = rtb_receiver.Hub(args, rec, store)
        server = await asyncio.start_server(lambda r, w: rtb_receiver.handle_conn(r, w, hub), "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            head, _ = await http(port, "GET /api/entries HTTP/1.1\r\nHost: x\r\n\r\n")
            self.assertIn("401", head.split("\r\n")[0])
            head, payload = await http(port, "GET /api/entries HTTP/1.1\r\nAuthorization: Bearer tok\r\n\r\n")
            self.assertIn("200", head.split("\r\n")[0])
            self.assertEqual(json.loads(payload)["entries"], [])

            key = base64.b64encode(os.urandom(16)).decode()
            r, w = await asyncio.open_connection("127.0.0.1", port)
            w.write(f"GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    f"Sec-WebSocket-Key: {key}\r\nAuthorization: Bearer tok\r\n\r\n".encode())
            resp = await r.readuntil(b"\r\n\r\n")
            self.assertIn(b"101", resp.split(b"\r\n")[0])

            def send(obj):
                w.write(client_frame(0x1, json.dumps(obj).encode()))
            send({"type": "hello", "device": "dev1"})
            send({"type": "session_start", "session": "11111111-aaaa", "started_at": 1761651534000,
                  "kind": "summary", "trigger": "button", "parent": None})
            w.write(client_frame(0x2, struct.pack(">IQ", 1, 1761651534100) + helpers.adts(3)))
            send({"type": "session_end", "session": "11111111-aaaa", "reason": "paused"})
            await w.drain()

            pushes = []
            while len(pushes) < 2:
                op, data = await asyncio.wait_for(read_frame(r), 5)
                self.assertEqual(op, 0x1)
                pushes.append(json.loads(data))
            self.assertEqual([(p["type"], p["id"], p["status"]) for p in pushes],
                             [("entry", "11111111-aaaa", "recording"), ("entry", "11111111-aaaa", "queued")])
            self.assertEqual(pushes[0]["kind"], "summary")

            # The entry is now served over HTTP, audio included (with a Range request).
            head, payload = await http(port, "GET /api/entries/11111111-aaaa HTTP/1.1\r\nAuthorization: Bearer tok\r\n\r\n")
            entry = json.loads(payload)
            self.assertEqual((entry["status"], entry["trigger"], entry["ended_at"]), ("queued", "button", 1761651534100))
            head, payload = await http(port, "GET /api/entries/11111111-aaaa/audio HTTP/1.1\r\n"
                                             "Authorization: Bearer tok\r\nRange: bytes=0-6\r\n\r\n")
            self.assertIn("206", head.split("\r\n")[0])
            self.assertEqual(payload, helpers.adts_frame()[:7])

            # A command the phone marks done, then deletes: the body gets through, and both are pushed.
            store.create(rtb_entries.new_entry("c1", "dev1", kind="command", started_at=5), os.path.join(tmp.name, "c1"))
            store.update("c1", status="done", command={"action": "UNKNOWN", "note": None, "text": "hm",
                                                       "when": None, "model": "m"})
            patch = json.dumps({"command": {"done": True}})
            head, payload = await http(port, "PATCH /api/entries/c1 HTTP/1.1\r\nAuthorization: Bearer tok\r\n"
                                             f"Content-Type: application/json\r\nContent-Length: {len(patch)}\r\n\r\n{patch}")
            self.assertIn("200", head.split("\r\n")[0])
            self.assertIs(json.loads(payload)["command"]["done"], True)
            head, _ = await http(port, "PATCH /api/entries/c1 HTTP/1.1\r\nAuthorization: Bearer tok\r\n"
                                       "Content-Length: 65537\r\n\r\n" + "x" * 65537)
            self.assertIn("413", head.split("\r\n")[0])
            head, payload = await http(port, "DELETE /api/entries/c1 HTTP/1.1\r\nAuthorization: Bearer tok\r\n\r\n")
            self.assertEqual(json.loads(payload), {"id": "c1", "deleted": True})
            pushes = [json.loads((await asyncio.wait_for(read_frame(r), 5))[1]) for _ in range(4)]
            self.assertEqual([(p["id"], p["status"]) for p in pushes],
                             [("c1", "recording"), ("c1", "done"), ("c1", "done"), ("c1", "deleted")])
            self.assertEqual(pushes[-1], {"type": "entry", "id": "c1", "kind": "command", "status": "deleted",
                                          "title": None})
            w.close()
        finally:
            server.close()
            await server.wait_closed()
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
