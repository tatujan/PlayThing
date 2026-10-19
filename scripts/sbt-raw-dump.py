#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Dump a raw byte range of the Car Thing eMMC to a file.

superbird-tool dumps by partition name, and its burn-mode U-Boot uses a
partition table that does not match the real layout (docs/boot.md). This
script uses raw sector offsets instead. For each block, it runs "mmc read"
into a RAM buffer, then "upload mem" (the fast AMLC upload protocol of
superbird-tool) to read the buffer over USB. With --slow, it reads the
buffer with readSimpleMemory instead (about 0.7 MB/s).

Errors: a block that fails is tried again up to 3 times. Then the script reads
it in 1 MiB parts. A part that still fails becomes zeros, and the script
lists it in OUT_FILE.holes. U-Boot refuses blocks larger than about 32 MiB.
It only reads. The device must be in USB Burn Mode.

Run it with the superbird-tool venv:
  out/tools/superbird-tool/.venv/bin/python scripts/sbt-raw-dump.py \
      OUT_FILE START LENGTH [--block-mib N] [--slow] [--hwpart N]
START and LENGTH are bytes (decimal or 0x hex), multiples of 512.
--hwpart selects the eMMC hardware partition: 0 user area (default),
1 boot0, 2 boot1. The script always selects the user area again at the end.
"""

import argparse
import os
import sys
import tempfile
import time
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent.parent / 'out' / 'tools' / 'superbird-tool'
sys.path.insert(0, str(TOOL_DIR))

from superbird_device import SuperbirdDevice, check_device_mode  # noqa: E402

BUFFER = 0x02000000  # free RAM below ramoops (0x07400000) and the U-Boot work area (0x13000000)
SECTOR = 512
MIB = 2**20
RETRIES = 3


def command(dev, cmd):
    """Run a U-Boot command. Return True on success. Do not stop the program on failure."""
    try:
        return 'success' in dev.decode(dev.device.bulkCmd(cmd))
    except Exception:
        return False


def read_once(dev, hwpart, offset, size, slow, tmp_path):
    if not command(dev, f'mmc dev 1 {hwpart}'):
        return None
    if not command(dev, f'mmc read {hex(BUFFER)} {hex(offset // SECTOR)} {hex(size // SECTOR)}'):
        return None
    if slow:
        return dev.read_memory(BUFFER, size)
    if not command(dev, f'upload mem {hex(BUFFER)} normal {hex(size)}'):
        return None
    try:
        dev._fast_read_to_file('raw', size, BUFFER, tmp_path)
    except Exception:
        dev._fast_reopen()
        return None
    data = Path(tmp_path).read_bytes()
    return data if len(data) == size else None


def read_retry(dev, hwpart, offset, size, slow, tmp_path):
    for _ in range(RETRIES):
        data = read_once(dev, hwpart, offset, size, slow, tmp_path)
        if data is not None:
            return data
        time.sleep(1)
    return None


def main():
    parser = argparse.ArgumentParser(description='Raw eMMC dump through burn-mode U-Boot')
    parser.add_argument('out_file')
    parser.add_argument('start', type=lambda x: int(x, 0))
    parser.add_argument('length', type=lambda x: int(x, 0))
    parser.add_argument('--block-mib', type=int, default=16)
    parser.add_argument('--slow', action='store_true')
    parser.add_argument('--hwpart', type=int, choices=(0, 1, 2), default=0)
    args = parser.parse_args()
    if args.start % SECTOR or args.length % SECTOR:
        sys.exit('START and LENGTH must be multiples of 512.')

    if not check_device_mode('usb-burn'):
        sys.exit('The device is not in USB Burn Mode. Run superbird_tool.py --burn_mode first.')

    dev = SuperbirdDevice()
    block = args.block_mib * MIB
    holes = []
    done = 0
    start_time = time.time()
    fd, tmp_path = tempfile.mkstemp(suffix='.blk')
    os.close(fd)
    try:
        with open(args.out_file, 'wb') as out:
            while done < args.length:
                offset = args.start + done
                size = min(block, args.length - done)
                data = read_retry(dev, args.hwpart, offset, size, args.slow, tmp_path)
                if data is None:
                    print(f'{hex(offset)}: block failed, reading 1 MiB parts', flush=True)
                    parts = []
                    for part in range(0, size, MIB):
                        part_size = min(MIB, size - part)
                        piece = read_retry(dev, args.hwpart, offset + part, part_size, args.slow, tmp_path)
                        if piece is None:
                            holes.append((offset + part, part_size))
                            print(f'{hex(offset + part)}: hole of {part_size} bytes (zeros)', flush=True)
                            piece = bytes(part_size)
                        parts.append(piece)
                    data = b''.join(parts)
                out.write(data)
                done += size
                rate = done / max(time.time() - start_time, 1e-3) / MIB
                print(f'{hex(args.start + done)}: {done / MIB:.0f}/{args.length / MIB:.0f} MiB, {rate:.1f} MiB/s',
                      flush=True)
    finally:
        os.unlink(tmp_path)
        command(dev, 'mmc dev 1 0')

    with open(args.out_file + '.holes', 'w') as hf:
        for offset, size in holes:
            hf.write(f'{hex(offset)} {hex(size)}\n')
    print(f'done: {len(holes)} holes' + (f', see {args.out_file}.holes' if holes else ''))


if __name__ == '__main__':
    main()
