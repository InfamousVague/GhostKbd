#!/usr/bin/env python3
"""Gets the emulator ready for a versus run, and puts it back afterwards: tool/versus/prepare.py <step>.

    snapshot        saves what will be changed: Ghost Keyboard's own data (its settings and everything it has
                    learned; a debug build's, by run-as and tar, to /data/local/tmp on the emulator and to
                    build/versus/backup/ here), the build of it that is installed (its APK, to
                    build/versus/backup/), and which keyboard is in use
    clear ghost     Ghost Keyboard as a new install has it: `pm clear`, so default settings and nothing learned.
                    Refused until there is a snapshot
    clear gboard    Gboard's own "Delete learned words and data" (Settings, Privacy), found by its words on the
                    screen. Its settings are left as they are. Says so, and changes nothing, if it cannot find its way
    restore         the build of Ghost Keyboard that was installed back, where another has been put over it since
                    (a build with a change to try), its data back from the snapshot, the keyboard that was in use
                    back in use, the versus field uninstalled, the launcher in front

A keyboard is cleared so that nothing typed on the emulator before the run is in what it reads sentences by. The
run itself teaches nothing: the versus field asks keyboards not to learn from it.
"""
import datetime
import hashlib
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import device as d  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BACKUP = os.path.join(ROOT, "build", "versus", "backup")
STATE = os.path.join(BACKUP, "state.json")
GHOST = d.KEYBOARDS["ghost"]["package"]
DEVICE_TAR = "/data/local/tmp/versus-ghost-before.tar"
APK = os.path.join(BACKUP, "ghost-before.apk")


