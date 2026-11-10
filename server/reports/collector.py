#!/usr/bin/env python3
"""Ghost Keyboard's report collector: takes the anonymous typing reports the keyboard sends (Reports.kt, off unless
the person turns it on) and appends each as one line to a file a day.

Standard library only. It listens on 127.0.0.1 behind the web server, which passes it POST /devkeyboard/report.

What it keeps is what clean() lets through and nothing else: a report is read against the same rules the keyboard
wrote it by, anything it does not know is dropped, and anything malformed refuses the whole report. It keeps no
address, no header, no time of day: a line in <dir>/YYYY-MM-DD.jsonl (the server's UTC day) is all there is, and
nothing in a line says which phone it came from or links it to another line.

    REPORT_DIR=/var/lib/ghostkbd-reports REPORT_PORT=8377 python3 collector.py

docs/reports.md says what a report holds; README.md here says how this is run on the box.
"""
import datetime
import json
import os
import re
import socketserver
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = 1
MAX_BODY = 96 * 1024
#: Reports taken in one UTC day, and bytes kept in all: past either, 429 and 507. A report is a few kilobytes.
MAX_PER_DAY = int(os.environ.get("REPORT_MAX_PER_DAY", "20000"))
MAX_BYTES = int(os.environ.get("REPORT_MAX_BYTES", str(2 * 1024 ** 3)))

GRID = 12 * 12
MAX_COUNT = 10_000_000
KINDS = {"retyped", "picked", "undone"}
STROKE_KINDS = {"kept", "picked", "redrawn"}
NAME = re.compile(r"[a-z][A-Za-z0-9.]{0,31}\Z")
DAY = re.compile(r"\d{4}-\d{2}-\d{2}\Z")
APP = re.compile(r"\d{1,3}\.\d{1,3}\.\d{1,3}\Z")
LAYOUT = re.compile(r"[a-z0-9_-]{1,24}\Z")
LANGUAGE = re.compile(r"[a-z]{2,3}(?:[-_][A-Za-z]{2,4})?\Z")


class Bad(ValueError):
    """The report is not one the keyboard writes."""


def _word(s, spaces):
    """Letters and apostrophes only, with single spaces between words where a phrase is allowed: as ReportTally.plain."""
    if not isinstance(s, str) or not 2 <= len(s) <= (48 if spaces else 24):
        raise Bad("word")
    if s[0] == " " or s[-1] == " " or "  " in s:
        raise Bad("word")
    for c in s:
        if not (c.isalpha() or c in "'’" or (spaces and c == " ")):
            raise Bad("word")
    return s


def _count(n, most=MAX_COUNT, least=0):
    if isinstance(n, bool) or not isinstance(n, int) or not least <= n <= most:
        raise Bad("count")
    return n


def _day(s):
    if not isinstance(s, str) or not DAY.match(s):
        raise Bad("day")
    try:
        return datetime.date.fromisoformat(s)
    except ValueError:
        raise Bad("day")


