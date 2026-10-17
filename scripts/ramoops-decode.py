#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Decode a raw ramoops (pstore RAM) dump into text records.

Each persistent RAM zone starts with a header: the signature "DBGC", then
"start" and "size" (two little-endian u32 values). The data after the header
is a ring buffer. This script makes the ring buffer linear, decompresses
compressed dmesg records, and prints each zone that has data.

Usage: python3 scripts/ramoops-decode.py DUMP_FILE [OUT_DIR]
       With OUT_DIR, it also writes one text file for each zone.
"""

import re
import struct
import sys
import zlib
from pathlib import Path

HEADER = 12
# A dmesg record starts with "====<seconds>.<microseconds>-<C|D>\n".
# "C" means that the rest of the record is zlib-compressed.
RECORD_HEADER = re.compile(rb'^====\d+\.\d+-([CD])\n')


def decompress(text):
    match = RECORD_HEADER.match(text)
    if not match or match.group(1) != b'C':
        return text
    body = text[match.end():]
    for wbits in (15, 12, -15, -12):
        try:
            return text[:match.end()] + zlib.decompressobj(wbits).decompress(body)
        except zlib.error:
            continue
    return text


def zones(data):
    starts = [m.start() for m in re.finditer(b'DBGC', data)]
    for i, offset in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else len(data)
        start, size = struct.unpack_from('<II', data, offset + 4)
        capacity = end - offset - HEADER
        if size == 0 or size > capacity or start > capacity:
            continue
        buf = data[offset + HEADER:end]
        text = buf[start:size] + buf[:start] if size == capacity else buf[:size]
        yield offset, decompress(text)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    data = Path(sys.argv[1]).read_bytes()
    out_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
    for offset, text in zones(data):
        decoded = text.decode('utf-8', errors='replace')
        print(f'===== zone at {hex(offset)}: {len(text)} bytes')
        print(decoded)
        if out_dir:
            (out_dir / f'zone-{offset:06x}.txt').write_text(decoded)


if __name__ == '__main__':
    main()
