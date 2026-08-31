#!/usr/bin/env python3
"""Measure FFmpeg/libSRT behavior during receiver-side network blackholes.

This is a calibration tool for OpenStream's OBS receiver timeout policy. It launches
an FFmpeg SRT caller through a tiny UDP relay into an FFmpeg SRT listener, waits for
media to flow, then either blackholes both directions until the receiver exits or
restores traffic after a temporary outage and verifies the same receiver recovers.
"""
from __future__ import annotations

import argparse
import asyncio
import shutil
import signal
import socket
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Optional


@dataclass
class RelayState:
    sender_addr: Optional[tuple[str, int]] = None
    receiver_addr: Optional[tuple[str, int]] = None
    blackhole: bool = False
    forwarded_packets: int = 0


class RelayProtocol(asyncio.DatagramProtocol):
    def __init__(self, state: RelayState, peer_sock: socket.socket, peer_port: int) -> None:
        self.state = state
        self.peer_sock = peer_sock
        self.peer_port = peer_port

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        if self.state.sender_addr is None:
            self.state.sender_addr = addr
        if self.state.blackhole:
            return
        self.peer_sock.sendto(data, ("127.0.0.1", self.peer_port))
        self.state.forwarded_packets += 1


class ReturnProtocol(asyncio.DatagramProtocol):
    def __init__(self, state: RelayState, sender_sock: socket.socket) -> None:
        self.state = state
        self.sender_sock = sender_sock

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        self.state.receiver_addr = addr
        if self.state.blackhole or self.state.sender_addr is None:
            return
        self.sender_sock.sendto(data, self.state.sender_addr)
        self.state.forwarded_packets += 1


def ffmpeg_path() -> str:
    path = shutil.which("ffmpeg")
    if not path:
        raise RuntimeError("ffmpeg not found in PATH")
    probe = subprocess.run(
        [path, "-hide_banner", "-protocols"],
        check=True,
        capture_output=True,
        text=True,
    )
    protocols = {line.strip() for line in probe.stdout.splitlines()}
    if "srt" not in protocols:
        raise RuntimeError("ffmpeg was built without SRT support")
    return path


def terminate(proc: Optional[subprocess.Popen[str]]) -> None:
    if proc is None or proc.poll() is not None:
        return
    try:
        proc.send_signal(signal.SIGTERM)
        proc.wait(timeout=2)
    except Exception:
        proc.kill()
        proc.wait(timeout=2)


async def wait_for_flow(
    state: RelayState,
    receiver: subprocess.Popen[str],
    sender: subprocess.Popen[str],
    target_packets: int,
    deadline_s: float,
) -> None:
    deadline = time.monotonic() + deadline_s
    while state.forwarded_packets < target_packets and time.monotonic() < deadline:
        if receiver.poll() is not None or sender.poll() is not None:
            raise RuntimeError("FFmpeg process exited before media flow was established")
        await asyncio.sleep(0.05)
    if state.forwarded_packets < target_packets:
        raise RuntimeError("SRT media flow was not established before timeout")


async def run_probe(
    timeout_us: int,
    warmup_s: float,
    max_wait_s: float,
    outage_s: Optional[float],
) -> tuple[str, float]:
    ffmpeg = ffmpeg_path()
    loop = asyncio.get_running_loop()
    state = RelayState()

    ingress = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    ingress.bind(("127.0.0.1", 0))
    ingress.setblocking(False)
    ingress_port = ingress.getsockname()[1]

    egress = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    egress.bind(("127.0.0.1", 0))
    egress.setblocking(False)

    receiver_port_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    receiver_port_sock.bind(("127.0.0.1", 0))
    receiver_port = receiver_port_sock.getsockname()[1]
    receiver_port_sock.close()

    ingress_transport, _ = await loop.create_datagram_endpoint(
        lambda: RelayProtocol(state, egress, receiver_port), sock=ingress
    )
    return_transport, _ = await loop.create_datagram_endpoint(
        lambda: ReturnProtocol(state, ingress), sock=egress
    )

    receiver: Optional[subprocess.Popen[str]] = None
    sender: Optional[subprocess.Popen[str]] = None
    try:
        receiver_url = (
            f"srt://127.0.0.1:{receiver_port}?mode=listener"
            f"&timeout={timeout_us}&latency=120000&transtype=live"
        )
        receiver = subprocess.Popen(
            [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "warning",
                "-i",
                receiver_url,
                "-f",
                "null",
                "-",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        await asyncio.sleep(0.25)

        sender_url = (
            f"srt://127.0.0.1:{ingress_port}?mode=caller"
            "&connect_timeout=2000&latency=120000&transtype=live&tlpktdrop=1"
        )
        sender = subprocess.Popen(
            [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "warning",
                "-re",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=30",
                "-an",
                "-c:v",
                "mpeg2video",
                "-f",
                "mpegts",
                sender_url,
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )

        await wait_for_flow(state, receiver, sender, 100, 8.0)
        await asyncio.sleep(warmup_s)
        state.blackhole = True
        blackhole_at = time.monotonic()

        if outage_s is not None:
            await asyncio.sleep(outage_s)
            if receiver.poll() is not None:
                raise RuntimeError(
                    f"receiver exited during {outage_s:.2f}s temporary blackhole"
                )
            packets_before_restore = state.forwarded_packets
            state.blackhole = False
            restore_at = time.monotonic()
            target_packets = packets_before_restore + 100
            await wait_for_flow(state, receiver, sender, target_packets, max_wait_s)
            if receiver.poll() is not None:
                raise RuntimeError("receiver exited instead of recovering after traffic restore")
            return "recovery", time.monotonic() - restore_at

        while receiver.poll() is None and time.monotonic() - blackhole_at < max_wait_s:
            await asyncio.sleep(0.02)
        if receiver.poll() is None:
            raise RuntimeError(
                f"receiver did not exit within {max_wait_s:.2f}s after blackhole"
            )
        return "exit", time.monotonic() - blackhole_at
    finally:
        terminate(sender)
        terminate(receiver)
        ingress_transport.close()
        return_transport.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeout-us", type=int, default=4_500_000)
    parser.add_argument("--warmup-s", type=float, default=0.5)
    parser.add_argument("--max-wait-s", type=float, default=8.0)
    parser.add_argument(
        "--outage-s",
        type=float,
        default=None,
        help="restore traffic after this many seconds and verify the receiver recovers",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if (
        args.timeout_us <= 0
        or args.warmup_s < 0
        or args.max_wait_s <= 0
        or (args.outage_s is not None and args.outage_s <= 0)
    ):
        print("timeout/wait values must be positive", file=sys.stderr)
        return 2
    try:
        mode, elapsed = asyncio.run(
            run_probe(args.timeout_us, args.warmup_s, args.max_wait_s, args.outage_s)
        )
    except Exception as exc:
        print(f"probe failed: {exc}", file=sys.stderr)
        return 1
    print(f"configured_timeout_us={args.timeout_us}")
    if mode == "recovery":
        print(f"temporary_blackhole_s={args.outage_s:.3f}")
        print(f"traffic_restore_to_media_recovery_ms={elapsed * 1000:.1f}")
    else:
        print(f"blackhole_to_receiver_exit_ms={elapsed * 1000:.1f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
