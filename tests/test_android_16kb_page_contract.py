from __future__ import annotations

import importlib.util
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER_PATH = ROOT / "tools" / "check_android_16kb_alignment.py"
SPEC = importlib.util.spec_from_file_location("android_alignment", CHECKER_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


def elf64_with_load_alignment(alignment: int) -> bytes:
    image = bytearray(64 + 56)
    image[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into(
        "<HHIQQQIHHHHHH",
        image,
        16,
        3,
        183,
        1,
        0,
        64,
        0,
        0,
        64,
        56,
        1,
        0,
        0,
        0,
    )
    struct.pack_into("<IIQQQQQQ", image, 64, 1, 5, 0, 0, 0, 1, 1, alignment)
    return bytes(image)


def test_checker_distinguishes_4kb_and_16kb_elf_segments() -> None:
    assert CHECKER.load_segment_alignments(elf64_with_load_alignment(4 * 1024)) == [0x1000]
    assert CHECKER.load_segment_alignments(elf64_with_load_alignment(16 * 1024)) == [0x4000]


def test_android_build_and_ci_enforce_16kb_alignment() -> None:
    cmake = (ROOT / "android/app/src/main/cpp/CMakeLists.txt").read_text()
    android_workflow = (ROOT / ".github/workflows/android.yml").read_text()
    release_workflow = (ROOT / ".github/workflows/release.yml").read_text()

    assert '"-Wl,-z,max-page-size=16384"' in cmake
    assert '"-Wl,-z,common-page-size=16384"' in cmake
    invocation = "python tools/check_android_16kb_alignment.py"
    assert invocation in android_workflow
    assert invocation in release_workflow
