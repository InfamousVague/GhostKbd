#!/usr/bin/env python3
"""A stand-in AI provider and a page to type in, for the keyboard's UI tests (ios/RunnerUITests).

  python3 ios/tool/stub_server.py [port]        # 8765 unless told otherwise

/pad                   a page with one text area, to type in from Safari on the Simulator
/v1/chat/completions   an OpenAI-compatible answer: "Stubbed answer." (or $STUB_ANSWER); every request is appended to requests.log
/v1/models             one model
"""
import json, sys, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "requests.log")
PAD = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>pad</title><body style="font:17px -apple-system;margin:12px">
<textarea id=pad aria-label="pad" style="width:100%;height:160px;font:17px -apple-system" autocapitalize="sentences"></textarea>
<input id=plain aria-label="plain" autocorrect="off" autocapitalize="none" style="width:100%;font:17px -apple-system;margin-top:8px">
</body>"""

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body, kind="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code); self.send_header("Content-Type", kind); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path.startswith("/pad"): self.send(200, PAD, "text/html; charset=utf-8")
        elif self.path.startswith("/v1/models"): self.send(200, json.dumps({"data": [{"id": "stub-1"}]}))
        else: self.send(404, "{}")
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8", "replace")
        with open(LOG, "a") as f: f.write(json.dumps({"path": self.path, "auth": self.headers.get("Authorization", ""), "body": body}) + "\n")
        self.send(200, json.dumps({"choices": [{"message": {"role": "assistant", "content": os.environ.get("STUB_ANSWER", "Stubbed answer.")}, "finish_reason": "stop"}]}))

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
