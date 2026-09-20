#!/usr/bin/env python3
"""Advertise a bounded synthetic shin phone for Linux OBS smoke tests."""

from __future__ import annotations

import argparse
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer


class ProbeState:
    def __init__(self) -> None:
        self.reserved_by = ""
        self.reserve_seen = threading.Event()
        self.release_seen = threading.Event()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--duration", type=int, default=30)
    parser.add_argument("--discovery-port", type=int, default=51615)
    parser.add_argument("--media-port", type=int, default=9100)
    parser.add_argument("--control-port", type=int, default=9101)
    args = parser.parse_args()
    state = ProbeState()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802 - stdlib callback name
            length = min(int(self.headers.get("Content-Length", "0")), 8192)
            body = self.rfile.read(length)
            try:
                payload = json.loads(body or b"{}")
            except json.JSONDecodeError:
                payload = {}
            if self.path == "/reserve":
                state.reserved_by = str(payload.get("sourceInstanceId", ""))
                state.reserve_seen.set()
                print(f"reserve accepted for {state.reserved_by}", flush=True)
            elif self.path == "/release":
                state.reserved_by = ""
                state.release_seen.set()
                print("release accepted", flush=True)
            response = b'{"ok":true}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        def log_message(self, _format: str, *_args: object) -> None:
            return

    server = HTTPServer(("127.0.0.1", args.control_port), Handler)
    server.timeout = 0.25
    stop = threading.Event()

    def advertise() -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        deadline = time.monotonic() + args.duration
        try:
            while time.monotonic() < deadline and not stop.is_set():
                beacon = {
                    "type": "dev.shin.phone",
                    "version": 1,
                    "name": "shin probe",
                    "instanceId": "shin-probe-phone",
                    "host": "127.0.0.1",
                    "listenerPort": args.media_port,
                    "controlPort": args.control_port,
                    "latencyMs": 120,
                    "codec": "video/avc",
                    "width": 640,
                    "height": 360,
                    "fps": 30,
                    "busy": bool(state.reserved_by),
                    "reservedBy": state.reserved_by,
                }
                data = ("SHIN_PHONE/1 " + json.dumps(beacon, separators=(",", ":"))).encode()
                sock.sendto(data, ("127.0.0.1", args.discovery_port))
                stop.wait(0.25)
        finally:
            sock.close()

    thread = threading.Thread(target=advertise, name="shin-probe-advertiser", daemon=True)
    thread.start()
    deadline = time.monotonic() + args.duration
    try:
        while time.monotonic() < deadline:
            server.handle_request()
    finally:
        stop.set()
        thread.join(timeout=1)
        server.server_close()
    if not state.reserve_seen.is_set():
        print("FAIL: OBS did not reserve the shin phone", flush=True)
        return 1
    print("PASS: OBS discovered and reserved the shin phone", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
