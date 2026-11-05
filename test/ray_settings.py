#!/usr/bin/env python3
"""TV settings and pairing popup on a dev build, checked through the debug channel.

    adb -s emulator-5556 forward tcp:18766 tcp:8766
    python test/ray_settings.py emulator-5556 18766

English UI on the emulator (the rows are found by their English labels).
"""
import html, json, re, subprocess, sys, time, urllib.error, urllib.request

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


def post(path, body=None, key=None):
    r = send(path, body, key)
    if body is not None and key:
        time.sleep(1.5)  # the schedule is applied on the TV's main thread
    return r


def send(path, body, key):
    get = body is None and key
    req = urllib.request.Request("http://127.0.0.1:" + PORT + path, data=None if get else json.dumps(body or {}).encode(), method="GET" if get else "POST")
    req.add_header("Content-Type", "application/json")
    if key:
        req.add_header("X-Key", key)
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


def prefs():
    return html.unescape(adb("shell", "run-as", PKG, "cat", "shared_prefs/dim.xml"))


def open_settings():
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", PKG + "/.RayLauncherActivity")
    time.sleep(5)
    key("DPAD_DOWN")
    for _ in range(3):
        key("DPAD_UP", .3)
    for _ in range(5):
        key("DPAD_RIGHT", .25)
    check(state()["home"]["focus"] == "settings", "the discs end on settings", state()["home"])
    key("DPAD_CENTER", 1.5)


def to_section(section):
    if state()["settings"]["focusedRow"] >= 0:
        key("BACK", .3)
    for _ in range(8):
        key("DPAD_UP", .15)
    for _ in range(8):
        if state()["settings"]["section"] == section:
            break
        key("DPAD_DOWN", .25)
    check(state()["settings"]["section"] == section, "section " + section, state()["settings"])


def to_row(section, label):
    to_section(section)
    key("DPAD_RIGHT", .25)
    rows = state()["settings"]["rows"]
    i = next((i for i, r in enumerate(rows) if r.startswith(label + "=")), -1)
    check(i >= 0, "row " + label + " in " + section, rows)
    for _ in range(i):
        key("DPAD_DOWN", .2)
    return i


def labels(section):
    to_section(section)
    return [r.split("=")[0] for r in state()["settings"]["rows"]]


def screen_text():
    for _ in range(4):
        adb("shell", "rm", "-f", "/sdcard/ray_ui.xml")
        adb("shell", "uiautomator", "dump", "/sdcard/ray_ui.xml")
        text = adb("shell", "cat", "/sdcard/ray_ui.xml")
        if "<hierarchy" in text:
            return text
        time.sleep(1)
    return ""


secret = re.search(r'name="key">([^<]+)<', prefs()).group(1)
post("/api/schedule", {"enabled": False, "value": [], "resolved": []}, secret)  # no schedule to start with

# settings: the last disc, reached from the rows
open_settings()
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

# what each section holds
check(labels("screen") == ["Screen filter", "Warm filter", "Brightness", "Colour", "Night filter", "From", "To", "Cover the whole screen"], "Screen section", labels("screen"))
phone_rows = labels("phone")
check(phone_rows == ["Phone remote", "Pair", "Connected phones", "Forget"], "Phone section: no Optional row, no pointer", phone_rows)
home_rows = labels("home")
check(home_rows[0] == "Full access" and home_rows[-2:] == ["Manage apps", "TV inputs"], "Home section", home_rows)
adv = labels("about")
check(adv[0] == "Pointer over apps" and "Version" in adv and adv[-1] == "Support the project", "Advanced section", adv)

