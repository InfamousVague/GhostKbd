"""The emulator, as tool/versus drives it: adb, the versus field's record, taps, and the checks made before each one.

Everything here speaks to one device, $VERSUS_SERIAL (default emulator-5584). Nothing is ever sent to a phone:
a serial that is not an emulator's is refused.
"""
import json
import os
import re
import subprocess
import time

SERIAL = os.environ.get("VERSUS_SERIAL", "emulator-5584")
FIELD = "com.infamousvague.versus"
FIELD_ACTIVITY = FIELD + "/.Main"
TMP = "/data/local/tmp/versus"

KEYBOARDS = {
    "ghost": {"name": "Ghost Keyboard", "package": "com.infamousvague.devkeyboard", "ime": "com.infamousvague.devkeyboard/.DevKeyboardService"},
    "gboard": {"name": "Gboard", "package": "com.google.android.inputmethod.latin", "ime": "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"},
    "swiftkey": {"name": "Microsoft SwiftKey", "package": "com.touchtype.swiftkey", "ime": "com.touchtype.swiftkey/com.touchtype.KeyboardService"},
}


class DeviceError(Exception):
    pass


def _check_serial():
    if not SERIAL.startswith("emulator-"):
        raise DeviceError("versus only drives an emulator; %r is not one" % SERIAL)


