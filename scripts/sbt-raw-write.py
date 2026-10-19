#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Write a file to one raw region of the Car Thing eMMC, with a read-back check.

THIS IS A FLASH. Use it only after the user approved the exact command
(README.md, "Safety rules").

superbird-tool writes by partition name, and its burn-mode U-Boot uses a
partition table that does not match the real layout (docs/boot.md). This script
writes by raw sector numbers instead. For each block of the file:
  1. It reads the eMMC block ("mmc read" into RAM, then "upload mem").
     If the block already has the file data, it skips the block.
  2. It sends the file data to RAM (writeLargeMemory).
  3. It runs "mmc write" on the user area ("mmc dev 1 0" first).
  4. It reads the block back from the eMMC and compares it with the file data.
     If the data differs, it tries again (3 times), then stops.

Safety:
  - Only the regions in ALLOWED can be written. They are the inactive slot (B).
  - Before the first write, the script reads the MPT from the eMMC. The MPT
    entry of the target must have the same offset and size as ALLOWED. This
    makes sure that the stock U-Boot wrote the MPT (button-4 entry), and that
    the region is correct.
  - The file must not be larger than the region. The rest of the region does
    not change.
  - --dry-run writes nothing. It reads the region and counts the blocks that
    differ from the file.

Restore: write the same region from the raw backup, for example
  dd if=backups/<time>/emmc-user.img of=OUT bs=4096 skip=$((OFFSET/4096)) count=$((SIZE/4096))
  and then this script with OUT.

The device must be in USB Burn Mode from the stock U-Boot (preset button 4).
Run it with the superbird-tool venv:
  out/tools/superbird-tool/.venv/bin/python scripts/sbt-raw-write.py IN_FILE TARGET [--offset N] [--dry-run]
