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


def state_focus():
    return home()["focus"]


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
    check("mInputShown=true" in adb("shell", "dumpsys", "input_method"), "the keyboard is up at once")
    adb("shell", "uiautomator", "dump", "/sdcard/ray_ui.xml")
    ui = adb("shell", "cat", "/sdcard/ray_ui.xml")
    check("<hierarchy" in ui and "Cancel" in ui, "the dialog is readable", ui[:300])
    check("Speak" not in ui and "Parla" not in ui, "no Speak button without a microphone")
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
check(h["row"][-2] == "ph:apps" or h["row"][-3] == "ph:apps", "no result: All apps is a target", h["row"])
check(h["row"][0].startswith("ph:find:") and h["focusIndex"] == 0, "no result: the first tile is focused", h)
key("DPAD_DOWN")
key("DPAD_RIGHT")
check(state_focus() == "ph:apps", "OK has a target on All apps", home())
key("DPAD_CENTER", 1)
check(home()["category"] == "apps", "OK on All apps shows every app", home())
key("BACK", 1)
key("BACK", 1)

h = search("demo")
check(not any(r.startswith("demo.app") for r in h["row"]), "the package name is not searched", h["row"])
key("BACK", 1)

h = search("film")
check(h["row"][0] == "demo.app0" and h["focusIndex"] == 0, "the app named like the query is first and focused", h)
check(h["row"][1].startswith("ph:find:"), "search inside the apps follows", h["row"])
key("BACK", 1)

h = search("t")
want = ["watch:Northern Lights", "watch:City at Night", "watch:Mountain Trails", "demo.app1", "demo.app3", "demo.app6"]
check([r.split(" (")[0] for r in h["row"][:6]] == want, "order: starts with, continue watching, word start, inside", h["row"])
key("BACK", 1)
print("ray_search OK")
