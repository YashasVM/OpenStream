#!/usr/bin/env python3
"""Check that a built OBS plugin binary embeds the requested product version."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


MARKER = re.compile(rb"OPENSTREAM_PRODUCT_VERSION=([0-9]+\.[0-9]+\.[0-9]+)")


def embedded_versions(binary: bytes) -> set[str]:
    return {match.decode("ascii") for match in MARKER.findall(binary)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("expected_version")
    args = parser.parse_args()
    try:
        versions = embedded_versions(args.binary.read_bytes())
    except OSError as exc:
        print(f"plugin version check failed: {exc}", file=sys.stderr)
        return 1
    if versions != {args.expected_version}:
        print(
            f"plugin version check failed: expected only {args.expected_version}, found {sorted(versions)}",
            file=sys.stderr,
        )
        return 1
    print(f"plugin version verified: {args.expected_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
