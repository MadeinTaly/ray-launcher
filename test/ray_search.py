#!/usr/bin/env python3
"""The search of the home on a dev build in demo mode, checked through the debug channel.

    python test/ray_search.py emulator-5556
"""
import json, subprocess, sys, time

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5556"
PKG = "dev.raylauncher"


def adb(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def home():
    for _ in range(20):
        out = adb("shell", "dumpsys", "activity", "service", PKG + "/.DimService", "debug")
        if "{" in out:
            return json.loads(out[out.index("{"):out.rindex("}") + 1])["home"]
        time.sleep(.5)
    sys.exit("no debug channel")


def key(name, wait=.5):
    adb("shell", "input", "keyevent", "KEYCODE_" + name)
    time.sleep(wait)


def check(ok, what, detail=None):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        print(json.dumps(detail, indent=1) if detail is not None else "")
        sys.exit(1)


def search(word):
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", PKG + "/.RayLauncherActivity")
    time.sleep(5)
    key("DPAD_DOWN")
    for _ in range(3):
        key("DPAD_UP", .3)
    for _ in range(4):
        key("DPAD_LEFT", .2)
    check(home()["focus"] == "search", "the search button is the first", home())
    key("DPAD_CENTER", 1)
    check(home()["dialog"], "the search box opens", home())
    adb("shell", "input", "text", word)
    time.sleep(.5)
    key("ENTER", 1.5)
    return home()


h = search("ocean")
check(h["category"] == "search" and not h["dialog"], "the results replace the home", h)
check(h["row"][0].startswith("watch:Ocean"), "the film comes first", h["row"])
check(any(r.startswith("ph:find:") for r in h["row"]), "search inside each app is offered", h["row"])
check(h["focusIndex"] == 0, "focus on the first result", h)
key("BACK", 1)
check(home()["category"] == "home", "back returns home", home())

h = search("OCEAN")
check(any(r.startswith("watch:Ocean") for r in h["row"]), "case does not matter", h["row"])
key("BACK", 1)

h = search("zzzqqq")
check(not any(r.startswith("watch:") for r in h["row"]), "no false film", h["row"])
check(len(h["row"]) >= 1, "the D-pad still has somewhere to go", h)
key("BACK", 1)
print("ray_search OK")