--offset writes the file at byte N inside the region (a multiple of 512). The file must
end inside the region.
"""

import argparse
import hashlib
import os
import struct
import sys
import tempfile
import time
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent.parent / 'out' / 'tools' / 'superbird-tool'
sys.path.insert(0, str(TOOL_DIR))

from superbird_device import SuperbirdDevice, check_device_mode  # noqa: E402

# Physical layout, verified on the device (docs/boot.md). Inactive slot only.
ALLOWED = {
    'boot_b': (0x0ee00000, 0x01000000),
    'system_b': (0x3120b000, 0x2040b000),
}

WRITE_BUF = 0x02000000   # 16 MiB, free RAM below ramoops (0x07400000) and BL31 (0x05000000)
VERIFY_BUF = 0x03000000  # 16 MiB
BLOCK = 16 * 2**20
SECTOR = 512
MPT_SECTOR = 36 * 2**20 // SECTOR
RETRIES = 3
MMC_TIMEOUT = 30000  # ms


def command(dev, cmd, timeout=None):
    """Run a U-Boot command. Return True on success. Do not stop the program on failure.

    The default status timeout (pyusb) is 1 s. A 16 MiB "mmc write" takes longer.
    If the status read times out, U-Boot is still busy, and the next commands
    collide with it: U-Boot then stops answering until a power cycle.
    Thus "mmc read" and "mmc write" use MMC_TIMEOUT.
    """
    try:
        return 'success' in dev.decode(dev.device.bulkCmd(cmd, timeout=timeout))
    except Exception:
        return False


def read_emmc(dev, offset, size, tmp_path):
    """Read eMMC bytes through VERIFY_BUF. Return None on failure.

    The upload size is always a multiple of 1 MiB, as in all tested dumps.
    Thus the last block of system_b reads up to 1 MiB past the region, into the
    8 MiB gap before misc (read only), and returns only the region bytes.
    """
    aligned = -(-size // 2**20) * 2**20
    if not command(dev, 'mmc dev 1 0'):
        return None
    if not command(dev, f'mmc read {hex(VERIFY_BUF)} {hex(offset // SECTOR)} {hex(aligned // SECTOR)}',
                   MMC_TIMEOUT):
        return None
    if not command(dev, f'upload mem {hex(VERIFY_BUF)} normal {hex(aligned)}'):
        return None
    try:
        dev._fast_read_to_file('raw', aligned, VERIFY_BUF, tmp_path)
    except Exception:
        dev._fast_reopen()
        return None
    data = Path(tmp_path).read_bytes()
    return data[:size] if len(data) == aligned else None


def read_emmc_retry(dev, offset, size, tmp_path):
    for _ in range(RETRIES):
        data = read_emmc(dev, offset, size, tmp_path)
        if data is not None:
            return data
        time.sleep(1)
    sys.exit(f'{hex(offset)}: read failed {RETRIES} times. Stopped. Blocks before this one are written and verified.')


def check_mpt(dev, target):
    """Make sure that the MPT entry of the target matches ALLOWED."""
    command(dev, 'mmc dev 1 0')
    command(dev, f'mmc read {hex(VERIFY_BUF)} {hex(MPT_SECTOR)} 0x8')
    data = dev.read_memory(VERIFY_BUF, 8 * SECTOR)
    if data[:3] != b'MPT':
        sys.exit('No MPT magic at 36 MiB. Stopped.')
    count, = struct.unpack_from('<I', data, 16)
    entries = {}
    for i in range(min(count, 32)):
        name, size, offset, _mask = struct.unpack_from('<16sQQI', data, 24 + i * 40)
        entries[name.split(b'\0')[0].decode(errors='replace')] = (offset, size)
    if 'fip_a' in entries or 'fip_b' in entries:
        sys.exit('The MPT has fip_a/fip_b (superbird-tool layout). Enter burn mode with button 4. Stopped.')
    if entries.get(target) != ALLOWED[target]:
        sys.exit(f'MPT entry of {target} is {entries.get(target)}, expected {ALLOWED[target]}. Stopped.')
    print(f'MPT check: {target} at {hex(ALLOWED[target][0])}, size {hex(ALLOWED[target][1])}')


def main():
    parser = argparse.ArgumentParser(description='Raw eMMC write with read-back check (a flash)')
    parser.add_argument('in_file')
    parser.add_argument('target', choices=sorted(ALLOWED))
    parser.add_argument('--dry-run', action='store_true', help='write nothing, count the blocks that differ')
    parser.add_argument('--offset', type=lambda x: int(x, 0), default=0,
                        help='byte offset inside the region (multiple of 512)')
    args = parser.parse_args()

    region_start, region_size = ALLOWED[args.target]
    file_size = os.path.getsize(args.in_file)
    if args.offset < 0 or args.offset % SECTOR:
        sys.exit('--offset must be a non-negative multiple of 512.')
    if args.offset + file_size > region_size:
        sys.exit(f'The file ({file_size} bytes at offset {args.offset}) does not fit in '
                 f'{args.target} ({region_size} bytes).')
    region_offset = region_start + args.offset
    if file_size % SECTOR:
        sys.exit('The file size must be a multiple of 512.')
    if not check_device_mode('usb-burn'):
        sys.exit('The device is not in USB Burn Mode.')

    dev = SuperbirdDevice()
    check_mpt(dev, args.target)
    file_sha = hashlib.sha256()
    written = skipped = differ = 0
    start_time = time.time()
    fd, tmp_path = tempfile.mkstemp(suffix='.blk')
    os.close(fd)
    try:
        with open(args.in_file, 'rb') as inf:
            done = 0
            while done < file_size:
                size = min(BLOCK, file_size - done)
                offset = region_offset + done
                data = inf.read(size)
                file_sha.update(data)
                current = read_emmc_retry(dev, offset, size, tmp_path)
                if current == data:
                    skipped += 1
                elif args.dry_run:
                    differ += 1
                else:
                    for attempt in range(1, RETRIES + 1):
                        dev.device.writeLargeMemory(WRITE_BUF, data, 4096, True)
                        command(dev, 'mmc dev 1 0')
                        if not command(dev, f'mmc write {hex(WRITE_BUF)} {hex(offset // SECTOR)} {hex(size // SECTOR)}',
                                       MMC_TIMEOUT):
                            sys.exit(f'{hex(offset)}: mmc write did not report success. Stopped.')
                        if read_emmc_retry(dev, offset, size, tmp_path) == data:
                            break
                        print(f'{hex(offset)}: read-back differs (attempt {attempt})', flush=True)
                    else:
                        sys.exit(f'{hex(offset)}: read-back differs after {RETRIES} writes. Stopped.')
                    written += 1
                done += size
                rate = done / max(time.time() - start_time, 1e-3) / 2**20
                print(f'{hex(offset + size)}: {done / 2**20:.0f}/{file_size / 2**20:.0f} MiB, '
                      f'{rate:.1f} MiB/s, written {written}, same {skipped}, differ {differ}', flush=True)
    finally:
        os.unlink(tmp_path)
        command(dev, 'mmc dev 1 0')

    print(f'U-Boot answers after the run: {command(dev, "echo ready")}')
    print(f'file sha256 {file_sha.hexdigest()}')
    if args.dry_run:
        print(f'dry run: {differ} blocks differ, {skipped} blocks are the same. Nothing was written.')
    else:
        print(f'done: {written} blocks written and verified, {skipped} blocks were already the same.')


if __name__ == '__main__':
    main()
