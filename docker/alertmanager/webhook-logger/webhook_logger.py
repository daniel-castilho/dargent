#!/usr/bin/env python3
"""Dargent Alertmanager webhook-logger stub (E16 S1).

One-file, stdlib-only HTTP sink (no external deps): listens on :9095 and logs every
Alertmanager webhook POST (alert groups) as single JSON lines. It exists so the
metrics profile has a REAL routing target — evidence that alerts flow end-to-end —
without any pager or external sink (spec §2 non-goal). An operator later replaces
the receiver in alertmanager.yml with a real integration; this stub is deleted.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            payload = {"raw": body.decode(errors="replace")}
        alerts = payload.get("alerts", [])
        for alert in alerts:
            labels = alert.get("labels", {})
            print(json.dumps({
                "stub": "webhook-logger",
                "alert": labels.get("alertname"),
                "severity": labels.get("severity"),
                "status": alert.get("status"),
                "summary": (alert.get("annotations") or {}).get("summary"),
            }, ensure_ascii=False), flush=True)
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt, *args):
        pass  # default access-log noise off; the alert lines above are the output


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9095
    print(json.dumps({"stub": "webhook-logger", "listening": port}), flush=True)
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()