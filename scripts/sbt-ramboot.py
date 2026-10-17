#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""RAM-boot a kernel on the Car Thing through the vendor burn-mode U-Boot.

The device must be in USB Burn Mode (superbird_tool.py --burn_mode).
This script writes nothing to the eMMC. The env import, the kernel, the
initrd, and the DTB go to RAM only.

superbird_tool.py --boot_adb_kernel does not give a DTB to booti. After a
mask-ROM USB boot, the burn-mode U-Boot has not loaded a DTB, so booti fails.
This script gives booti a DTB: the device DTB from a boot partition
(--dtb-emmc boot_a: "imgread dtb", which reads the eMMC and decrypts into RAM)
or a DTB file (--dtb).

After a mask-ROM USB boot, U-Boot has also not set up the display. The kernel
VPU driver then runs vpu_power_on, which crashes on the Car Thing
(docs/boot.md). Use --pre-cmd "run init_display" to set up the
display in U-Boot first. The order is: env, DTB (the U-Boot LCD driver reads
the panel setup from it), pre-commands, kernel and initrd, booti.
init_display uses $loadaddr as scratch space, so the kernel goes to RAM after it.

Run it with the superbird-tool venv:
  out/tools/superbird-tool/.venv/bin/python scripts/sbt-ramboot.py \
      --env ENV_TXT --kernel IMAGE --initrd UIMAGE (--dtb-emmc PART | --dtb FILE) \
      [--pre-cmd CMD ...]
"""

import argparse
import sys
import time
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent.parent / 'out' / 'tools' / 'superbird-tool'
sys.path.insert(0, str(TOOL_DIR))

from superbird_device import SuperbirdDevice, check_device_mode  # noqa: E402

ADDR_DTB = 0x01000000  # dtb_mem_addr in the stock env
ADDR_KERNEL_UIMAGE = 0x08000000  # bootm unpacks the kernel from here to its load address


def wait_ready(dev, seconds=30):
    """A long U-Boot command can exceed the USB timeout. Wait until U-Boot answers again."""
    for _ in range(seconds):
        try:
            if 'success' in dev.decode(dev.device.bulkCmd('echo ready')):
                return
        except Exception:
            pass
        time.sleep(1)
    sys.exit('U-Boot did not answer after the pre-command.')


def main():
    parser = argparse.ArgumentParser(description='RAM-boot a kernel through burn-mode U-Boot')
    parser.add_argument('--env', required=True, help='env.txt with bootargs')
    kernel = parser.add_mutually_exclusive_group(required=True)
    kernel.add_argument('--kernel', help='arm64 Image (booti)')
    kernel.add_argument('--kernel-uimage', help='gzip uImage kernel (bootm unpacks it), see scripts/mkuimage-kernel.py')
    parser.add_argument('--initrd', required=True, help='initrd as a U-Boot uImage')
    dtb = parser.add_mutually_exclusive_group(required=True)
    dtb.add_argument('--dtb', help='DTB file to load')
    dtb.add_argument('--dtb-emmc', metavar='PART',
                     help='read the device DTB from a boot partition, for example boot_a')
    parser.add_argument('--pre-cmd', action='append', default=[],
                        help='U-Boot command to run before the files go to RAM (repeatable)')
    args = parser.parse_args()

    if not check_device_mode('usb-burn'):
        sys.exit('The device is not in USB Burn Mode. Run superbird_tool.py --burn_mode first.')

    dev = SuperbirdDevice()
    dev.send_env_file(args.env)
    if args.dtb_emmc:
        # Read only: imgread copies the DTB from the eMMC to RAM and decrypts it.
        dev.bulkcmd(f'imgread dtb {args.dtb_emmc} {hex(ADDR_DTB)}')
    else:
        dev.send_file(args.dtb, ADDR_DTB)
    for command in args.pre_cmd:
        dev.bulkcmd(command, ignore_timeout=True)
        wait_ready(dev)
    if args.kernel:
        dev.send_file(args.kernel, dev.ADDR_KERNEL)
    else:
        dev.send_file(args.kernel_uimage, ADDR_KERNEL_UIMAGE)
    dev.send_file(args.initrd, dev.ADDR_INITRD)

    dev.print('Booting kernel with initrd and DTB')
    if args.kernel:
        dev.bulkcmd(f'booti {hex(dev.ADDR_KERNEL)} {hex(dev.ADDR_INITRD)} {hex(ADDR_DTB)}')
    else:
        dev.bulkcmd(f'bootm {hex(ADDR_KERNEL_UIMAGE)} {hex(dev.ADDR_INITRD)} {hex(ADDR_DTB)}')


if __name__ == '__main__':
    main()
