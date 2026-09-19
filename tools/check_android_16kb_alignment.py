#!/usr/bin/env python3
"""Verify that an APK's 64-bit native libraries support 16 KB pages."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path


PAGE_SIZE = 16 * 1024
SUPPORTED_64_BIT_ABIS = {"arm64-v8a", "x86_64"}
PT_LOAD = 1


def load_segment_alignments(library: bytes) -> list[int]:
    if library[:4] != b"\x7fELF":
        raise ValueError("not an ELF library")

    elf_class = library[4]
    byte_order = library[5]
    endian = {1: "<", 2: ">"}.get(byte_order)
    if endian is None:
        raise ValueError(f"unsupported ELF byte order {byte_order}")

    if elf_class == 1:
        header_format = f"{endian}HHIIIIIHHHHHH"
        program_header_format = f"{endian}IIIIIIII"
    elif elf_class == 2:
        header_format = f"{endian}HHIQQQIHHHHHH"
        program_header_format = f"{endian}IIQQQQQQ"
    else:
        raise ValueError(f"unsupported ELF class {elf_class}")

    header = struct.unpack_from(header_format, library, 16)
    program_header_offset = header[4]
    program_header_size = header[8]
    program_header_count = header[9]
    expected_header_size = struct.calcsize(program_header_format)
    if program_header_size < expected_header_size:
        raise ValueError("truncated ELF program header")

    alignments: list[int] = []
    for index in range(program_header_count):
        offset = program_header_offset + index * program_header_size
        program_header = struct.unpack_from(program_header_format, library, offset)
        if program_header[0] == PT_LOAD:
            alignments.append(program_header[7])
    if not alignments:
        raise ValueError("ELF library has no loadable segments")
    return alignments


def zip_data_offset(apk, entry: zipfile.ZipInfo) -> int:
    apk.seek(entry.header_offset)
    local_header = apk.read(30)
    if len(local_header) != 30:
        raise ValueError("truncated ZIP local header")
    fields = struct.unpack("<IHHHHHIIIHH", local_header)
    if fields[0] != 0x04034B50:
        raise ValueError("invalid ZIP local header")
    return entry.header_offset + 30 + fields[9] + fields[10]


def check_apk(apk_path: Path) -> list[str]:
    failures: list[str] = []
    checked = 0
    with apk_path.open("rb") as apk_file, zipfile.ZipFile(apk_file) as archive:
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or parts[1] not in SUPPORTED_64_BIT_ABIS:
                continue
            if not entry.filename.endswith(".so"):
                continue

            checked += 1
            try:
                alignments = load_segment_alignments(archive.read(entry))
            except (IndexError, struct.error, ValueError) as error:
                failures.append(f"{entry.filename}: cannot inspect ELF: {error}")
                continue

            bad_alignments = [alignment for alignment in alignments if alignment < PAGE_SIZE]
            if bad_alignments:
                formatted = ", ".join(f"0x{alignment:x}" for alignment in bad_alignments)
                failures.append(f"{entry.filename}: load segment alignment is {formatted}, need 0x4000")

            if entry.compress_type == zipfile.ZIP_STORED:
                data_offset = zip_data_offset(apk_file, entry)
                if data_offset % PAGE_SIZE != 0:
                    failures.append(
                        f"{entry.filename}: APK data offset 0x{data_offset:x} is not 16 KB aligned"
                    )

    if checked == 0:
        failures.append("APK contains no arm64-v8a or x86_64 native libraries")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()

    failures = check_apk(args.apk)
    if failures:
        for failure in failures:
            print(f"UNALIGNED: {failure}")
        return 1

    print(f"ALIGNED: {args.apk} supports 16 KB native-library pages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
