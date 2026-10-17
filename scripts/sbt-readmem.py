#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read Car Thing RAM over USB in burn mode, for example the ramoops (pstore) area.

The device must be in USB Burn Mode. This script only reads RAM.
The default area is ramoops at 0x07400000 (1 MiB, superbird_evt_512.dts).

Run it with the superbird-tool venv:
  out/tools/superbird-tool/.venv/bin/python scripts/sbt-readmem.py OUT_FILE [ADDR] [LENGTH]
"""

import sys
from pathlib import Path

TOOL_DIR = Path(__file__).resolve().parent.parent / 'out' / 'tools' / 'superbird-tool'
sys.path.insert(0, str(TOOL_DIR))

from superbird_device import SuperbirdDevice, check_device_mode  # noqa: E402


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out_file = sys.argv[1]
    address = int(sys.argv[2], 0) if len(sys.argv) > 2 else 0x07400000
    length = int(sys.argv[3], 0) if len(sys.argv) > 3 else 0x100000

    if not check_device_mode('usb-burn'):
        sys.exit('The device is not in USB Burn Mode. Run superbird_tool.py --burn_mode first.')

    dev = SuperbirdDevice()
    data = dev.read_memory(address, length)
    Path(out_file).write_bytes(data)
    print(f'read {len(data)} bytes from {hex(address)} to {out_file}')


if __name__ == '__main__':
    main()
