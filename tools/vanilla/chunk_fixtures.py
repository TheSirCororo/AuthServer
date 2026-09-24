#!/usr/bin/env python3
"""Copy one vanilla chunk packet per release from probe recordings into world module test resources.

Each fixture directory holds chunk.bin.gz (Chunk Data body) and, for 1.14 - 1.17, light.bin.gz (Update Light body)
of the same chunk. The recorded world is a default superflat: bedrock, two dirt layers and grass on top.
1.8 servers send chunks in bulk; the first chunk of a bulk packet is rewritten as a single Chunk Data body.
"""
import gzip
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fetch import CACHE  # noqa: E402
from versions import RELEASES  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
TABLE = ROOT / "protocol/src/main/resources/ru/cororo/authserver/protocol/packet-ids.txt"
OUTPUT = ROOT / "world/src/test/resources/vanilla-chunks"
MAP_CHUNK_BULK_1_8 = 0x26


def packet_id(key: str, protocol: int):
    for line in TABLE.read_text().splitlines():
        if line.startswith(f"play clientbound {key} "):
            current = None
            for point in line.split()[3:]:
                version, value = point.split("=")
                if int(version) <= protocol:
                    current = None if value == "-" else int(value, 16)
            return current
    return None


def read_varint(data: bytes, offset: int):
    result = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return (result - (1 << 32) if result >= 1 << 31 else result), offset


def first_bulk_chunk(body: bytes):
    """1.8 Map Chunk Bulk: bool sky light, varint count, (int x, int z, ushort mask)*, chunk data*."""
    sky_light = body[0] != 0
    count, offset = read_varint(body, 1)
    x, z, mask = struct.unpack(">iiH", body[offset:offset + 10])
    offset += 10 * count
    sections = bin(mask).count("1")
    size = sections * (8192 + 2048 + (2048 if sky_light else 0)) + 256
    data = body[offset:offset + size]
    varint = bytearray()
    value = size
    while True:
        byte = value & 0x7F
        value >>= 7
        varint.append(byte | (0x80 if value else 0))
        if not value:
            break
    return struct.pack(">ii?H", x, z, True, mask) + bytes(varint) + data, (x, z)


def main():
    for protocol, release, _ in RELEASES:
        recording = CACHE / release / "recording"
        chunk_id, light_id = packet_id("level_chunk", protocol), packet_id("light_update", protocol)
        chunk = light = position = None
        frames = [(name, (recording / name).read_bytes()) for name in (recording / "index.txt").read_text().split()]
        for name, body in frames:
            _, state, packet = name.removesuffix(".bin").split("_")
            if state != "play" or chunk is not None:
                continue
            if int(packet, 16) == chunk_id:
                chunk, position = body, struct.unpack(">ii", body[:8])
            elif protocol == 47 and int(packet, 16) == MAP_CHUNK_BULK_1_8:
                chunk, position = first_bulk_chunk(body)
        for name, body in frames:
            _, state, packet = name.removesuffix(".bin").split("_")
            if light_id is not None and state == "play" and int(packet, 16) == light_id and light is None:
                x, offset = read_varint(body, 0)
                z, _ = read_varint(body, offset)
                if (x, z) == position:
                    light = body
        if chunk is None:
            print(f"{release}: no chunk recorded")
            continue
        directory = OUTPUT / str(protocol)
        directory.mkdir(parents=True, exist_ok=True)
        with gzip.GzipFile(directory / "chunk.bin.gz", "wb", mtime=0) as output:
            output.write(chunk)
        if light is not None:
            with gzip.GzipFile(directory / "light.bin.gz", "wb", mtime=0) as output:
                output.write(light)
        print(f"{release}: chunk {position} {len(chunk)} bytes" + (f", light {len(light)} bytes" if light else ""))


if __name__ == "__main__":
    main()
