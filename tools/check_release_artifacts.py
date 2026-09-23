#!/usr/bin/env python3
"""Validate and manifest the exact OpenStream release artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path
try:
    from .check_plugin_binary import embedded_versions
except ImportError:  # Direct script execution in CI.
    from check_plugin_binary import embedded_versions


ARTIFACTS = (
    "openstream-android.apk",
    "openstream-obs-windows-x64.zip",
    "openstream-obs-plugin-installer-windows-x64.exe",
    "shin-obs-linux-x86_64.tar.gz",
)
PROTOCOL = "SHIN/1"
WINDOWS_ABI = "OBS 32.2.1 x64; FFmpeg avformat-62/avcodec-62/avutil-60/swscale-9"
LINUX_ABI = "system libobs/FFmpeg ABI from Ubuntu 24.04 build environment"


def fail(message: str) -> "None":
    raise ValueError(message)


def run_text(command: list[str]) -> str:
    try:
        return subprocess.run(command, check=True, text=True, capture_output=True).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        fail(f"Could not inspect artifact with {' '.join(command)}: {exc}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as artifact:
        for chunk in iter(lambda: artifact.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def plugin_versions(archive: Path, linux: bool) -> set[str]:
    versions: set[str] = set()
    try:
        if linux:
            with tarfile.open(archive, "r:gz") as package:
                candidates = [item for item in package.getmembers() if item.name.endswith("shin-obs.so")]
                if len(candidates) != 1:
                    fail("Linux package must contain exactly one shin-obs.so")
                stream = package.extractfile(candidates[0])
                if stream is None:
                    fail("Linux plugin binary could not be read from package")
                content = stream.read()
        else:
            with zipfile.ZipFile(archive) as package:
                candidates = [name for name in package.namelist() if name.endswith("openstream-obs.dll")]
                if len(candidates) != 1:
                    fail("Windows package must contain exactly one openstream-obs.dll")
                content = package.read(candidates[0])
    except (OSError, tarfile.TarError, zipfile.BadZipFile) as exc:
        fail(f"Could not inspect plugin package {archive.name}: {exc}")
    versions.update(embedded_versions(content))
    if len(versions) != 1:
        fail(f"{archive.name} must embed exactly one OPENSTREAM_PRODUCT_VERSION marker; found {sorted(versions)}")
    return versions


def apk_identity(apk: Path, aapt: str, apksigner: str) -> tuple[str, str, str, str]:
    badging = run_text([aapt, "dump", "badging", str(apk)])
    package_line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    match = re.fullmatch(
        r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'.*",
        package_line,
    )
    if not match:
        fail(f"Could not read APK package/name/code from {apk.name}")
    certificate_output = run_text([apksigner, "verify", "--print-certs", str(apk)])
    certificates = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F:]+)", certificate_output)
    normalized = {value.replace(":", "").lower() for value in certificates}
    if len(normalized) != 1:
        fail(f"{apk.name} must have exactly one inspectable signing certificate")
    return match.group(1), match.group(2), match.group(3), normalized.pop()


def read_installer_metadata(path: Path) -> str:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        version = data["productVersion"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as exc:
        fail(f"Invalid Windows installer metadata {path}: {exc}")
    if not isinstance(version, str):
        fail("Windows installer metadata productVersion must be a string")
    return version


def verify_artifacts(directory: Path) -> dict[str, str]:
    missing = [name for name in ARTIFACTS if not (directory / name).is_file()]
    if missing:
        fail(f"Missing release artifacts: {', '.join(missing)}")
    return {name: sha256(directory / name) for name in ARTIFACTS}


def validate(args: argparse.Namespace) -> dict[str, object]:
    if not re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", args.tag):
        fail(f"Invalid release tag: {args.tag}")
    version = args.tag[1:]
    if version != args.expected_version:
        fail(f"Tag version {version} does not match product version {args.expected_version}")
    hashes = verify_artifacts(args.artifacts)

    apk = args.artifacts / "openstream-android.apk"
    package, code, apk_version, certificate = apk_identity(apk, args.aapt, args.apksigner)
    if package != "dev.openstream.app":
        fail(f"APK package is {package}, expected dev.openstream.app")
    if apk_version != version:
        fail(f"APK versionName is {apk_version}, expected {version}")
    if code != str(args.expected_version_code):
        fail(f"APK versionCode is {code}, expected {args.expected_version_code}")
    if args.previous_apk:
        previous_package, previous_code, _, previous_certificate = apk_identity(args.previous_apk, args.aapt, args.apksigner)
        if previous_package != package:
            fail(f"Previous published APK package is {previous_package}, expected {package}")
        if int(code) <= int(previous_code):
            fail(f"APK versionCode {code} must exceed previous published APK code {previous_code}")
        if certificate != previous_certificate:
            fail("APK signing certificate differs from the previous published APK")
    elif args.require_previous:
        fail("Previous published APK could not be fetched; refusing to skip code/signature continuity")

    windows_versions = plugin_versions(args.artifacts / "openstream-obs-windows-x64.zip", linux=False)
    linux_versions = plugin_versions(args.artifacts / "shin-obs-linux-x86_64.tar.gz", linux=True)
    for name, versions in (("Windows", windows_versions), ("Linux", linux_versions)):
        if versions != {version}:
            fail(f"{name} plugin version {sorted(versions)} does not match {version}")
    installer_version = read_installer_metadata(args.installer_metadata)
    if installer_version != version:
        fail(f"Windows installer version {installer_version} does not match {version}")

    manifest: dict[str, object] = {
        "schemaVersion": 1,
        "tag": args.tag,
        "productVersion": version,
        "android": {"applicationId": package, "versionName": apk_version, "versionCode": int(code), "signingCertificateSha256": certificate},
        "protocol": PROTOCOL,
        "obsAbi": {"windows": WINDOWS_ABI, "linux": LINUX_ABI},
        "artifacts": {name: {"sha256": digest, "size": (args.artifacts / name).stat().st_size} for name, digest in hashes.items()},
    }
    if args.commit:
        manifest["commit"] = args.commit
    if args.manifest:
        existing = json.loads(args.manifest.read_text(encoding="utf-8"))
        if existing != manifest:
            fail("Staged release manifest does not match current artifact hashes or metadata")
    if args.write_manifest:
        args.write_manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--expected-version", required=True)
    parser.add_argument("--expected-version-code", type=int, required=True)
    parser.add_argument("--installer-metadata", type=Path, required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--require-previous", action="store_true")
    parser.add_argument("--commit")
    parser.add_argument("--write-manifest", type=Path)
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    try:
        manifest = validate(args)
    except (ValueError, OSError, json.JSONDecodeError) as exc:
        print(f"release artifact check failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(manifest, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
