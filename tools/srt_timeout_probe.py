#!/usr/bin/env python3
"""Measure FFmpeg/libSRT behavior during receiver-side network blackholes.

This is a calibration tool for OpenStream's OBS receiver timeout policy. It launches
an FFmpeg SRT listener source through a tiny UDP relay into an FFmpeg SRT caller
receiver, waits for decoded media to flow, then either blackholes both directions
until the receiver exits or restores traffic after a temporary outage and verifies
receiver-side frame progress resumes on the same receiver.
"""
from __future__ import annotations

import argparse
import asyncio
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from typing import Optional


@dataclass
class RelayState:
    caller_addr: Optional[tuple[str, int]] = None
    listener_addr: Optional[tuple[str, int]] = None
    blackhole: bool = False
    forwarded_packets: int = 0


class ForwardingProtocol(asyncio.DatagramProtocol):
    """Single parameterized UDP relay; mode="relay" (caller->listener) or "return"."""

    def __init__(
        self, state: RelayState, sock: socket.socket, peer_port: int = 0, mode: str = "relay"
    ) -> None:
        self.state = state
        self.sock = sock
        self.peer_port = peer_port
        self.mode = mode

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        if self.mode == "return":
            self.state.listener_addr = addr
            if self.state.blackhole or self.state.caller_addr is None:
                return
            self.sock.sendto(data, self.state.caller_addr)
        else:
            if self.state.caller_addr is None:
                self.state.caller_addr = addr
            if self.state.blackhole:
                return
            self.sock.sendto(data, ("127.0.0.1", self.peer_port))
        self.state.forwarded_packets += 1


class ReceiverProgress:
    def __init__(self) -> None:
        self._frame = 0
        self._lock = threading.Lock()

    def update_from_line(self, line: str) -> None:
        rest = line.removeprefix("frame=")
        if rest == line:
            return
        try:
            value = int(rest)
        except ValueError:
            return
        with self._lock:
            if value > self._frame:
                self._frame = value

    def frame(self) -> int:
        with self._lock:
            return self._frame


def ffmpeg_output_has_token(output: str, token: str) -> bool:
    return token in output.split()


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
    if not ffmpeg_output_has_token(probe.stdout, "srt"):
        raise RuntimeError("ffmpeg was built without SRT support")
    for list_arg, token, label in [
        ("-formats", "lavfi", "lavfi test-source input"),
        ("-encoders", "mpeg2video", "mpeg2video encoder"),
        ("-muxers", "mpegts", "mpegts muxer"),
    ]:
        try:
            ok = _ffmpeg_list_contains(path, list_arg, token)
        except subprocess.CalledProcessError as exc:
            raise RuntimeError(f"ffmpeg capability check failed for {label}: {exc}") from exc
        if not ok:
            raise RuntimeError(f"ffmpeg was built without {label} support (missing {token})")
    return path


def _ffmpeg_list_contains(path: str, list_arg: str, token: str) -> bool:
    probe = subprocess.run(
        [path, "-hide_banner", list_arg],
        check=True,
        capture_output=True,
        text=True,
    )
    return ffmpeg_output_has_token(f"{probe.stdout}\n{probe.stderr}", token)


