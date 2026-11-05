#!/usr/bin/env python3
"""A real TV or projector with real apps, dev build, Full access on: what the phone gets, over its real socket.
Touches only RAY; opens the listed apps and brings RAY back.

    adb -s HOST:5555 forward tcp:18767 tcp:8766
    python test/ray_real.py HOST:5555 18767
"""
import base64, json, os, re, socket, struct, subprocess, sys, time

D, PORT = sys.argv[1], int(sys.argv[2])
PKG = "dev.raylauncher"
APPS = sys.argv[3:] or ["com.lagradost.cloudstream3", "org.xbmc.kodi", "com.netflix.mediaclient", "org.smarttube.stable"]


def adb(*a):
    return subprocess.run(["adb", "-s", D, *a], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def debug():
    for _ in range(20):
        o = adb("shell", "dumpsys", "activity", "service", PKG + "/.DimService", "debug")
        if "{" in o:
            return json.loads(o[o.index("{"):o.rindex("}") + 1])
        time.sleep(.5)
    sys.exit("no debug channel")


def front():
    m = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([\w.]+)", adb("shell", "dumpsys", "window"))
    return m.group(1) if m else "?"


def check(ok, what, detail=""):
    print(("ok   " if ok else "FAIL ") + what + (("  " + str(detail)[:150]) if detail != "" else ""))
    return ok


class Ws:
    def __init__(self, key):
        self.s = socket.create_connection(("127.0.0.1", PORT), 8)
        self.s.settimeout(12)
        self.s.sendall(("GET /ws HTTP/1.1\r\nHost: tv\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "
                        + base64.b64encode(os.urandom(16)).decode() + "\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.s.recv(1024)
        self.seq = 0
        self.send({"type": "auth", "key": key})
        first = self.recv()
        if first.get("type") != "auth.ok":
            sys.exit("auth failed: " + str(first))

    def send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        head = bytes([0x81]) + (bytes([0x80 | len(data)]) if len(data) < 126 else bytes([0x80 | 126]) + struct.pack(">H", len(data)))
        self.s.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _read(self, n):
        out = b""
        while len(out) < n:
            part = self.s.recv(n - len(out))
            if not part:
                raise EOFError
            out += part
        return out

    def recv(self):
        while True:
            a, b = self._read(2)
            n = b & 127
            if n == 126:
                n = struct.unpack(">H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read(8))[0]
            data = self._read(n)
            if a & 15 == 1:
                return json.loads(data)

    def cmd(self, kind, **payload):
        self.seq += 1
        rid = str(self.seq)
        self.send({"type": kind, "id": rid, "payload": payload, **payload})
        end = time.time() + 10
        while time.time() < end:
            m = self.recv()
            if m.get("id") == rid or m.get("type") in ("result", "ack") and str(m.get("id", rid)) == rid:
                return m
        return {}

    def snapshot(self):
        # frames queued while the test slept are old: keep reading until the socket is quiet, take the last
        self.send({"type": "ping"})
        last = {}
        self.s.settimeout(2.5)
        try:
            while True:
                m = self.recv()
                if "apps" in m or "apps" in m.get("data", {}) or "apps" in m.get("payload", {}):
                    last = m.get("data") or m.get("payload") or m
        except (socket.timeout, TimeoutError):
            pass
        self.s.settimeout(12)
        return last


prefs = adb("shell", "run-as", PKG, "cat", "shared_prefs/dim.xml")
key = re.search(r'name="[^"]*key[^"]*">([0-9a-f]{8,})<', prefs).group(1)
adb("shell", "am", "start", "-n", PKG + "/.RayLauncherActivity")
time.sleep(4)
ws = Ws(key)
h = debug()["home"]
print("home: watch", h["watch"], "row", [r[:32] for r in h["row"][:4]])
ok = check(h["watch"] > 0, "continue watching from real apps")
titles = len(set(r for r in h["row"] if r.startswith("watch:")))
ok &= check(titles == sum(1 for r in h["row"] if r.startswith("watch:")), "no title twice")

r = ws.cmd("media.search", query="boys")
time.sleep(1.5)
h = debug()["home"]
ok &= check(h["category"] == "search" and any("Boys" in x for x in h["row"]), "phone search with the home in front", [x[:30] for x in h["row"][:3]])
adb("shell", "input", "keyevent", "KEYCODE_BACK")
time.sleep(1)

for app in APPS:
    if app not in adb("shell", "pm", "list", "packages", app):
        continue
    ws.cmd("app.launch", package=app)
    for _ in range(12):  # a heavy app on a small box takes its time
        time.sleep(2)
        if front() == app:
            break
    time.sleep(4)
    s = ws.snapshot()
    media = s.get("media") or s.get("foreground") or {}
    print(app, "front", front(), "keys", sorted(media)[:12] if isinstance(media, dict) else type(media))
    if isinstance(media, dict):
        print("   app", media.get("app"), "continue", len(media.get("continue", [])), [i.get("title", "")[:22] for i in media.get("continue", [])[:3]],
              "suggested", len(media.get("suggestions", [])), "searchable", media.get("searchable"), "session", media.get("session"))
    ok &= check(front() == app, "opens from the phone: " + app)
    ok &= check(isinstance(media, dict) and media.get("package", app) == app and bool(media.get("app")), "the phone knows what is open", media.get("app") if isinstance(media, dict) else "")
    k = ws.cmd("remote.key", key="DOWN")
    ok &= check(k.get("ok") is True or k.get("payload", {}).get("ok") is True, "phone arrows work over the app", json.dumps(k)[:80])
    adb("shell", "am", "start", "-n", PKG + "/.RayLauncherActivity")
    time.sleep(3)

first = debug()["home"]["row"][0]
if first.startswith("watch:"):
    title = first[6:first.rindex(" (")]
    owner = first[first.rindex("(") + 1:-1]
    r = ws.cmd("watch.open", title=title)
    time.sleep(7)
    ok &= check(front() == owner, "the chosen title opens its app: " + title, front())
    adb("shell", "am", "start", "-n", PKG + "/.RayLauncherActivity")
    time.sleep(2)
print("ray_real OK" if ok else "ray_real FAILED")
