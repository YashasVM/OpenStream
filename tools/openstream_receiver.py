#!/usr/bin/env python3
"""OpenStream feasibility receiver.

This script verifies that the local FFmpeg build can listen for SRT input and
then launches ffmpeg or ffplay against one Android caller stream.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from srt_timeout_probe import ffmpeg_output_has_token, terminate


def require_binary(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise SystemExit(f"Required binary not found on PATH: {name}")
    return path


def default_null_sink() -> str:
    return os.devnull


def is_null_sink(output: str) -> bool:
    return output in (os.devnull, "/dev/null") or output.upper() == "NUL"


def ffmpeg_supports_srt(ffmpeg: str) -> bool:
    result = subprocess.run(
        [ffmpeg, "-hide_banner", "-protocols"],
        check=False,
        capture_output=True,
        text=True,
    )
    return result.returncode == 0 and ffmpeg_output_has_token(result.stdout, "srt")


def build_command(args: argparse.Namespace) -> list[str]:
    srt_url = f"srt://0.0.0.0:{args.port}?mode=listener&latency={args.latency_ms}"
    if args.ffplay:
        return [
            require_binary("ffplay"),
            "-hide_banner",
            "-fflags",
            "nobuffer",
            "-flags",
            "low_delay",
            "-i",
            srt_url,
        ]

    ffmpeg = require_binary("ffmpeg")
    output = args.output or default_null_sink()
    return [
        ffmpeg,
        "-hide_banner",
        "-stats",
        "-fflags",
        "nobuffer",
        "-flags",
        "low_delay",
        "-i",
        srt_url,
        "-map",
        "0",
        "-c",
        "copy",
        "-f",
        "null" if is_null_sink(output) else "mpegts",
        output,
    ]


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Listen for one OpenStream SRT feed.")
    parser.add_argument("--port", type=int, default=9000)
    parser.add_argument("--latency-ms", type=int, default=120)
    parser.add_argument("--ffplay", action="store_true", help="Preview with ffplay instead of ffmpeg null sink.")
    parser.add_argument("--output", help="Optional MPEG-TS output path when not using --ffplay.")
    args = parser.parse_args(argv)

    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    if args.port < 1024:
        parser.error("--port below 1024 requires admin/root privileges; use --port >= 1024 or run elevated")
    if not 20 <= args.latency_ms <= 1000:
        parser.error("--latency-ms must be between 20 and 1000")
    elif not 80 <= args.latency_ms <= 200:
        print(
            f"warning: --latency-ms {args.latency_ms} is outside the OBS/Android spec range "
            "80-200 ms; OBS/Android clamp latency to 80-200 ms",
            file=sys.stderr,
        )

    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    ffmpeg = require_binary("ffmpeg")
    if not ffmpeg_supports_srt(ffmpeg):
        raise SystemExit("The FFmpeg build on PATH does not list SRT protocol support.")
    if args.ffplay:
        ffplay = require_binary("ffplay")
        if not ffmpeg_supports_srt(ffplay):
            raise SystemExit("The ffplay build on PATH does not list SRT protocol support.")

    print("OpenStream receiver listening for Android caller:")
    print(f"  srt://0.0.0.0:{args.port}?mode=listener&latency={args.latency_ms}")
    print("Android target URL:")
    print(f"  srt://<windows-ip>:{args.port}?mode=caller&latency={args.latency_ms}")
    print()

    proc = subprocess.Popen(build_command(args))
    try:
        return proc.wait()
    except KeyboardInterrupt:
        print("\nInterrupted, stopping receiver...", file=sys.stderr)
        try:
            terminate(proc)
        except Exception:
            pass
        return 130


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
