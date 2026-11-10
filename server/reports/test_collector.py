#!/usr/bin/env python3
"""The collector's own check: python3 -m unittest server/reports/test_collector.py (standard library only)."""
import copy
import http.client
import json
import os
import tempfile
import threading
import unittest
import collector

GOOD = {
    "v": 1, "app": "0.12.0", "from": "2026-11-10", "to": "2026-11-11",
    "board": {"layout": "qwerty", "languages": ["en", "es"], "keyDp": 44, "rowAspect": 0.95, "condensed": True, "numberRow": False},
    "counts": {"words.typed": 412, "fix.made": 31, "fix.undone": 2, "keys": 2301, "swipe.kept": 12},
    "taps": {"bin": 0.25, "span": 1.5, "own": [0] * 144, "slip": [0] * 144},
    "mistakes": [["retyped", "teh", "the", 3], ["undone", "form", "from", 1], ["picked", "thankyiu", "thank you", 1]],
    "strokes": [{"kind": "picked", "read": ["form", "from", "for"], "meant": "from", "path": [3.0, 1.0, 3.52, 0.0, 8.5, 0.0, 6.5, 2.0]}],
    "swipeMs": [0, 3, 9, 1, 0, 0, 0, 0],
}


class Clean(unittest.TestCase):
    def test_a_report_as_the_keyboard_writes_it_is_kept_whole(self):
        self.assertEqual(collector.clean(copy.deepcopy(GOOD)), GOOD)

    def test_what_it_does_not_know_is_dropped(self):
        r = copy.deepcopy(GOOD)
        r["device"] = "SM-F966"
        r["id"] = "abc"
        r["board"]["model"] = "x"
        r["strokes"][0]["at"] = 1731200000
        self.assertEqual(collector.clean(r), GOOD)

    def test_anything_but_letters_refuses_the_report(self):
        for typed, meant in [("p4ssword", "password"), ("teh", "the "), ("a@b.co", "about"), ("teh", "the\n"), ("x", "the"), ("teh", "t" * 60), ("555", "five"), ("same", "same")]:
            r = copy.deepcopy(GOOD)
            r["mistakes"] = [["retyped", typed, meant, 1]]
            with self.assertRaises(collector.Bad, msg=f"{typed!r} → {meant!r}"):
                collector.clean(r)
        r = copy.deepcopy(GOOD)
        r["strokes"][0]["meant"] = "call 5551234"
        with self.assertRaises(collector.Bad):
            collector.clean(r)

    def test_the_bounds_hold(self):
        def broken(change):
            r = copy.deepcopy(GOOD)
            change(r)
            with self.assertRaises(collector.Bad):
                collector.clean(r)
        broken(lambda r: r.update(v=2))
        broken(lambda r: r.update(app="0.12.0-evil<script>"))
        broken(lambda r: r.update(to="2027-01-01"))
        broken(lambda r: r.update(**{"from": "yesterday"}))
        broken(lambda r: r["counts"].update({"x" * 40: 1}))
        broken(lambda r: r["counts"].update({"words.typed": -1}))
        broken(lambda r: r["counts"].update({"words.typed": 1.5}))
        broken(lambda r: r["counts"].update({"words.typed": True}))
        broken(lambda r: r["taps"].update(own=[0] * 143))
        broken(lambda r: r["taps"].update(bin=0.5))
        broken(lambda r: r.update(mistakes=[["retyped", "teh", "the", 1]] * 61))
        broken(lambda r: r.update(mistakes=[["guessed", "teh", "the", 1]]))
        broken(lambda r: r.update(strokes=[GOOD["strokes"][0]] * 13))
        broken(lambda r: r["strokes"][0].update(path=[0.0] * 98))
        broken(lambda r: r["strokes"][0].update(path=[0.0, 1.0, 2.0]))
        broken(lambda r: r["strokes"][0].update(path=[0.0, 1.0, float("nan"), 2.0]))
        broken(lambda r: r["strokes"][0].update(path=[0.0, 1.0, 900.0, 2.0]))
        broken(lambda r: r["board"].update(layout="../../etc"))
        broken(lambda r: r["board"].update(languages=["en"] * 7))
        broken(lambda r: r.update(swipeMs=[0] * 9))
        for junk in ([], "report", 7, None):
            with self.assertRaises(collector.Bad):
                collector.clean(junk)

    def test_a_report_without_a_board_is_still_one(self):
        r = copy.deepcopy(GOOD)
        del r["board"]
        self.assertNotIn("board", collector.clean(r))


class Serve(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.store = collector.Store(self.dir.name)
        self.server = collector.Server(("127.0.0.1", 0), collector.handler(self.store))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.dir.cleanup()

    def post(self, body, path="/devkeyboard/report", headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=5)
        c.request("POST", path, body=body, headers={"Content-Type": "application/json", "User-Agent": "GhostKeyboard", "X-Forwarded-For": "203.0.113.9", **(headers or {})})
        r = c.getresponse()
        r.read()
        c.close()
        return r.status

    def text(self):
        out = ""
        for name in sorted(os.listdir(self.dir.name)):
            with open(os.path.join(self.dir.name, name), encoding="utf-8") as f:
                out += f.read()
        return out

    def kept(self):
        return [json.loads(line) for line in self.text().splitlines()]

    def test_a_report_is_one_line_with_nothing_of_who_sent_it(self):
        self.assertEqual(self.post(json.dumps(GOOD)), 204)
        self.assertEqual(self.post(json.dumps(GOOD), path="/report"), 204)
        self.assertEqual(self.kept(), [GOOD, GOOD])
        for word in ("203.0.113.9", "GhostKeyboard", "127.0.0.1", "Forwarded"):
            self.assertNotIn(word, self.text())
        # The file's name is the day, and that is the only time there is.
        self.assertRegex(os.listdir(self.dir.name)[0], r"^\d{4}-\d{2}-\d{2}\.jsonl$")

    def test_what_is_not_a_report_is_refused_and_nothing_is_kept(self):
        self.assertEqual(self.post("not json"), 400)
        self.assertEqual(self.post(json.dumps({"v": 1})), 400)
        self.assertEqual(self.post(json.dumps(GOOD), path="/devkeyboard/elsewhere"), 404)
        self.assertEqual(self.post("x" * (collector.MAX_BODY + 1)), 413)
        bad = copy.deepcopy(GOOD)
        bad["mistakes"].append(["retyped", "hunter2", "hunter", 1])
        self.assertEqual(self.post(json.dumps(bad)), 400)
        self.assertEqual(self.kept(), [])
        c = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=5)
        c.request("GET", "/devkeyboard/report")
        self.assertEqual(c.getresponse().status, 405)
        c.close()

    def test_a_day_takes_only_so_many(self):
        most = collector.MAX_PER_DAY
        collector.MAX_PER_DAY = 2
        try:
            self.assertEqual([self.post(json.dumps(GOOD)) for _ in range(3)], [204, 204, 429])
        finally:
            collector.MAX_PER_DAY = most
        self.assertEqual(len(self.kept()), 2)


if __name__ == "__main__":
    unittest.main()