def snapshot():
    os.makedirs(BACKUP, exist_ok=True)
    if os.path.exists(STATE):
        raise SystemExit("prepare: there is a snapshot already (%s); restore it, or move it away, before taking another" % os.path.relpath(STATE))
    state = {"taken": datetime.datetime.now().astimezone().isoformat(timespec="seconds"), "serial": d.SERIAL, "ime": d.current_ime(), "front": d.front()}
    if d.installed(GHOST):
        # Its caches are left out: Android makes them again.
        d.sh("run-as %s tar -cf - --exclude=./cache --exclude=./code_cache . > %s" % (GHOST, DEVICE_TAR))
        data = d.adb("exec-out", "cat " + DEVICE_TAR, binary=True)
        with open(os.path.join(BACKUP, "ghost-before.tar"), "wb") as f:
            f.write(data)
        state["ghost"] = {"version": d.version(GHOST), "tar": DEVICE_TAR, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        on_device = d.sh("sha256sum " + DEVICE_TAR).split()[0]
        if on_device != state["ghost"]["sha256"]:
            raise SystemExit("prepare: the snapshot here is not the one on the emulator")
        # The build itself, for putting back after one with a change in it has been tried over it.
        apk = d.adb("exec-out", "cat " + state["ghost"]["version"]["apk"], binary=True)
        if hashlib.sha256(apk).hexdigest() != state["ghost"]["version"]["sha256"]:
            raise SystemExit("prepare: the APK pulled from the emulator is not the one installed there")
        with open(APK, "wb") as f:
            f.write(apk)
    with open(STATE, "w") as f:
        json.dump(state, f, indent=1)
        f.write("\n")
    print("snapshot: the keyboard in use is %s; Ghost Keyboard's data, %d bytes, sha256 %s" % (state["ime"], state.get("ghost", {}).get("bytes", 0), state.get("ghost", {}).get("sha256", "-")[:12]))


def clear_ghost():
    if not os.path.exists(STATE) or "ghost" not in json.load(open(STATE)):
        raise SystemExit("prepare: no snapshot of Ghost Keyboard's data; run `prepare.py snapshot` first")
    print(d.sh("pm clear " + GHOST).strip())
    # A cleared app's keyboard is dropped from the list of keyboards that are on.
    d.sh("ime enable " + d.KEYBOARDS["ghost"]["ime"], check=False)
    print("Ghost Keyboard: default settings, nothing learned")


def nodes():
    """(text, content description, class, [l, t, r, b]) of every node on the screen."""
    import xml.etree.ElementTree as ET
    out = []
    try:
        root = ET.fromstring(d.windows_dump())
    except ET.ParseError:
        return out
    for node in root.iter("node"):
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        if m:
            out.append((node.get("text", ""), node.get("content-desc", ""), node.get("class", ""), [int(v) for v in m.groups()]))
    return out


def tap_text(wanted, tries=6, scroll=True):
    """Taps the first thing on the screen whose words start with [wanted]; scrolls down to look for it. True if it did."""
    for attempt in range(tries):
        for text, desc, _, b in nodes():
            if text.lower().startswith(wanted.lower()) or desc.lower().startswith(wanted.lower()):
                d.tap((b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0)
                time.sleep(1.0)
                return True
        if not scroll:
            break
        d.sh("input swipe 600 1500 600 700 300")
        time.sleep(0.8)
    return False


def clear_gboard():
    kb = d.KEYBOARDS["gboard"]
    where = d.front()
    if not (where.startswith(d.FIELD) or "launcher" in where.lower()):
        raise SystemExit("prepare: %s is in front on %s; not touching it" % (where, d.SERIAL))
    # From its first page, wherever its settings were left open.
    d.sh("am start --activity-clear-task -n %s/com.google.android.apps.inputmethod.latin.preference.SettingsActivity >/dev/null" % kb["package"])
    time.sleep(2.5)
    try:
        if not tap_text("Privacy"):
            raise SystemExit("prepare: no \"Privacy\" in Gboard's settings; delete its learned words by hand (Settings, Privacy, Delete learned words and data)")
        if not tap_text("Delete learned words"):
            raise SystemExit("prepare: no \"Delete learned words and data\" on Gboard's Privacy page; do it by hand")
        # It asks for a number it shows, to be sure it is meant.
        seen = nodes()
        code = [t.strip() for t, _, _, _ in seen if re.fullmatch(r"\d{3,6}", t.strip())]
        field = [b for _, _, cls, b in seen if cls.endswith("EditText")]
        if len(code) != 1 or not field:
            raise SystemExit("prepare: Gboard's question did not show one number and a place to type it; do it by hand")
        d.tap((field[0][0] + field[0][2]) / 2.0, (field[0][1] + field[0][3]) / 2.0)
        time.sleep(1.0)
        d.sh("input text " + code[0])
        time.sleep(1.0)
        # The keyboard has come up and moved the question: its OK is found again where it is now.
        ok = [b for t, _, cls, b in nodes() if t.strip().upper() == "OK" and cls.endswith("Button")]
        if not ok:
            raise SystemExit("prepare: typed %s but found no OK to press; finish it by hand" % code[0])
        d.tap((ok[0][0] + ok[0][2]) / 2.0, (ok[0][1] + ok[0][3]) / 2.0)
        time.sleep(2.0)
        if any(cls.endswith("EditText") for _, _, cls, _ in nodes()):
            raise SystemExit("prepare: pressed OK and Gboard's question is still there; finish it by hand")
        print("Gboard: its learned words and data deleted (it asked for %s)" % code[0])
    finally:
        d.sh("input keyevent KEYCODE_HOME")


def restore():
    if not os.path.exists(STATE):
        raise SystemExit("prepare: no snapshot to restore (%s)" % os.path.relpath(STATE))
    state = json.load(open(STATE))
    d.sh("input keyevent KEYCODE_HOME")
    if "ghost" in state and d.installed(GHOST) and d.version(GHOST)["sha256"] != state["ghost"]["version"]["sha256"]:
        # Another build was put over it since the snapshot: the one that was there goes back first (an older one
        # over a newer needs -d, which a debug build allows).
        if not os.path.exists(APK) or hashlib.sha256(open(APK, "rb").read()).hexdigest() != state["ghost"]["version"]["sha256"]:
            raise SystemExit("prepare: Ghost Keyboard is not the build the snapshot was taken of, and %s is not that build either: put it back by hand, then restore again" % os.path.relpath(APK))
        free = int(os.popen("df -m / | tail -1").read().split()[3])
        if free < 1024:
            raise SystemExit("prepare: only %d MB free on this Mac; not installing" % free)
        print(d.adb("install", "-r", "-d", APK, timeout=300).strip().splitlines()[-1])
        if d.version(GHOST)["sha256"] != state["ghost"]["version"]["sha256"]:
            raise SystemExit("prepare: the build that was installed did not go back")
        print("Ghost Keyboard %s (%s) is installed again, the build the snapshot was taken of" % (state["ghost"]["version"]["versionName"], state["ghost"]["version"]["versionCode"]))
    if "ghost" in state and d.installed(GHOST):
        if d.sh("sha256sum " + DEVICE_TAR, check=False).split()[:1] != [state["ghost"]["sha256"]]:
            # The emulator's copy is gone or changed: send this Mac's.
            d.adb("push", os.path.join(BACKUP, "ghost-before.tar"), DEVICE_TAR)
        d.sh("pm clear " + GHOST)
        d.sh("cat %s | run-as %s tar -xf -" % (DEVICE_TAR, GHOST))
        d.sh("ime enable " + d.KEYBOARDS["ghost"]["ime"], check=False)
        back = d.sh("run-as %s tar -cf - --exclude=./cache --exclude=./code_cache . | sha256sum" % GHOST).split()[0]
        print("Ghost Keyboard's data put back (%s)" % ("the same, byte for byte" if back == state["ghost"]["sha256"] else "its files are back; the archive of them differs, as times and order may"))
    if state.get("ime"):
        d.sh("ime enable " + state["ime"], check=False)
        d.sh("ime set " + state["ime"], check=False)
    if d.installed(d.FIELD):
        d.adb("uninstall", d.FIELD, check=False)
    os.rename(STATE, os.path.join(BACKUP, "state-restored-%s.json" % datetime.datetime.now().strftime("%Y%m%d-%H%M%S")))
    print("the keyboard in use: %s; in front: %s" % (d.current_ime(), d.front()))


def main():
    step = sys.argv[1:] or [""]
    if step == ["snapshot"]:
        snapshot()
    elif step == ["clear", "ghost"]:
        clear_ghost()
    elif step == ["clear", "gboard"]:
        clear_gboard()
    elif step == ["restore"]:
        restore()
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
