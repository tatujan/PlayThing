#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Make the Android of this repository (system_b boot area) the default boot of the stock U-Boot, or undo it.

THIS IS A FLASH (env write). Read README.md, "Safety rules", first.

--apply adds cpt_boot and changes bootcmd:
  cpt_boot = read the variables text from the system_b boot area, import it into the
             RAM env (no save), run cpt_script (scripts/mk-systemb-tail.py)
  bootcmd  = preset 1 (GPIOA_0) held: stock boot (run check_charger).
             Else: run cpt_boot. If it returns (failure), run check_charger (stock).
Preset button 4 is not changed: storeargs checks it in preboot, before bootcmd.
--undo sets bootcmd back to the stock value and removes cpt_boot.

The method is the same as superbird-tool --enable_burn_mode_button ("amlmmc env",
"setenv", "env save"). That write changes only the one variable. Enter burn mode fresh (preset button 4)
before the write, so that the RAM env has no imported cpt_* variables.

Run it with the superbird-tool venv:
  out/tools/superbird-tool/.venv/bin/python scripts/sbt-env-cpt-boot.py --apply | --undo
Then compare: sbt --get_env FILE, and diff it with the env from before the write.
"""

import argparse
import sys
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent.parent / 'out' / 'tools' / 'superbird-tool'
sys.path.insert(0, str(TOOL_DIR))

from superbird_device import SuperbirdDevice, check_device_mode  # noqa: E402

CPT_BOOT = 'mmc dev 1 0; mmc read 0x08000000 0x27b0b0 0x80; env import -t 0x08000000 0x10000; run cpt_script'
BOOTCMD = 'if gpio input GPIOA_0; then run check_charger; else run cpt_boot; run check_charger; fi'
BOOTCMD_STOCK = 'run check_charger'
BULKCMD_MAX = 127


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--apply', action='store_true')
    mode.add_argument('--undo', action='store_true')
    args = parser.parse_args()

    if args.apply:
        commands = [f'setenv cpt_boot "{CPT_BOOT}"', f'setenv bootcmd "{BOOTCMD}"']
    else:
        commands = ['setenv cpt_boot', f'setenv bootcmd "{BOOTCMD_STOCK}"']
    for command in commands:
        if len(command) > BULKCMD_MAX or '$' in command:
            sys.exit(f'The command is too long or has a "$": {command}')

    if not check_device_mode('usb-burn'):
        sys.exit('The device is not in USB Burn Mode.')
    dev = SuperbirdDevice()
    dev.bulkcmd('amlmmc env')
    for command in commands:
        dev.bulkcmd(command)
    dev.bulkcmd('env save')


if __name__ == '__main__':
    main()
