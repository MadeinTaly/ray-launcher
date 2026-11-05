#!/usr/bin/env python3
"""Phone and TV emulators together, dev builds, checked through the debug channel of both, sensors injected.

    adb -s emulator-5556 forward tcp:18766 tcp:8766
    python test/ray_phone.py emulator-5554 emulator-5556 18766
"""
import json, re, socket, subprocess, sys, time

PHONE, TV, PORT = (sys.argv[1:] + ["emulator-5554", "emulator-5556", "18766"][len(sys.argv) - 1:])[:3]
PKG = "dev.raylauncher"


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def state(serial):
    for _ in range(20):
        out = adb(serial, "shell", "dumpsys", "activity", "service", PKG + "/.DimService", "debug")
        if "{" in out:
            return json.loads(out[out.index("{"):out.rindex("}") + 1])
        time.sleep(.5)
    sys.exit("no debug channel on " + serial)


def check(ok, what, detail=None):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        print(json.dumps(detail, indent=1) if detail is not None else "")
        sys.exit(1)


def sensor(name, x, y, z):
    adb(PHONE, "emu", "sensor", "set", name, "%g:%g:%g" % (x, y, z))


def moved(seconds=1.0):
    a = state(PHONE)["phone"]
    time.sleep(seconds)
    b = state(PHONE)["phone"]
    return b["movedX"] - a["movedX"], b["movedY"] - a["movedY"], b["sensorEvents"] - a["sensorEvents"]


def tap_text(*labels):
    adb(PHONE, "shell", "uiautomator", "dump", "/data/local/tmp/ui.xml")
    xml = adb(PHONE, "shell", "cat", "/data/local/tmp/ui.xml")
    for label in labels:
        m = re.search(r'(?:text|content-desc)="%s[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % label, xml)
        if m:
            x0, y0, x1, y1 = map(int, m.groups())
            adb(PHONE, "shell", "input", "tap", str((x0 + x1) // 2), str((y0 + y1) // 2))
            return True
    return False


# link the phone to the TV as a scanned QR would
adb(TV, "shell", "am", "start", "-n", PKG + "/.RayLauncherActivity")
time.sleep(5)
prefs = adb(TV, "shell", "run-as", PKG, "cat", "shared_prefs/dim.xml")
m = re.search(r'name="[^"]*key[^"]*">([0-9a-f]{8,})<', prefs)
check(bool(m), "the TV has a pairing key")
adb(PHONE, "shell", "am", "force-stop", "dev.midnightbeam")
adb(PHONE, "shell", "am", "force-stop", PKG)
adb(PHONE, "shell", "am", "start", "-n", PKG + "/.PhoneActivity", "-a", "android.intent.action.VIEW", "-d", "'http://10.0.2.2:%s/ray#k=%s'" % (PORT, m.group(1)))
for _ in range(30):
    time.sleep(1)
    s = state(PHONE)["phone"]
    if s.get("rayPage"):
        break
check(s.get("rayPage"), "the phone opens the remote of the TV", s)
time.sleep(2)
check(state(TV)["remote"]["sockets"] >= 1, "the TV sees the phone", state(TV)["remote"])
check(state(TV)["home"].get("connectVisible") is True, "the home shows the small connected label", state(TV)["home"])

for _ in range(4):  # the Motion button: fixed place on the 320x640 emulator; the page may still be drawing
    adb(PHONE, "shell", "input", "tap", "160", "123")
    time.sleep(2)
    s = state(PHONE)["phone"]
    if s["motion"]:
        break
check(s["motion"] and s["sensor"], "Motion on, sensors running", s)


def pose(ax, ay, az, label):
    sensor("gyroscope", 0, 0, 0)
    sensor("acceleration", ax, ay, az)
    time.sleep(2.5)
    print("     " + label + ", up =", state(PHONE)["phone"]["up"])


def expect_axis(wx, wy, wz, axis, sign, what):
    sensor("gyroscope", wx, wy, wz)
    dx, dy, n = moved()
    sensor("gyroscope", 0, 0, 0)
    main, other = (dx, dy) if axis == "x" else (dy, dx)
    check(n > 5 and main * sign > 20 and abs(other) < abs(main) * .15, "%s: dx %.0f dy %.0f" % (what, dx, dy), [dx, dy, n])


pose(0, 0, 9.8, "flat")
expect_axis(0, 0, 1, "x", -1, "flat, turn left")
expect_axis(1, 0, 0, "y", -1, "flat, nose up")
pose(9.8, 0, 0, "on its side, volume keys up")
expect_axis(1, 0, 0, "x", -1, "side, turn left")
expect_axis(0, 0, -1, "y", -1, "side, nose up")

# physical keys
before = len(state(TV)["events"])
adb(PHONE, "shell", "input", "keyevent", "KEYCODE_VOLUME_DOWN")
time.sleep(1)
events = state(PHONE)["events"]
check(any("volume key: select" in e for e in events), "volume down selects", events[-4:])
tv = state(TV)["events"]
check(any("cmd remote.key" in e for e in tv[-8:]), "the TV received the select", tv[-4:])
adb(PHONE, "shell", "input", "keyevent", "KEYCODE_VOLUME_UP")
time.sleep(.5)
check(any("volume key: recenter" in e for e in state(PHONE)["events"]), "volume up recenters")
sensor("gyroscope", 0, 0, 0)
sensor("acceleration", 0, 9.8, 0)  # upright again for the next run
rot = adb(PHONE, "shell", "dumpsys", "window", "displays")
check("cur=320x640" in rot or "cur=640x320" not in rot, "the remote stays upright however the phone is held")
print("ray_phone OK")