def clean(report):
    """The report with only what the keyboard writes in it, or Bad. Nothing is passed through unlooked at."""
    if not isinstance(report, dict) or report.get("v") != VERSION:
        raise Bad("version")
    out = {"v": VERSION}
    app = report.get("app")
    if not isinstance(app, str) or not APP.match(app):
        raise Bad("app")
    out["app"] = app
    start, end = _day(report.get("from")), _day(report.get("to"))
    if end < start or (end - start).days > 15:
        raise Bad("days")
    out["from"], out["to"] = start.isoformat(), end.isoformat()

    board = report.get("board")
    if board is not None:
        if not isinstance(board, dict):
            raise Bad("board")
        layout, languages = board.get("layout"), board.get("languages")
        if not isinstance(layout, str) or not LAYOUT.match(layout):
            raise Bad("layout")
        if not isinstance(languages, list) or len(languages) > 6 or any(not isinstance(l, str) or not LANGUAGE.match(l) for l in languages):
            raise Bad("languages")
        aspect = board.get("rowAspect")
        if isinstance(aspect, bool) or not isinstance(aspect, (int, float)) or not 0.3 <= aspect <= 3:
            raise Bad("rowAspect")
        for flag in ("condensed", "numberRow"):
            if not isinstance(board.get(flag), bool):
                raise Bad(flag)
        out["board"] = {
            "layout": layout, "languages": languages, "keyDp": _count(board.get("keyDp"), 300, 8), "rowAspect": round(float(aspect), 2),
            "condensed": board["condensed"], "numberRow": board["numberRow"],
        }

    counts = report.get("counts")
    if not isinstance(counts, dict) or len(counts) > 64:
        raise Bad("counts")
    out["counts"] = {}
    for name in sorted(counts):
        if not isinstance(name, str) or not NAME.match(name):
            raise Bad("count name")
        out["counts"][name] = _count(counts[name])

    taps = report.get("taps")
    if not isinstance(taps, dict) or taps.get("bin") != 0.25 or taps.get("span") != 1.5:
        raise Bad("taps")
    grids = {}
    for name in ("own", "slip"):
        grid = taps.get(name)
        if not isinstance(grid, list) or len(grid) != GRID:
            raise Bad("grid")
        grids[name] = [_count(n) for n in grid]
    out["taps"] = {"bin": 0.25, "span": 1.5, **grids}

    mistakes = report.get("mistakes")
    if not isinstance(mistakes, list) or len(mistakes) > 60:
        raise Bad("mistakes")
    out["mistakes"] = []
    for m in mistakes:
        if not isinstance(m, list) or len(m) != 4 or m[0] not in KINDS or m[1] == m[2]:
            raise Bad("mistake")
        out["mistakes"].append([m[0], _word(m[1], False), _word(m[2], True), _count(m[3], least=1)])

    strokes = report.get("strokes")
    if not isinstance(strokes, list) or len(strokes) > 12:
        raise Bad("strokes")
    out["strokes"] = []
    for s in strokes:
        if not isinstance(s, dict) or s.get("kind") not in STROKE_KINDS:
            raise Bad("stroke")
        read, path = s.get("read"), s.get("path")
        if not isinstance(read, list) or len(read) > 3:
            raise Bad("read")
        if not isinstance(path, list) or not 4 <= len(path) <= 96 or len(path) % 2:
            raise Bad("path")
        for v in path:
            if isinstance(v, bool) or not isinstance(v, (int, float)) or v != v or not -8 <= v <= 30:
                raise Bad("path")
        out["strokes"].append({"kind": s["kind"], "read": [_word(r, True) for r in read], "meant": _word(s.get("meant"), True), "path": [round(float(v), 2) for v in path]})

    ms = report.get("swipeMs")
    if not isinstance(ms, list) or len(ms) != 8:
        raise Bad("swipeMs")
    out["swipeMs"] = [_count(n) for n in ms]
    return out


class Store:
    """One file a day, appended to a line a report; the day's count and the bytes kept, so a flood stops at a wall."""

    def __init__(self, directory):
        self.directory = directory
        self.lock = threading.Lock()
        self.day = None
        self.today = 0
        os.makedirs(directory, exist_ok=True)

    def _path(self, day):
        return os.path.join(self.directory, day + ".jsonl")

    def add(self, report):
        """Appends the report. "full" where the day has had its share, "no room" where the files have theirs, else None."""
        line = json.dumps(report, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
        day = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
        with self.lock:
            if day != self.day:
                self.day = day
                try:
                    with open(self._path(day), "rb") as f:
                        self.today = sum(1 for _ in f)
                except FileNotFoundError:
                    self.today = 0
            if self.today >= MAX_PER_DAY:
                return "full"
            kept = sum(e.stat().st_size for e in os.scandir(self.directory) if e.is_file())
            if kept + len(line.encode()) > MAX_BYTES:
                return "no room"
            with open(self._path(day), "a", encoding="utf-8") as f:
                f.write(line)
            self.today += 1
        return None


def handler(store):
    class Handler(BaseHTTPRequestHandler):
        server_version = "GhostKbdReports"
        sys_version = ""
        timeout = 15

        def log_message(self, *args):  # nothing is logged of who asked, here or anywhere
            pass

        def _say(self, code):
            self.send_response(code)
            self.send_header("Content-Length", "0")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()

        def do_POST(self):
            if not self.path.split("?")[0].rstrip("/").endswith("/report"):
                return self._say(404)
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                return self._say(411)
            if length <= 0 or length > MAX_BODY:
                return self._say(413)
            try:
                report = clean(json.loads(self.rfile.read(length).decode("utf-8")))
            except (Bad, ValueError, UnicodeDecodeError, RecursionError):
                return self._say(400)
            refused = store.add(report)
            self._say(429 if refused == "full" else 507 if refused else 204)

        def do_GET(self):
            self._say(405)

        do_PUT = do_DELETE = do_PATCH = do_HEAD = do_GET

    return Handler


class Server(ThreadingHTTPServer):
    """This machine only, and nothing asked of the network: not even the name of its own address, which the standard
    server looks up as it starts."""

    daemon_threads = True

    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = "localhost", self.server_address[1]


def main():
    directory = os.environ.get("REPORT_DIR") or os.environ.get("STATE_DIRECTORY") or "./reports"
    port = int(os.environ.get("REPORT_PORT", "8377"))
    server = Server(("127.0.0.1", port), handler(Store(directory)))
    print(f"ghost keyboard reports: 127.0.0.1:{port}, kept in {directory}", file=sys.stderr, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
