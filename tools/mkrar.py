"""Writes small stored (uncompressed) RAR 4.x archives as test fixtures.

Format per RAR technote (RAR 2.9/3.x): marker block, main header, file
headers with stored data, end-of-archive block. Multi-volume sets use the
new numbering (name.partN.rar) and split flags on the file headers.

Usage: python3 tools/mkrar.py app/src/androidTest/assets/rar
"""
import struct
import sys
import zlib
from pathlib import Path

MARK = b"Rar!\x1a\x07\x00"


def crc16(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFF


def block(head_type: int, flags: int, body: bytes) -> bytes:
    size = 7 + len(body)
    rest = struct.pack("<BHH", head_type, flags, size) + body
    return struct.pack("<H", crc16(rest)) + rest


def main_header(flags: int) -> bytes:
    return block(0x73, flags, struct.pack("<HI", 0, 0))


DOS_TIME = (46 << 25) | (10 << 21) | (30 << 16) | (12 << 11)  # 2026-10-30 12:00


def file_header(name: str, data: bytes, file_crc: int, unp_size: int, flags: int, is_dir=False) -> bytes:
    name_b = name.replace("/", "\\").encode("ascii")
    attr = 0x10 if is_dir else 0x20
    method = 0x30
    flags |= 0x8000  # ADD_SIZE (packed size) present
    if is_dir:
        flags |= 0x00E0
    body = struct.pack(
        "<IIBIIBBHI",
        len(data), unp_size, 2, file_crc, DOS_TIME, 29, method, len(name_b), attr,
    ) + name_b
    return block(0x74, flags, body) + data


def end_block(next_volume: bool) -> bytes:
    return block(0x7B, 0x0001 if next_volume else 0, b"")


def single(path: Path, files):
    out = bytearray(MARK + main_header(0))
    for name, data in files:
        if data is None:
            out += file_header(name, b"", 0, 0, 0, is_dir=True)
        else:
            out += file_header(name, data, zlib.crc32(data), len(data), 0)
    out += end_block(False)
    path.write_bytes(out)


def multi(base: Path, small_name: str, small: bytes, big_name: str, big: bytes, parts: int):
    chunk = -(-len(big) // parts)
    pieces = [big[i * chunk:(i + 1) * chunk] for i in range(parts)]
    for i, piece in enumerate(pieces):
        flags = 0x0001 | 0x0010 | (0x0100 if i == 0 else 0)  # volume, new numbering, first volume
        out = bytearray(MARK + main_header(flags))
        if i == 0:
            out += file_header(small_name, small, zlib.crc32(small), len(small), 0)
        split = (0x01 if i > 0 else 0) | (0x02 if i < parts - 1 else 0)
        # Non-final parts carry the CRC of their own data, the final part that of the whole file
        crc = zlib.crc32(piece) if i < parts - 1 else zlib.crc32(big)
        out += file_header(big_name, piece, crc, len(big), split)
        out += end_block(i < parts - 1)
        Path(f"{base}.part{i + 1}.rar").write_bytes(out)


if __name__ == "__main__":
    dest = Path(sys.argv[1])
    dest.mkdir(parents=True, exist_ok=True)
    text = b"JDAndroid RAR test\n"
    data = bytes((i * 31 + 7) % 251 for i in range(96 * 1024))
    single(dest / "single.rar", [("docs", None), ("docs/readme.txt", text), ("data.bin", data[:5000])])
    multi(dest / "set", "info.txt", text, "big.bin", data, 3)
