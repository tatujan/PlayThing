#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Wrap a gzip cpio ramdisk (for example AOSP ramdisk.img) in a legacy U-Boot uImage header.

The stock U-Boot "booti" needs the initrd as a uImage (scripts/sbt-ramboot.py --initrd).
The header is the same as in superbird-tool's superbird.initrd.img: OS Linux (5),
arch arm64 (22), type ramdisk (3), compression gzip (1), load and entry address 0.
U-Boot does not unpack the ramdisk. The kernel does.

Usage: python3 scripts/mkuimage-ramdisk.py RAMDISK_IMG OUT_UIMAGE [NAME]
"""

import struct
import sys
import zlib


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    data = open(sys.argv[1], 'rb').read()
    if data[:2] != b'\x1f\x8b':
        sys.exit('The ramdisk is not gzip data.')
    name = (sys.argv[3] if len(sys.argv) > 3 else 'plaything').encode()[:31]
    # Fixed header time (0): the same ramdisk always gives the same uImage, so the boot area
    # (scripts/mk-systemb-tail.py) and its SHA-256 only change when the content changes.
    fields = [0x27051956, 0, 0, len(data), 0, 0, zlib.crc32(data) & 0xffffffff]
    header = bytearray(struct.pack('>7I4B32s', *fields, 5, 22, 3, 1, name))
    struct.pack_into('>I', header, 4, zlib.crc32(bytes(header)) & 0xffffffff)
    with open(sys.argv[2], 'wb') as out:
        out.write(header)
        out.write(data)
    print(f'{sys.argv[2]}: {len(data)} bytes of ramdisk, uImage header OK')


if __name__ == '__main__':
    main()
