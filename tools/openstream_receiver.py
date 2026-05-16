#!/usr/bin/env python3
"""OpenStream feasibility receiver.

This script verifies that the local FFmpeg build can listen for SRT input and
then launches ffmpeg or ffplay against one Android caller stream.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from dataclasses import dataclass


@dataclass(frozen=True)
class ReceiverConfig:
    port: int
    latency_ms: int
    ffplay: bool
    output: str | None

    @property
    def srt_url(self) -> str:
        return f"srt://0.0.0.0:{self.port}?mode=listener&latency={self.latency_ms}"


def require_binary(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise SystemExit(f"Required binary not found on PATH: {name}")
    return path


def ffmpeg_supports_srt(ffmpeg: str) -> bool:
    result = subprocess.run(
        [ffmpeg, "-hide_banner", "-protocols"],
        check=False,
        capture_output=True,
        text=True,
    )
    return result.returncode == 0 and "srt" in result.stdout.split()


def build_command(config: ReceiverConfig) -> list[str]:
    if config.ffplay:
        player = require_binary("ffplay")
        return [
            player,
            "-hide_banner",
            "-fflags",
            "nobuffer",
            "-flags",
            "low_delay",
            "-i",
            config.srt_url,
        ]

    ffmpeg = require_binary("ffmpeg")
    output = config.output or "NUL"
    return [
        ffmpeg,
        "-hide_banner",
        "-stats",
        "-fflags",
        "nobuffer",
        "-flags",
        "low_delay",
        "-i",
        config.srt_url,
        "-map",
        "0",
        "-c",
        "copy",
        "-f",
        "null" if output.upper() == "NUL" else "mpegts",
        output,
    ]


def parse_args(argv: list[str]) -> ReceiverConfig:
    parser = argparse.ArgumentParser(description="Listen for one OpenStream SRT feed.")
    parser.add_argument("--port", type=int, default=9000)
    parser.add_argument("--latency-ms", type=int, default=120)
    parser.add_argument("--ffplay", action="store_true", help="Preview with ffplay instead of ffmpeg null sink.")
    parser.add_argument("--output", help="Optional MPEG-TS output path when not using --ffplay.")
    args = parser.parse_args(argv)

    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    if not 20 <= args.latency_ms <= 1000:
        parser.error("--latency-ms must be between 20 and 1000")

    return ReceiverConfig(
        port=args.port,
        latency_ms=args.latency_ms,
        ffplay=args.ffplay,
        output=args.output,
    )


def main(argv: list[str]) -> int:
    config = parse_args(argv)
    ffmpeg = require_binary("ffmpeg")
    if not ffmpeg_supports_srt(ffmpeg):
        raise SystemExit("The FFmpeg build on PATH does not list SRT protocol support.")

    print("OpenStream receiver listening for Android caller:")
    print(f"  {config.srt_url}")
    print("Android target URL:")
    print(f"  srt://<windows-ip>:{config.port}?mode=caller&latency={config.latency_ms}")
    print()

    command = build_command(config)
    return subprocess.call(command)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
