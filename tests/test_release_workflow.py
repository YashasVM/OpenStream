import os
import shutil
import subprocess
import textwrap

import pytest

from _helpers import read_text as read


pytestmark = pytest.mark.skipif(
    os.name == "nt" or shutil.which("bash") is None,
    reason="Release Bash steps run on Ubuntu runners",
)


def release_script(step_name):
    step = read(".github/workflows/release.yml").split(
        f"      - name: {step_name}\n", 1
    )[1]
    return textwrap.dedent(
        step.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0]
    )


@pytest.fixture
def release_repo(tmp_path):
    def git(*args):
        return subprocess.run(
            ["git", *args], cwd=tmp_path, check=True, capture_output=True, text=True
        ).stdout.strip()

    git("init", "-q")
    git("config", "user.name", "Release test")
    git("config", "user.email", "release@example.test")
    git("config", "commit.gpgsign", "false")
    git("config", "core.hooksPath", str(tmp_path / "no-hooks"))
    android = tmp_path / "android"
    android.mkdir()
    properties = android / "gradle.properties"
    for tag, code in [("v1.0.2", 1790441644), ("v1.0.3-beta.1", 1791000000)]:
        properties.write_text(
            f"openstream.versionName={tag[1:]}\nopenstream.versionCode={code}\n"
        )
        git("add", ".")
        git("commit", "-qm", tag)
        git("tag", tag)
    (android / "gradlew").write_text('#!/bin/bash\nprintf "%s\\n" "$@" > build-args\n')
    return tmp_path, git


@pytest.mark.parametrize(
    "tag,version,code,error",
    [
        ("v1.1.0", "1.1.0", 1792574158, None),
        ("v1.1.0-beta.1", "1.1.0-beta.1", 1792574158, None),
        ("v1.1.0-rc.1", "1.1.0-rc.1", 1792574158, None),
        ("v1.1.0-beta.1", "1.1.0", 1792574158, "Set openstream.versionName"),
        ("v1.1.0", "1.1.0", 1791000000, "must exceed every previous release code"),
        ("v1.1.0-beta.1", "1.1.0-beta.1", 1790999999, "must exceed every previous release code"),
        ("v1.1.0", "1.1.0", 2100000001, "must be an integer"),
        ("v1.1.0", "1.1.0", "bad", "must be an integer"),
        *[
            (tag, tag[1:], 1792574158, "Release tag must be")
            for tag in [
                "v01.1.0", "v1.1", "v1.1.0-beta", "v1.1.0-beta.0",
                "v1.1.0-beta.01", "v1.1.0-alpha.1", "v1.1.0-rc.1-extra",
                "v1.1.0+build.1",
            ]
        ],
    ],
)
def test_release_validation(release_repo, tag, version, code, error):
    repo, git = release_repo
    android = repo / "android"
    (android / "gradle.properties").write_text(
        f"openstream.versionName={version}\nopenstream.versionCode={code}\n"
    )
    git("add", ".")
    git("commit", "-qm", "Candidate release")
    git("tag", tag)
    output = repo / "output"
    result = subprocess.run(
        ["bash", "-c", release_script("Build signed release APK with native SRT enabled")],
        cwd=android,
        env={**os.environ, "RELEASE_TAG": tag, "GITHUB_REF_TYPE": "tag",
             "GITHUB_SHA": git("rev-parse", "HEAD"), "GITHUB_OUTPUT": str(output)},
        capture_output=True, text=True,
    )
    if error:
        assert result.returncode != 0
        assert error in result.stdout
        assert not (android / "build-args").exists()
    else:
        assert result.returncode == 0, result.stdout + result.stderr
        assert output.read_text() == f"version_name={version}\nversion_code={code}\n"
        assert (android / "build-args").read_text() == "--no-daemon\n:app:assembleRelease\n"


@pytest.mark.parametrize("tag", ["v1.1.0", "v1.1.0-beta.1", "v1.1.0-rc.1"])
@pytest.mark.parametrize("manual", [False, True])
def test_release_publication_flags(release_repo, tag, manual):
    repo, git = release_repo
    bin_dir = repo / "bin"
    bin_dir.mkdir()
    gh = bin_dir / "gh"
    gh.write_text('#!/bin/bash\nprintf "%s\\n" "$@" > "$RELEASE_ARGS"\n')
    gh.chmod(0o755)
    args_file = repo / "release-args"
    result = subprocess.run(
        ["bash", "-c", release_script("Create release")], cwd=repo,
        env={**os.environ, "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
             "INPUT_TAG": tag if manual else "", "GITHUB_REF_NAME": "main" if manual else tag,
             "RELEASE_ARGS": str(args_file)},
        capture_output=True, text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    args = args_file.read_text().splitlines()
    assert args[:3] == ["release", "create", tag]
    assert args[args.index("--target") + 1] == git("rev-parse", "HEAD")
    assert "--generate-notes" in args
    assert "dist/openstream-android.apk" in args
    assert "dist/openstream-obs-windows-x64.zip" in args
    assert "dist/openstream-obs-plugin-installer-windows-x64.exe" in args
    assert "dist/openstream-obs-linux-x86_64.tar.gz" in args
    if "-" in tag:
        assert args[-2:] == ["--prerelease", "--latest=false"]
        assert "--latest" not in args
    else:
        assert args[-1] == "--latest"
        assert "--prerelease" not in args