def _candidate_free_port() -> int:
    """Return an OS-assigned free UDP port candidate.

    The socket is closed before return, so callers must handle the
    bind-then-use race by retrying on EADDRINUSE (see run_probe).
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]
    finally:
        sock.close()


def terminate(proc: Optional[subprocess.Popen[str]]) -> None:
    if proc is None or proc.poll() is not None:
        return
    try:
        proc.send_signal(signal.SIGTERM)
        proc.wait(timeout=2)
    except Exception:
        proc.kill()
        proc.wait(timeout=2)


async def wait_until(
    predicate,
    timeout_s: float,
    desc: str,
    receiver: Optional[subprocess.Popen[str]] = None,
    sender: Optional[subprocess.Popen[str]] = None,
    exited_desc: str = "FFmpeg process exited",
    poll_s: float = 0.05,
) -> float:
    started = time.monotonic()
    deadline = started + timeout_s
    while not predicate() and time.monotonic() < deadline:
        if (receiver is not None and receiver.poll() is not None) or (
            sender is not None and sender.poll() is not None
        ):
            raise RuntimeError(exited_desc)
        await asyncio.sleep(poll_s)
    if not predicate():
        raise RuntimeError(desc)
    return time.monotonic() - started


async def wait_for_transport_flow(
    state: RelayState,
    receiver: subprocess.Popen[str],
    sender: subprocess.Popen[str],
    target_packets: int,
    deadline_s: float,
) -> None:
    await wait_until(
        lambda: state.forwarded_packets >= target_packets,
        deadline_s,
        "SRT transport flow was not established before timeout",
        receiver,
        sender,
        "FFmpeg process exited before SRT transport flow was established",
        0.05,
    )


async def wait_for_frame_progress(
    progress: ReceiverProgress,
    receiver: subprocess.Popen[str],
    sender: subprocess.Popen[str],
    frame_after: int,
    deadline_s: float,
) -> float:
    return await wait_until(
        lambda: progress.frame() > frame_after,
        deadline_s,
        "receiver media did not resume before timeout",
        receiver,
        sender,
        "FFmpeg process exited before receiver media progress was observed",
        0.01,
    )


async def run_probe(
    timeout_us: int,
    warmup_s: float,
    max_wait_s: float,
    outage_s: Optional[float],
    sender_startup_s: float = 0.25,
    flow_packets: int = 100,
    flow_timeout_s: float = 8.0,
    frame_timeout_s: float = 8.0,
    sender_bind_retries: int = 5,
) -> tuple[str, float]:
    ffmpeg = ffmpeg_path()
    loop = asyncio.get_running_loop()
    state = RelayState()
    progress = ReceiverProgress()

    ingress = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    ingress.bind(("127.0.0.1", 0))
    ingress.setblocking(False)
    ingress_port = ingress.getsockname()[1]

    egress = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    egress.bind(("127.0.0.1", 0))
    egress.setblocking(False)

    # Avoid port-reserve TOCTOU: the listener port cannot be held open
    # (ffmpeg must bind it), so reserve a candidate and retry on collision.
    listener_port = _candidate_free_port()

    ingress_transport, _ = await loop.create_datagram_endpoint(
        lambda: ForwardingProtocol(state, egress, listener_port, "relay"), sock=ingress
    )
    return_transport, _ = await loop.create_datagram_endpoint(
        lambda: ForwardingProtocol(state, ingress, mode="return"), sock=egress
    )

    receiver: Optional[subprocess.Popen[str]] = None
    sender: Optional[subprocess.Popen[str]] = None
    progress_thread: Optional[threading.Thread] = None
    try:
        sender_args_base = [
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
        ]
        for attempt in range(max(1, sender_bind_retries)):
            sender_url = (
                f"srt://127.0.0.1:{listener_port}?mode=listener"
                "&latency=120000&transtype=live&tlpktdrop=1"
            )
            sender = subprocess.Popen(
                [*sender_args_base, sender_url],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                text=True,
            )
            await asyncio.sleep(sender_startup_s)
            if sender.poll() is None:
                break
            # Sender exited during startup: most likely the reserved UDP port
            # was stolen between reserve and bind (TOCTOU). Retry with a fresh
            # candidate instead of failing on a transient collision.
            terminate(sender)
            sender = None
            if attempt + 1 >= max(1, sender_bind_retries):
                raise RuntimeError(
                    "FFmpeg sender exited during startup; "
                    "listener port may be in use or the ffmpeg build cannot serve SRT"
                )
            listener_port = _candidate_free_port()
            ingress_transport.get_protocol().peer_port = listener_port  # type: ignore[attr-defined]
        assert sender is not None and sender.poll() is None

        receiver_url = (
            f"srt://127.0.0.1:{ingress_port}?mode=caller"
            f"&timeout={timeout_us}&connect_timeout=2000"
            "&latency=120000&transtype=live"
        )
        receiver = subprocess.Popen(
            [
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "warning",
                "-stats_period",
                "0.1",
                "-i",
                receiver_url,
                "-progress",
                "pipe:1",
                "-f",
                "null",
                "-",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        assert receiver.stdout is not None
        stdout = receiver.stdout
        progress_thread = threading.Thread(
            target=lambda: [progress.update_from_line(line.strip()) for line in stdout],
            daemon=True,
        )
        progress_thread.start()

        await wait_for_transport_flow(state, receiver, sender, flow_packets, flow_timeout_s)
        await wait_for_frame_progress(progress, receiver, sender, 0, frame_timeout_s)
        await asyncio.sleep(warmup_s)
        state.blackhole = True
        blackhole_at = time.monotonic()

        if outage_s is not None:
            await asyncio.sleep(outage_s)
            if receiver.poll() is not None:
                raise RuntimeError(
                    f"receiver exited during {outage_s:.2f}s temporary blackhole"
                )
            frame_before_restore = progress.frame()
            state.blackhole = False
            elapsed = await wait_for_frame_progress(
                progress, receiver, sender, frame_before_restore, max_wait_s
            )
            if receiver.poll() is not None:
                raise RuntimeError("receiver exited instead of recovering after traffic restore")
            return "recovery", elapsed

        while receiver.poll() is None and time.monotonic() - blackhole_at < max_wait_s:
            await asyncio.sleep(0.02)
        if receiver.poll() is None:
            raise RuntimeError(
                f"receiver did not exit within {max_wait_s:.2f}s after blackhole"
            )
        return "exit", time.monotonic() - blackhole_at
    finally:
        terminate(receiver)
        terminate(sender)
        ingress_transport.close()
        return_transport.close()
        if progress_thread is not None:
            progress_thread.join(timeout=1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeout-us", type=int, default=4_500_000)
    parser.add_argument("--warmup-s", type=float, default=0.5)
    parser.add_argument("--max-wait-s", type=float, default=8.0)
    parser.add_argument(
        "--outage-s",
        type=float,
        default=None,
        help="restore traffic after this many seconds and verify receiver frames resume",
    )
    parser.add_argument(
        "--sender-startup-s",
        type=float,
        default=0.25,
        help="delay after launching the SRT sender before launching the receiver",
    )
    parser.add_argument(
        "--flow-packets",
        type=int,
        default=100,
        help="relay packets required before transport flow is considered established",
    )
    parser.add_argument(
        "--flow-timeout-s",
        type=float,
        default=8.0,
        help="deadline for transport flow establishment",
    )
    parser.add_argument(
        "--frame-timeout-s",
        type=float,
        default=8.0,
        help="deadline for initial receiver frame progress",
    )
    parser.add_argument(
        "--sender-bind-retries",
        type=int,
        default=5,
        help="sender listener-port bind attempts before giving up (TOCTOU retry)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if (
        args.timeout_us <= 0
        or args.warmup_s < 0
        or args.max_wait_s <= 0
        or (args.outage_s is not None and args.outage_s <= 0)
        or args.sender_startup_s < 0
        or args.flow_packets <= 0
        or args.flow_timeout_s <= 0
        or args.frame_timeout_s <= 0
        or args.sender_bind_retries <= 0
    ):
        print("timeout/wait values must be positive", file=sys.stderr)
        return 2
    try:
        mode, elapsed = asyncio.run(
            run_probe(
                args.timeout_us,
                args.warmup_s,
                args.max_wait_s,
                args.outage_s,
                args.sender_startup_s,
                args.flow_packets,
                args.flow_timeout_s,
                args.frame_timeout_s,
                args.sender_bind_retries,
            )
        )
    except Exception as exc:
        print(f"probe failed: {exc}", file=sys.stderr)
        return 1
    print(f"configured_timeout_us={args.timeout_us}")
    print("receiver_role=caller")
    if mode == "recovery":
        print(f"temporary_blackhole_s={args.outage_s:.3f}")
        print(f"traffic_restore_to_receiver_frame_ms={elapsed * 1000:.1f}")
    else:
        print(f"blackhole_to_receiver_exit_ms={elapsed * 1000:.1f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
