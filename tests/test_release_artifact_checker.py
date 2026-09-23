import argparse
import json
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path

import pytest

from tools import check_plugin_binary, check_release_artifacts as checker


VERSION = "1.0.1"
TAG = "v1.0.1"


def make_artifacts(root: Path, *, windows_version: str = VERSION, linux_version: str = VERSION) -> tuple[Path, Path]:
    artifacts = root / "artifacts"
    artifacts.mkdir()
    (artifacts / "openstream-android.apk").write_bytes(b"fixture-apk")
    with zipfile.ZipFile(artifacts / "openstream-obs-windows-x64.zip", "w") as package:
        package.writestr("openstream-obs.dll", f"OPENSTREAM_PRODUCT_VERSION={windows_version}".encode())
    with tarfile.open(artifacts / "shin-obs-linux-x86_64.tar.gz", "w:gz") as package:
        binary = root / "shin-obs.so"
        binary.write_bytes(f"OPENSTREAM_PRODUCT_VERSION={linux_version}".encode())
        package.add(binary, arcname="shin-obs-linux-x86_64/shin-obs.so")
    (artifacts / "openstream-obs-plugin-installer-windows-x64.exe").write_bytes(b"fixture-installer")
    installer_metadata = artifacts / "windows-installer-metadata.json"
    installer_metadata.write_text(json.dumps({"productVersion": VERSION}), encoding="utf-8")
    return artifacts, installer_metadata


def fake_android_tools(
    monkeypatch: pytest.MonkeyPatch,
    *,
    code: int = 101,
    candidate_cert: str = "aa" * 32,
    previous_cert: str = "aa" * 32,
) -> tuple[Path, Path]:
    aapt = Path("fake-aapt")
    signer = Path("fake-apksigner")

    def fake_run(command: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
        artifact_name = Path(command[-1]).name
        if command[0] == str(aapt):
            assert command[1:3] == ["dump", "badging"]
            version_code = str(code) if artifact_name == "openstream-android.apk" else "100"
            version_name = VERSION if artifact_name == "openstream-android.apk" else "1.0.0"
            output = (
                f"package: name='dev.openstream.app' versionCode='{version_code}' "
                f"versionName='{version_name}'\n"
            )
        elif command[0] == str(signer):
            assert command[1:3] == ["verify", "--print-certs"]
            certificate = candidate_cert if artifact_name == "openstream-android.apk" else previous_cert
            output = f"Signer #1 certificate SHA-256 digest: {certificate}\n"
        else:
            raise AssertionError(f"Unexpected Android tool: {command[0]}")
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

    monkeypatch.setattr(checker.subprocess, "run", fake_run)
    return aapt, signer


def args_for(artifacts: Path, installer_metadata: Path, tools: tuple[Path, Path], **overrides: object) -> argparse.Namespace:
    values = {
        "artifacts": artifacts,
        "tag": TAG,
        "expected_version": VERSION,
        "expected_version_code": 101,
        "installer_metadata": installer_metadata,
        "aapt": str(tools[0]),
        "apksigner": str(tools[1]),
        "previous_apk": None,
        "require_previous": False,
        "commit": "abc123",
        "write_manifest": None,
        "manifest": None,
    }
    values.update(overrides)
    return argparse.Namespace(**values)


def test_accepts_matched_candidate_and_writes_manifest(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    artifacts, installer = make_artifacts(tmp_path)
    tools = fake_android_tools(monkeypatch)
    output = artifacts / "release-manifest.json"
    manifest = checker.validate(args_for(artifacts, installer, tools, write_manifest=output))
    assert manifest["tag"] == TAG
    assert manifest["android"]["versionCode"] == 101
    assert set(manifest["artifacts"]) == set(checker.ARTIFACTS)
    assert json.loads(output.read_text(encoding="utf-8")) == manifest


def test_rejects_tag_that_does_not_match_product_version(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    artifacts, installer = make_artifacts(tmp_path)
    tools = fake_android_tools(monkeypatch)
    with pytest.raises(ValueError, match="does not match product version"):
        checker.validate(args_for(artifacts, installer, tools, tag="v1.0.2"))


def test_rejects_wrong_apk_version_code(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    artifacts, installer = make_artifacts(tmp_path)
    tools = fake_android_tools(monkeypatch, code=99)
    with pytest.raises(ValueError, match="versionCode is 99"):
        checker.validate(args_for(artifacts, installer, tools))


def test_rejects_wrong_apk_certificate(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    artifacts, installer = make_artifacts(tmp_path)
    tools = fake_android_tools(monkeypatch, candidate_cert="bb" * 32)
    previous = tmp_path / "previous.apk"
    previous.write_bytes(b"old")
    with pytest.raises(ValueError, match="signing certificate differs"):
        checker.validate(args_for(artifacts, installer, tools, previous_apk=previous))


@pytest.mark.parametrize("platform", ["windows", "linux"])
def test_rejects_wrong_plugin_version(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, platform: str) -> None:
    artifacts, installer = make_artifacts(
        tmp_path,
        windows_version="1.0.0" if platform == "windows" else VERSION,
        linux_version="1.0.0" if platform == "linux" else VERSION,
    )
    tools = fake_android_tools(monkeypatch)
    with pytest.raises(ValueError, match=f"{platform.title()} plugin version"):
        checker.validate(args_for(artifacts, installer, tools))


def test_rejects_wrong_installer_product_version(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    artifacts, installer = make_artifacts(tmp_path)
    tools = fake_android_tools(monkeypatch)
    installer.write_text('{"productVersion":"1.0.0"}', encoding="utf-8")
    with pytest.raises(ValueError, match="installer version 1.0.0"):
        checker.validate(args_for(artifacts, installer, tools))


def test_rejects_missing_artifact(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    artifacts, installer = make_artifacts(tmp_path)
    tools = fake_android_tools(monkeypatch)
    (artifacts / "shin-obs-linux-x86_64.tar.gz").unlink()
    with pytest.raises(ValueError, match="Missing release artifacts"):
        checker.validate(args_for(artifacts, installer, tools))


def test_rejects_manifest_when_staged_bytes_change(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    artifacts, installer = make_artifacts(tmp_path)
    tools = fake_android_tools(monkeypatch)
    manifest_path = artifacts / "release-manifest.json"
    checker.validate(args_for(artifacts, installer, tools, write_manifest=manifest_path))
    with (artifacts / "openstream-android.apk").open("ab") as apk:
        apk.write(b"changed")
    with pytest.raises(ValueError, match="manifest does not match"):
        checker.validate(args_for(artifacts, installer, tools, manifest=manifest_path))


def test_rejects_stale_linux_package_only_binary(tmp_path: Path) -> None:
    binary = tmp_path / "shin-obs.so"
    binary.write_bytes(b"OPENSTREAM_PRODUCT_VERSION=1.0.0")
    result = subprocess.run(
        [sys.executable, "tools/check_plugin_binary.py", str(binary), VERSION],
        capture_output=True,
        text=True,
    )
    assert result.returncode != 0
    assert "expected only 1.0.1" in result.stderr


def test_plugin_binary_checker_accepts_current_version() -> None:
    assert check_plugin_binary.embedded_versions(b"OPENSTREAM_PRODUCT_VERSION=1.0.1") == {VERSION}
