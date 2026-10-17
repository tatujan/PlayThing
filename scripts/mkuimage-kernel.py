#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Wrap a gzip-compressed arm64 kernel (Image.gz) in a legacy U-Boot uImage header.

The stock U-Boot has no "unzip" command, but "bootm" unpacks a gzip uImage kernel to
its load address. The uncompressed Image (about 16 MB) plus the ramdisk does not fit
into the 16 MiB boot_b region, and Image.gz (about 6 MB) does.

Header: OS Linux (5), arch arm64 (22), type kernel (2), compression gzip (1),
load and entry address 0x1080000 (the address that our RAM boots use).

Usage: python3 scripts/mkuimage-kernel.py IMAGE_GZ OUT_UIMAGE [NAME]
"""

import struct
import sys
import time
import zlib

LOAD = 0x1080000


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    data = open(sys.argv[1], 'rb').read()
    if data[:2] != b'\x1f\x8b':
        sys.exit('The kernel is not gzip data.')
    name = (sys.argv[3] if len(sys.argv) > 3 else 'plaything-kernel').encode()[:31]
    fields = [0x27051956, 0, int(time.time()), len(data), LOAD, LOAD, zlib.crc32(data) & 0xffffffff]
    header = bytearray(struct.pack('>7I4B32s', *fields, 5, 22, 2, 1, name))
    struct.pack_into('>I', header, 4, zlib.crc32(bytes(header)) & 0xffffffff)
    with open(sys.argv[2], 'wb') as out:
        out.write(header)
        out.write(data)
    print(f'{sys.argv[2]}: {len(data)} bytes of gzip kernel, uImage header OK')


if __name__ == '__main__':
    main()
