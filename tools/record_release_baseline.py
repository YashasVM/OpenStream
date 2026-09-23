#!/usr/bin/env python3
"""Download one GitHub release and record its asset sizes and SHA-256 hashes."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request


USER_AGENT = "OpenStream-release-baseline/1.0"


def request(url: str) -> urllib.request.Request:
    return urllib.request.Request(
        url,
        headers={"Accept": "application/vnd.github+json", "User-Agent": USER_AGENT},
    )


def download(url: str, destination: pathlib.Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(request(url)) as response, destination.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
            digest.update(chunk)
            size += len(chunk)
    return size, digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="YashasVM/OpenStream")
    parser.add_argument("--tag", default="v1.0.1")
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument("--manifest", type=pathlib.Path)
    args = parser.parse_args()

    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        parser.error("--repo must be OWNER/REPOSITORY")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.tag):
        parser.error("--tag contains unsupported characters")

    release_url = f"https://api.github.com/repos/{args.repo}/releases/tags/{args.tag}"
    try:
        with urllib.request.urlopen(request(release_url)) as response:
            release = json.load(response)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        assets = []
        for asset in release["assets"]:
            path = args.output_dir / asset["name"]
            size, sha256 = download(asset["browser_download_url"], path)
            published_size = asset["size"]
            published_digest = asset.get("digest")
            expected_digest = (
                published_digest.removeprefix("sha256:") if published_digest else None
            )
            record = {
                "name": asset["name"],
                "size_bytes": size,
                "sha256": sha256,
                "github_size_bytes": published_size,
                "github_digest": published_digest,
                "download_url": asset["browser_download_url"],
            }
            assets.append(record)
            status = "MATCH" if size == published_size and (not expected_digest or sha256 == expected_digest) else "MISMATCH"
            print(f"{status} {sha256} {size:>10} {asset['name']}")
            if status == "MISMATCH":
                raise ValueError(f"download differs from release metadata: {asset['name']}")

        manifest = {
            "repository": args.repo,
            "tag": release["tag_name"],
            "release_id": release["id"],
            "published_at": release["published_at"],
            "release_url": release["html_url"],
            "assets": assets,
        }
        encoded = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
        if args.manifest:
            args.manifest.parent.mkdir(parents=True, exist_ok=True)
            args.manifest.write_text(encoded, encoding="utf-8")
        else:
            sys.stdout.write(encoded)
    except (urllib.error.URLError, KeyError, ValueError, OSError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
