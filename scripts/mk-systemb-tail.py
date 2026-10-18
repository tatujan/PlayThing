#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the boot area at the end of the system_b region (the last 32 MiB).

The stock U-Boot boots our Android from the eMMC with these files. The env holds only a
small loader (cpt_boot): it reads the variables text from this area, imports it into the
RAM env (env import -t, no save), and runs cpt_script. Thus later changes to the boot
steps are system_b writes, not env writes. U-Boot here has no "source" and no "unzip",
and "bootm" with a gzip kernel failed, so the kernel is an uncompressed Image for booti.

Layout (offsets from the start of the area, which follows the system file system):
  0        variables text (env import -t format, max 64 KiB)
  1 MiB    kernel Image (max 21 MiB)
  22 MiB   ramdisk uImage (max 6 MiB)
  28 MiB   debug-log area (4 MiB, zeros)

Usage:
  python3 scripts/mk-systemb-tail.py --kernel IMAGE --ramdisk RAMDISK_UIMG \\
      --mode device|host --fs-size BYTES --out TAIL_IMG
The bootargs come from scripts/ramboot/env-android.txt. The script prints the
variables text and the loader values for the env.
"""

import argparse
from pathlib import Path

SYSTEM_B_START = 0x3120b000
SYSTEM_B_SIZE = 0x2040b000
MIB = 2**20
AREA = 32 * MIB
VARS_OFF, VARS_MAX = 0, 64 * 1024
KERNEL_OFF, KERNEL_MAX = 1 * MIB, 21 * MIB
RAMDISK_OFF, RAMDISK_MAX = 22 * MIB, 6 * MIB
LOG_OFF = 28 * MIB
SECTOR = 512

DT_EDITS = [
    'fdt addr 0x1000000',
    'fdt rm /firmware/android',
    'fdt set /reserved-memory/ramoops@0x07400000 console-size <0x80000>',
    'fdt set /reserved-memory/ramoops@0x07400000 ftrace-size <0x0>',
    'fdt set /soc/cbus@ffd00000/i2c@1f000/tlsc6x_ts@2e touchscreen-inverted-x',
    'fdt set /soc/cbus@ffd00000/i2c@1f000/tlsc6x_ts@2e touchscreen-swapped-x-y',
    # Decoder memory (docs/video.md). The stock 308 MiB codec_mm CMA does not fit and fails;
    # 64 MiB is enough for 800 x 480 H.264. It is reusable CMA (movable pages when idle).
    'fdt set /reserved-memory/linux,codec_mm_cma size <0x0 0x4000000>',
]
# Knob and buttons for CarPlay (DiPlay maps DPAD/ENTER/BACK to the CarPlay knob, and media
# keys to CarPlay media buttons; Android's Generic.kl maps these Linux codes). Knob: one key
# per step (patches/kernel/spotify-4.9/0005-*): KEY_LEFT counterclockwise, KEY_RIGHT clockwise.
# Front button: KEY_BACK. Presets 1-3: KEY_PREVIOUSSONG, KEY_PLAYPAUSE, KEY_NEXTSONG.
# Preset 4: KEY_F1, which DiPlay uses as its assigned Siri key (diplay-setup.sh). Android 9
# takes KEY_VOICECOMMAND (VOICE_ASSIST) for its own assistant, so DiPlay never saw it.
# Mute: KEY_MUTE. Knob press stays KEY_ENTER (select).
# U-Boot reads preset 1 and 4 at power-on directly, so the stock boot and burn mode do not change.
KEY_EDITS = [
    'fdt set /rotary@0 linux,key-codes <0x69 0x6a>',
    'fdt set /gpio-keys/back linux,code <0x9e>',
    'fdt set /gpio-keys/preset1 linux,code <0xa5>',
    'fdt set /gpio-keys/preset2 linux,code <0xa4>',
    'fdt set /gpio-keys/preset3 linux,code <0xa3>',
    'fdt set /gpio-keys/preset4 linux,code <0x3b>',
    'fdt set /gpio-keys/mute linux,code <0x71>',
]
# USB-C port as host for the powered dongle (patches/kernel/spotify-4.9/0003-*).
HOST_EDITS = [
    'fdt set /usb3phy@ffe09080 status okay',
    'fdt set /usb3phy@ffe09080 otg <0>',
    'fdt set /usb3phy@ffe09080 portnum <0>',
    'fdt set /usb3phy@ffe09080 force-host-mode',
    'fdt rm /usb3phy@ffe09080 gpio-vbus-power',
    'fdt set /dwc2_a@ff400000 status disabled',
]


def sectors(nbytes):
    return -(-nbytes // SECTOR)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--kernel', required=True)
    parser.add_argument('--ramdisk', required=True)
    parser.add_argument('--mode', choices=('device', 'host'), required=True)
    parser.add_argument('--fs-size', type=lambda x: int(x, 0), required=True)
    parser.add_argument('--out', required=True)
    parser.add_argument('--selinux', choices=('enforcing', 'permissive'), default='enforcing',
                        help='permissive adds androidboot.selinux=permissive (docs/selinux.md)')
    args = parser.parse_args()

    if args.fs_size % 4096 or args.fs_size + AREA != SYSTEM_B_SIZE:
        raise SystemExit(f'The file system size must be system_b minus 32 MiB ({SYSTEM_B_SIZE - AREA}).')
    kernel = Path(args.kernel).read_bytes()
    ramdisk = Path(args.ramdisk).read_bytes()
    if len(kernel) > KERNEL_MAX or kernel[0x38:0x3c] != b'ARM\x64':
        raise SystemExit('The kernel is too large or not an arm64 Image.')
    if len(ramdisk) > RAMDISK_MAX or ramdisk[:4] != b'\x27\x05\x19\x56':
        raise SystemExit('The ramdisk is too large or not a uImage.')

    area_start = SYSTEM_B_START + args.fs_size
    ksec, rsec = (area_start + KERNEL_OFF) // SECTOR, (area_start + RAMDISK_OFF) // SECTOR
    bootargs = (Path(__file__).resolve().parent / 'ramboot/env-android.txt').read_text().strip()
    assert bootargs.startswith('bootargs=')
    bootargs = ' '.join(t for t in bootargs.split(' ') if not t.startswith('androidboot.selinux='))
    if args.selinux == 'permissive':
        bootargs += ' androidboot.selinux=permissive'
    lines = [
        'cpt_bootargs=' + bootargs[len('bootargs='):],
        'cpt_dt=' + '; '.join(DT_EDITS),
        'cpt_keys=' + '; '.join(KEY_EDITS),
        'cpt_host=' + '; '.join(HOST_EDITS),
        'cpt_mode=' + ('run cpt_host' if args.mode == 'host' else 'echo cpt: device mode'),
        # imgread uses loadaddr (0x1080000, the kernel address) as its work buffer
        # (Assumed). Thus the DTB comes first and the kernel after it, as in sbt-ramboot.py.
        # The newer Spotify U-Boot applies the overlays of dtbo_a at booti when the env has
        # androidboot.dtbo_idx (bootm.c, do_fdt_overlay). The stock software v8 sets it, and
        # its overlay moves the display from the framebuffer driver (meson-fb, /dev/graphics/fb0,
        # which the HWC needs) to the DRM driver. So delete it in the RAM env (no env save).
        'cpt_script=imgread dtb boot_a 0x1000000; run cpt_dt; run cpt_keys; run cpt_mode; '
        f'mmc dev 1 0; mmc read 0x1080000 {hex(ksec)} {hex(sectors(len(kernel)))}; '
        f'mmc read 0x13000000 {hex(rsec)} {hex(sectors(len(ramdisk)))}; '
        'setenv bootargs ${cpt_bootargs}; setenv androidboot.dtbo_idx; '
        'booti 0x1080000 0x13000000 0x1000000',
    ]
    text = ('\n'.join(lines) + '\n').encode()
    if len(text) >= VARS_MAX:
        raise SystemExit('The variables text is too large.')

    area = bytearray(AREA)
    area[VARS_OFF:VARS_OFF + len(text)] = text
    area[KERNEL_OFF:KERNEL_OFF + len(kernel)] = kernel
    area[RAMDISK_OFF:RAMDISK_OFF + len(ramdisk)] = ramdisk
    Path(args.out).write_bytes(area)

    print(text.decode())
    print(f'area: {hex(area_start)} (sector {hex(area_start // SECTOR)}), {AREA // MIB} MiB, '
          f'write offset in system_b {hex(args.fs_size)}')
    print(f'debug-log area: {hex(area_start + LOG_OFF)} (sector {hex((area_start + LOG_OFF) // SECTOR)})')
    print(f'loader: cpt_boot=mmc dev 1 0; mmc read 0x08000000 {hex(area_start // SECTOR)} '
          f'{hex(VARS_MAX // SECTOR)}; env import -t 0x08000000 {hex(VARS_MAX)}; run cpt_script')


if __name__ == '__main__':
    main()
