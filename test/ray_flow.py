#!/usr/bin/env python3
"""Home flow on a TV device running a dev build (DEV=1), checked through the debug channel, no screenshots.

    python test/ray_flow.py emulator-5556
"""
import json, re, subprocess, sys, time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"
PKG = "dev.raylauncher"


def adb(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def state():
    for _ in range(20):  # the service starts a moment after the activity
        out = adb("shell", "dumpsys", "activity", "service", PKG + "/.DimService", "debug")
        if "debug off" in out:
            sys.exit("release build: the debug channel is closed, install a DEV=1 build")
        if "{" in out:
            return json.loads(out[out.index("{"):out.rindex("}") + 1])
        time.sleep(.5)
    sys.exit("no answer from the debug channel")


def key(name, wait=.5):
    adb("shell", "input", "keyevent", "KEYCODE_" + name)
    time.sleep(wait)


def check(ok, what, s):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        print(json.dumps(s["home"], indent=1))
        sys.exit(1)


adb("shell", "am", "force-stop", PKG)
adb("shell", "am", "start", "-n", PKG + "/.RayLauncherActivity")
time.sleep(5)
s = state()
h = s["home"]
check(h["active"] and h["category"] == "home", "home in front", s)
check(not h["dialog"], "no question at first start", s)
check(h["labels"] and all(96 <= b[0] and 54 <= b[1] and b[2] <= 1824 and b[3] <= 1026 for b in h["labels"]), "every label box is inside x 96-1824, y 54-1026", s)
words = [b for b in h["labels"] if b[2] - b[0] == 146]
check(len(words) == 4 and max(b[2] for b in words) == 1823 and all(b[1] == 151 for b in words), "the four button labels: 146 px boxes at y 151, right edge 1823", s)
if s["remote"]["sockets"] == 0:
    check(not h["connectVisible"], "no phone: the home does not ask for one", s)
row = h["row"]
check(row[-1] == "ph:apps", "All apps is the last card", s)
check(len(row) > 1, "the row has content", s)
if h["focusIndex"] == -1:  # a touch on the emulator window left it in touch mode: the first key must land on card 1
    check(h["stage"] != "none", "touch mode: the stage is not empty", s)
    key("DPAD_DOWN")
    s = state(); h = s["home"]
check(h["focusIndex"] == 0, "focus starts on the first card", s)
watch = [i for i, r in enumerate(row) if r.startswith("watch:")]
if s["home"]["watch"]:
    check(watch == list(range(len(watch))), "continue watching on top, apps below", s)
    check(h["focus"].startswith("watch:"), "focus starts on continue watching", s)

first_stage = h["stage"]
key("DPAD_RIGHT")
s = state(); h = s["home"]
check(h["focusIndex"] == 1, "right moves along the row", s)
for _ in range(12):
    key("DPAD_RIGHT", .2)
s = state()
check(s["home"]["focusIndex"] == len(watch) - 1 or not watch, "right stops at the end of the top row", s)
if watch:
    key("DPAD_DOWN")
    s = state()
    check(not s["home"]["focus"].startswith("watch:"), "down goes to the apps", s)
    key("DPAD_UP")
    s = state()
    check(s["home"]["focus"].startswith("watch:"), "up returns to continue watching", s)
    key("DPAD_DOWN")
check(h["stage"] != first_stage, "the stage follows the focus", s)
for _ in row:
    key("DPAD_RIGHT", .2)
s = state(); h = s["home"]
check(h["focus"] == "ph:apps", "right ends on All apps", s)
key("DPAD_RIGHT")
check(state()["home"]["focus"] == "ph:apps", "no wrap past the end", state())
key("DPAD_DOWN")
check(state()["home"]["focus"] == "ph:apps", "down stays in the row", state())

key("DPAD_CENTER", 1)
s = state(); h = s["home"]
check(h["category"] == "apps" and not h["dialog"], "All apps opens the grid, not a dialog", s)
check(h["focusIndex"] == 0 and len(h["row"]) == h["apps"], "the grid has every app", s)
per = 6
if h["apps"] > per:
    key("DPAD_DOWN")
    check(state()["home"]["focusIndex"] == per, "down moves a grid row", state())
key("BACK", 1)
s = state(); h = s["home"]
check(h["category"] == "home" and h["focus"] == "ph:apps", "back returns to All apps in the row", s)

for _ in range(3):
    key("DPAD_UP")
s = state()
check(s["home"]["focusIndex"] == -1, "up reaches the discs", s)
key("DPAD_DOWN")
check(state()["home"]["focusIndex"] >= 0, "down returns to the row", state())
print("ray_flow OK")
