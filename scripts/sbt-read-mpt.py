#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read the real Amlogic partition table (MPT) of the Car Thing eMMC.

The MPT is at byte 36 MiB of the eMMC user area. This script reads raw sectors
with "mmc read" into RAM, reads the RAM over USB, and prints the table.
It only reads. The device must be in USB Burn Mode.

MPT layout (Assumed, from Amlogic U-Boot): magic "MPT\\0" (4 bytes), version
(12 bytes), part_num (u32), checksum (u32), then 32 entries of name[16],
size u64, offset u64, mask u32, 4 bytes padding. Sizes and offsets are bytes.

Run it with the superbird-tool venv:
  out/tools/superbird-tool/.venv/bin/python scripts/sbt-read-mpt.py OUT_FILE [MMC_DEV]
"""

import struct
import sys
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent.parent / 'out' / 'tools' / 'superbird-tool'
sys.path.insert(0, str(TOOL_DIR))

from superbird_device import SuperbirdDevice, check_device_mode  # noqa: E402

MPT_SECTOR = 36 * 1024 * 1024 // 512
SECTORS = 8
ADDR = 0x13000000


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out_file = sys.argv[1]
    mmc_dev = sys.argv[2] if len(sys.argv) > 2 else '1'

    if not check_device_mode('usb-burn'):
        sys.exit('The device is not in USB Burn Mode. Run superbird_tool.py --burn_mode first.')

    dev = SuperbirdDevice()
    dev.bulkcmd(f'mmc dev {mmc_dev}')
    dev.bulkcmd(f'mmc read {hex(ADDR)} {hex(MPT_SECTOR)} {hex(SECTORS)}')
    data = dev.read_memory(ADDR, SECTORS * 512)
    Path(out_file).write_bytes(data)
    print(f'read {len(data)} bytes from sector {hex(MPT_SECTOR)} to {out_file}')

    if data[:3] != b'MPT':
        print('no MPT magic. First bytes:', data[:32].hex())
        return
    count, = struct.unpack_from('<I', data, 16)
    print(f'MPT version {data[4:16].split(b"\\0")[0].decode()}, {count} partitions')
    for i in range(min(count, 32)):
        name, size, offset, mask = struct.unpack_from('<16sQQI', data, 24 + i * 40)
        name = name.split(b'\0')[0].decode(errors='replace')
        print(f'{name:12s} offset {offset / 2**20:9.2f} MiB ({hex(offset)})  '
              f'size {size / 2**20:9.2f} MiB ({hex(size)})  mask {mask}')


if __name__ == '__main__':
    main()
