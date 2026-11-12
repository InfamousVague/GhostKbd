#!/usr/bin/env python3
"""
The rows of the website's settings search that the app gets from the keyboard, not from its pages' own words: the
languages (Languages.kt, Layouts.kt and which next-word tables are in the app) and every connection the keyboard can
make (NetLedger.kt). Settings draws them from what the phone sends, so lib/settings/panes.dart does not hold them, and
copied by hand they went stale: on 2026-11-11 the site still said six languages, a day after the app had 38.

    tool/site_search_rows.py            writes site/js/chunks/search-rows.js
    tool/site_search_rows.py --check    says whether that file is what the sources give now (exit 1 if not)

The rows are what Settings' own search would read off those pages (lib/settings/settings_search.dart, pageEntries): a
card's title with its footer, a row's label with its hint. For the phone the rest of the index describes: English
on, nothing else touched, no speech model downloaded. A connection's row leaves out its server line, which depends on
the AI provider chosen.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "android/app/src/main/kotlin/com/infamousvague/devkeyboard"
OUT = ROOT / "site/js/chunks/search-rows.js"
STRING = r'"((?:[^"\\]|\\.)*)"'


def unescape(s):
    return s.replace('\\"', '"').replace("\\u200C", "‌").replace("\\'", "'").replace("\\\\", "\\")


def strings(text):
    """Every string literal in a piece of Kotlin, in order."""
    return [unescape(m) for m in re.findall(STRING, text)]


def joined(text, name):
    """The value of `name = "…" + "…"`: its literals put together, up to the comma that ends it."""
    start = text.index(name + " = ") + len(name) + 3
    out, i = [], start
    while True:
        m = re.compile(r"\s*" + STRING).match(text, i)
        if not m:
            break
        out.append(unescape(m.group(1)))
        i = m.end()
        plus = re.compile(r"\s*\+").match(text, i)
        if not plus:
            break
        i = plus.end()
    return "".join(out)


def blocks(text, opener):
    """The bodies of every `opener(` … `)` call, by counting brackets outside strings."""
    out = []
    for m in re.finditer(opener + r"\(", text):
        depth, i, quoted = 1, m.end(), False
        while depth:
            c = text[i]
            if quoted:
                if c == "\\":
                    i += 1
                elif c == '"':
                    quoted = False
            elif c == '"':
                quoted = True
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            i += 1
        out.append(text[m.end():i - 1])
    return out


def languages():
    src = (KOTLIN / "Languages.kt").read_text()
    layouts = (KOTLIN / "Layouts.kt").read_text()
    label = {m.group(1): unescape(m.group(2)) for m in re.finditer(r'(?:latin|LayoutSpec)\(\s*' + STRING.replace("(", "(", 1) + r',\s*' + STRING, layouts)}
    tables = {p.stem for p in (ROOT / "android/app/src/main/assets/ngram").glob("*.tsv")}
    no_capitals = strings(re.search(r"NO_CAPITALS = (.*)", src).group(1))[0]
    shared = strings(re.search(r"SHARED = (.*)", src).group(1))[0].split()
    body = src[src.index("val ALL"):src.index("private val BY_CODE")]
    found = []
    for kind, opener in (("latin", r"(?<![A-Za-z])latin"), ("other", r"(?<![A-Za-z.])Language")):
        for b in blocks(body, opener):
            lits = strings(b.split("own =")[0].split("note =")[0].split("joiners =")[0].split("short =")[0])
            code, name, english = lits[0], lits[1], lits[2]
            if kind == "latin":
                first = lits[3] if len(lits) > 3 else "qwerty"
                boards = list(dict.fromkeys([first] + shared))
            else:
                boards = strings(re.search(r"listOf\(([^)]*)\)", b).group(1))
            note = None
            if "note = NO_CAPITALS" in b:
                note = no_capitals
            elif "note = " in b:
                note = joined(b, "note")
            found.append(dict(code=code, name=name, english=english, boards=boards, note=note, dictionary="dict = null" not in b, at=body.index(b)))
    found.sort(key=lambda l: l["at"])
    assert len(found) == len({l["code"] for l in found}) and found[0]["code"] == "en", [l["code"] for l in found]

    def hint(l):
        board = label[l["boards"][0]] if len(l["boards"]) > 1 else None
        own = "Next words from your typing only" if l["dictionary"] and l["code"] not in tables else None
        english = None if l["english"] == l["name"] else l["english"]
        said = " · ".join(x for x in (english, board, own) if x)
        if l["note"]:
            said += ("" if not said else ". ") + l["note"]
        return said

    most = int(re.search(r"const val MAX = (\d+)", src).group(1))
    count = {6: "six"}.get(most, str(most))
    on = found[0]
    rows = [
        ["Your languages", f"Languages on the same keys work together, so a message can mix them. Up to {count} at once."],
        [on["name"], hint(on)],
        [f"Keys for {on['name']}"],
        ["Switching"],
        ["Globe key beside space", "Tap for the next layout; hold it, or the space bar, for the list. It shows once two layouts are on."],
        ["Add a language", "Every word list is inside the app; nothing is downloaded. Outside English, Spanish, French, German, Italian and Portuguese, autocorrect is cautious: "
            "it changes a word on its own only for a near-certain slip and offers its other guesses above the keys. No reader of each language has checked its corrections yet."],
    ]
    for l in sorted(found[1:], key=lambda l: l["english"]):
        rows.append([l["name"], hint(l)] if hint(l) else [l["name"]])
    rows.append(["Not yet"])
    for b in blocks(src[src.index("val UNAVAILABLE"):], r"(?<![A-Za-z])Unavailable"):
        name, english, why = strings(b)
        rows.append([name, f"{english}. {why}"])
    return rows, len(found), sum(1 for l in found if l["dictionary"])


def model_built():
    """Whether this tree's app has the on-phone language model's library in it: android/app/build.gradle.kts depends
    on :llm. Where it has not, the app shows nothing of that model (Net.toMap, LocalAi.built), and nor does the site."""
    gradle = (ROOT / "android/app/build.gradle.kts").read_text()
    return re.search(r'^\s*implementation\(project\(":llm"\)\)', gradle, re.M) is not None


def connections():
    src = (KOTLIN / "NetLedger.kt").read_text()
    local = model_built()
    features = []
    body = src[src.index("enum class NetFeature"):src.index("enum class NetPath")]
    for m in re.finditer(r"^    ([A-Z_]+)\(" + STRING + r",\s*" + STRING + r",\s*" + STRING, body, re.M):
        features.append(dict(const=m.group(1), id=m.group(2), title=unescape(m.group(3)), stops=unescape(m.group(4))))
    works = []
    for b in blocks(src[src.index("val WORKS"):src.index("fun hostOf")], r"(?<![A-Za-z])Works"):
        wid, title, line = strings(b)
        if wid == "rewrite-local" and not local:
            continue
        works.append([title, "Once a speech model is on the phone. None is yet." if wid == "dictation" else line])
    paths = []
    body = src[src.index("enum class NetPath"):]
    for m in re.finditer(r"^    [A-Z_]+\(\n\s+NetFeature\.([A-Z_]+), " + STRING + ", " + STRING, body, re.M):
        block = body[m.start():body.index("\n    ),", m.start())]
        paths.append(dict(feature=m.group(1), title=unescape(m.group(3)), trigger=joined(block, "trigger"), sends=joined(block, "sends")))
    # Asserted on the whole ledger, below; shown without the on-phone model's download where the build has no library.
    shown_paths = [p for p in paths if local or p["feature"] != "LOCAL_MODELS"]
    features = [f for f in features if local or f["const"] != "LOCAL_MODELS"]
    offline = [
        ["Offline is off", "One switch for every internet connection Ghost Keyboard can make."],
        ["What it stops", "Each is a network connection Ghost Keyboard would otherwise make. What leaves your phone, below, lists them one by one: the servers, what starts each, and what is sent."],
        *[[f["title"], f["stops"]] for f in features],
        ["Still works", "A privacy switch for Ghost Keyboard’s own connections. The app keeps Android’s internet permission. What you type goes to the app you type in, as with any keyboard; "
            "your password manager, Ghost.md and your browser keep their own connections. Android’s own backup is turned off for Ghost Keyboard, whatever this switch says: "
            "its settings, keys and what it has learned are not copied to your Google account or to a new phone."],
        *works,
    ]
    leaves = [["Offline", "The one switch that stops them all."]]
    for f in features:
        mine = [p for p in shown_paths if p["feature"] == f["const"]]
        assert mine, f
        leaves.append([f["title"]])
        leaves += [[p["title"], f"When: {p['trigger']} Sent: {p['sends']}"] for p in mine]
    assert len(paths) == sum(1 for _ in re.finditer(r"^    [A-Z_]+\(\n\s+NetFeature\.", body, re.M))
    return offline, leaves, len(shown_paths)


def made():
    language_rows, typed, listed = languages()
    offline, leaves, paths = connections()

    def js(name, rows, said):
        lines = ",\n".join("  " + json.dumps(r, ensure_ascii=False) for r in rows)
        return f"/** {said} */\nexport const {name} = [\n{lines},\n];\n"

    return (
        "// MADE, not written: tool/site_search_rows.py reads these off the keyboard's own sources (Languages.kt, Layouts.kt,\n"
        "// NetLedger.kt). Change them there and run it again; `tool/site_search_rows.py --check` says whether this is current.\n\n"
        + js("LANGUAGE_ROWS", language_rows, f"Settings › Typing › Languages, with English on: {typed} languages, {listed} with a word list.")
        + "\n" + js("OFFLINE_ROWS", offline, "Settings › Offline, with the switch off and no speech model on the phone.")
        + "\n" + js("LEAVES_ROWS", leaves, f"Settings › Offline › What leaves your phone: the {paths} connections, without their server lines.")
    )


if __name__ == "__main__":
    text = made()
    if "--check" in sys.argv[1:]:
        if not OUT.exists() or OUT.read_text() != text:
            sys.exit(f"{OUT.relative_to(ROOT)} is not what the sources give now: run tool/site_search_rows.py")
        print(f"{OUT.relative_to(ROOT)} is current")
    else:
        OUT.write_text(text)
        print(f"wrote {OUT.relative_to(ROOT)}: {text.count(chr(10))} lines")
