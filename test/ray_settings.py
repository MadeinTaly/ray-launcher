#!/usr/bin/env python3
"""TV settings and pairing popup on a dev build, checked through the debug channel.

    adb -s emulator-5556 forward tcp:18766 tcp:8766
    python test/ray_settings.py emulator-5556 18766
"""
import json, subprocess, sys, time, urllib.error, urllib.request

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"
PORT = sys.argv[2] if len(sys.argv) > 2 else "18766"
PKG = "dev.raylauncher"


def adb(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def state():
    for _ in range(20):
        out = adb("shell", "dumpsys", "activity", "service", PKG + "/.DimService", "debug")
        if "{" in out:
            return json.loads(out[out.index("{"):out.rindex("}") + 1])
        time.sleep(.5)
    sys.exit("no answer from the debug channel")


def key(name, wait=.5):
    adb("shell", "input", "keyevent", "KEYCODE_" + name)
    time.sleep(wait)


def post(path, body=None):
    req = urllib.request.Request("http://127.0.0.1:" + PORT + path, data=json.dumps(body or {}).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def check(ok, what, detail=None):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        print(json.dumps(detail, indent=1) if detail is not None else "")
        sys.exit(1)


adb("shell", "am", "force-stop", PKG)
adb("shell", "am", "start", "-n", PKG + "/.RayLauncherActivity")
time.sleep(5)
key("DPAD_DOWN")

# settings: the last disc, reached from the rows
for _ in range(3):
    key("DPAD_UP", .3)
for _ in range(5):
    key("DPAD_RIGHT", .25)
check(state()["home"]["focus"] == "settings", "the discs end on settings", state()["home"])
key("DPAD_CENTER", 1.5)
s = state()["settings"]
check(s["active"], "settings opens as a screen", s)
check(state()["home"]["active"] is False, "the home is behind it", state()["home"])
sections = []
first = s["section"]
for _ in range(10):
    sections.append(state()["settings"]["section"])
    key("DPAD_DOWN", .25)
    if state()["settings"]["section"] == sections[-1]:
        break
check(len(set(sections)) >= 5, "several sections: " + str(sections), sections)
text = json.dumps(state()["settings"])
check(not any(w in text.lower() for w in ["x-key:", "pairing_key"]), "no key on the screen", text)
for _ in sections:
    key("DPAD_UP", .2)
check(state()["settings"]["section"] == first, "back to the first section", state()["settings"])
key("DPAD_RIGHT")
check(state()["settings"]["focusedRow"] >= 0, "right enters the rows", state()["settings"])
key("BACK")
key("BACK", 1)
check(state()["settings"]["active"] is False and state()["home"]["active"], "back closes it", state())

# pairing from the phone: the popup follows the window, the code stops with it
status, reply = post("/api/pair/start")
check(status == 200, "pair start", reply)
time.sleep(1)
check(state()["home"]["pairOpen"], "the TV opens the pairing popup by itself", state()["home"])
status, reply = post("/api/pair/finish", {"pin": "abcd"})
check(status == 403, "a wrong code is refused", reply)
key("BACK", 1)
check(not state()["home"]["pairOpen"], "back closes the popup", state()["home"])
status, reply = post("/api/pair/finish", {"pin": "0000"})
check(status == 403 and reply.get("error") == "closed", "with the popup closed no code works", reply)
dump = json.dumps(state())
check("\"k\"" not in dump, "no key in the dump")
print("ray_settings OK")