def adb(*args, **kw):
    """adb -s SERIAL args; stdout as text. Raises on a non-zero exit unless check=False."""
    _check_serial()
    check = kw.pop("check", True)
    timeout = kw.pop("timeout", 120)
    binary = kw.pop("binary", False)
    p = subprocess.run(["adb", "-s", SERIAL] + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if check and p.returncode != 0:
        raise DeviceError("adb %s: %s" % (" ".join(args)[:200], p.stderr.decode("utf-8", "replace").strip()[:400]))
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def sh(command, **kw):
    """One shell command on the device."""
    return adb("shell", command, **kw)


def front():
    """The activity in front, as package/activity."""
    out = sh("dumpsys activity activities | grep -m1 topResumedActivity")
    m = re.search(r"u0 (\S+?)[ }]", out)
    return m.group(1) if m else ""


def press_wait():
    """Presses Wait on Android's "isn't responding" dialogs, one after another, and says how many there were. A
    starved emulator puts them up (System UI, the system itself) and they take the screen from the field. Only
    ever Wait: Close app would end what is being measured. Nothing else on the screen is touched."""
    import xml.etree.ElementTree as ET
    pressed = 0
    for _ in range(6):
        if "Application Not Responding" not in sh("dumpsys window | grep -m1 mCurrentFocus", check=False):
            break
        try:
            root = ET.fromstring(windows_dump())
        except ET.ParseError:
            time.sleep(2)
            continue
        wait = [n.get("bounds", "") for n in root.iter("node") if n.get("package") == "android" and n.get("text", "").strip().lower() == "wait"]
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", wait[0]) if wait else None
        if not m:
            break
        l, t, r, b = [int(v) for v in m.groups()]
        tap((l + r) / 2.0, (t + b) / 2.0)
        pressed += 1
        time.sleep(2)
    return pressed


def current_ime():
    return sh("settings get secure default_input_method").strip()


def installed(package):
    return ("package:" + package) in sh("pm list packages " + package)


def version(package):
    """What `dumpsys package` says of an installed app: its version, when it was last installed, and its APK's hash."""
    out = sh("dumpsys package " + package)
    def find(key):
        m = re.search(key + r"=(\S+)", out)
        return m.group(1) if m else ""
    path = sh("pm path " + package).strip().splitlines()
    apk = path[0].replace("package:", "") if path else ""
    sha = sh("sha256sum " + apk).split()[0] if apk else ""
    m = re.search(r"lastUpdateTime=(.+)", out)
    return {"package": package, "versionName": find("versionName"), "versionCode": find("versionCode"), "lastUpdateTime": m.group(1).strip() if m else "", "apk": apk, "sha256": sha}


def install_stamp(package):
    """A cheap fingerprint of the installed build: its APK's path changes with every install."""
    return sh("pm path " + package).strip()


def set_ime(ime):
    """Makes [ime] the keyboard in use. `ime enable` first: a keyboard whose app was stopped is dropped from the list."""
    sh("ime enable " + ime, check=False)
    out = sh("ime set " + ime, check=False)
    if current_ime() != ime:
        raise DeviceError("could not set the keyboard to %s: %s" % (ime, out.strip()))


def reset(tag, mode="plain", learn=False, wait=0.0):
    """Empties the versus field and starts the keyboard over in it. [mode]: "plain" (a message field) or "raw" (no suggestions)."""
    sh("am start -n %s --es tag %s --es mode %s --ez learn %s >/dev/null" % (FIELD_ACTIVITY, tag, mode, "true" if learn else "false"))
    if wait:
        time.sleep(wait)


def events():
    """The versus field's record since the last reset, as a list of tuples: ('R', ms, tag, mode, learn, ime, focus),
    ('T', ms, text), ('I', ms, showing, height, window), ('F', ms, focus)."""
    out = sh("run-as %s cat files/events.log" % FIELD, check=False)
    rows = []
    for line in out.splitlines():
        f = line.split("\t")
        if len(f) < 2 or not f[1].isdigit():
            continue
        if f[0] == "T" and len(f) >= 3:
            rows.append(("T", int(f[1]), json.loads(f[2])))
        elif f[0] == "R" and len(f) >= 7:
            rows.append(("R", int(f[1]), f[2], f[3], f[4] == "true", f[5], f[6] == "true"))
        elif f[0] == "I" and len(f) >= 5:
            rows.append(("I", int(f[1]), f[2] == "true", int(f[3]), int(f[4])))
        elif f[0] == "F" and len(f) >= 3:
            rows.append(("F", int(f[1]), f[2] == "true"))
    return rows


def state(rows=None):
    """What the record says now: the tag, the keyboard, whether the field's window has focus, the keyboard's height
    (0 when it is not showing) and the field's text."""
    rows = events() if rows is None else rows
    s = {"tag": None, "ime": None, "focus": False, "showing": False, "height": 0, "text": ""}
    for r in rows:
        if r[0] == "R":
            s.update(tag=r[2], mode=r[3], learn=r[4], ime=r[5], focus=r[6], text="")
        elif r[0] == "T":
            s["text"] = r[2]
        elif r[0] == "I":
            s.update(showing=r[2], height=r[3] if r[2] else 0, window=r[4])
        elif r[0] == "F":
            s["focus"] = r[2]
    return s


def ready(tag, mode="plain", learn=False, ime=None, tries=40):
    """Resets the field and waits until the keyboard is showing over it. Returns the state; raises if it never is,
    if the field's window is not in front, or if the keyboard in use is not [ime]."""
    reset(tag, mode, learn)
    s = {}
    for _ in range(tries):
        time.sleep(0.12)
        s = state()
        if s.get("tag") == tag and s.get("showing") and s.get("height", 0) > 0 and s.get("focus"):
            if ime and s.get("ime") != ime:
                raise DeviceError("the keyboard in use is %s, not %s" % (s.get("ime"), ime))
            return s
    raise DeviceError("the keyboard never showed over the versus field (state %r, in front %r)" % (s, front()))


def tap(x, y):
    sh("input tap %.1f %.1f" % (x, y))


def text():
    return state()["text"]


def screenshot(path):
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(path, "wb") as f:
        f.write(data)


def windows_dump():
    """The accessibility tree of every window (uiautomator), the keyboard's among them, as XML text."""
    sh("mkdir -p %s; uiautomator dump --windows %s/dump.xml >/dev/null" % (TMP, TMP))
    return sh("cat %s/dump.xml" % TMP)


def field_text_by_uiautomator(xml=None):
    """The versus field's text as uiautomator reads it: the second way of reading it, to hold the first to."""
    import xml.etree.ElementTree as ET
    xml = windows_dump() if xml is None else xml
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        if node.get("class") == "android.widget.EditText" and node.get("package") == FIELD:
            return node.get("text", "")
    return None