# C4: the filter at night with the remote alone
night_row = to_row("screen", "Night filter")
check(state()["settings"]["rows"][night_row] == "Night filter=off", "night filter starts off", state()["settings"]["rows"])
key("DPAD_CENTER", 2)
p = prefs()
check(state()["settings"]["rows"][night_row] == "Night filter=on", "OK turns it on", state()["settings"]["rows"])
check('name="schedule_on" value="true"' in p and p.count('"model":"tv"') >= 2 and '"time":"22:00"' in p and '"time":"07:00"' in p, "two tv slots, 22:00 and 07:00", p)
hhmm = int(adb("shell", "date", "+%H%M").strip())
night_now = hhmm >= 2200 or hhmm < 700
check(('name="off" value="false"' in p) == night_now, "the slot of this hour is applied (night: " + str(night_now) + ")", p)
key("DPAD_DOWN", .3)
key("DPAD_RIGHT", .8)
check("From=22:30" in state()["settings"]["rows"][night_row + 1], "right moves From by 30 minutes", state()["settings"]["rows"])
check('"time":"22:30"' in prefs(), "the slot follows", prefs())
key("DPAD_LEFT", .8)
check("From=22:00" in state()["settings"]["rows"][night_row + 1], "left moves it back", state()["settings"]["rows"])
key("DPAD_UP", .3)
key("DPAD_CENTER", 2)
check('name="schedule_on" value="false"' in prefs(), "OK again turns it off", prefs())

# U7: a schedule made on the phone is kept until OK is pressed twice; the phone editor reads what the TV wrote
phone_slot = [{"time": "20:00", "model": "night", "label": "x", "values": {"on": True, "red": 30, "bright": 60, "temp": 2000}}]
status, reply = post("/api/schedule", {"enabled": True, "value": phone_slot, "resolved": phone_slot}, secret)
check(status == 200, "the phone saves a schedule", reply)
key("BACK", .5)
key("BACK", 1)
open_settings()
night_row = to_row("screen", "Night filter")
key("DPAD_CENTER", 1)
check("again" in state()["settings"]["rows"][night_row] and '"model":"tv"' not in prefs(), "first OK only asks", state()["settings"]["rows"])
key("DPAD_CENTER", 2)
check('"model":"tv"' in prefs(), "second OK replaces it", prefs())
status, reply = post("/api/schedule", None, secret)
slots = reply.get("value")
check(status == 200 and isinstance(slots, list) and len(slots) == 2 and all(x.get("model") == "tv" and "red" in x for x in slots), "the phone gets two flat slots it can load", reply)
post("/api/schedule", {"enabled": False, "value": [], "resolved": []}, secret)

# C5: Home > Manage apps with the D-pad and OK alone
key("BACK", .5)
key("BACK", 1)
open_settings()
to_row("home", "Manage apps")
key("DPAD_CENTER", 2)
check(state()["home"]["dialog"] and "Manage apps" in screen_text(), "Manage apps lists the apps", state()["home"])
key("DPAD_CENTER", 1.5)
check("App info" in screen_text(), "OK on an app opens its menu", state()["home"])
key("BACK", 1)
key("BACK", 1)

# Menu key: on an app tile its menu, elsewhere the settings
key("DPAD_DOWN")
for _ in range(2):
    key("DPAD_RIGHT", .2)
key("MENU", 1.5)
check(state()["home"]["dialog"] and "App info" in screen_text(), "Menu on an app opens its menu", state()["home"])
key("BACK", 1)
for _ in range(3):
    key("DPAD_UP", .3)
key("MENU", 1.5)
check(state()["settings"]["active"], "Menu elsewhere opens the settings", state())
key("BACK", .5)
key("BACK", 1)
check(state()["settings"]["active"] is False and state()["home"]["active"], "back closes it", state())

# C9: Full access is on but the ADB of this device is closed
def write_prefs(text):
    subprocess.run(["adb", "-s", SERIAL, "shell", "run-as " + PKG + " sh -c 'cat > shared_prefs/dim.xml'"], input=text, text=True, encoding="utf-8", capture_output=True)


adb("shell", "am", "force-stop", PKG)
original = adb("shell", "run-as", PKG, "cat", "shared_prefs/dim.xml").replace("\r\n", "\n")
write_prefs(original.replace("<map>", '<map>\n    <boolean name="ray_adb" value="true" />', 1))
open_settings()
to_row("home", "Full access")
time.sleep(4)
rows = state()["settings"]["rows"]
check(any(r == "Full access=Unavailable, try again" for r in rows), "Full access on but ADB closed: Unavailable, try again", rows)
adb("shell", "am", "force-stop", PKG)
write_prefs(original)
adb("shell", "am", "start", "-n", PKG + "/.RayLauncherActivity")
time.sleep(5)
check("ray_adb" not in prefs(), "the pref is back as it was")

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
