#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Make a release folder from the build output (docs/release.md).

  python3 scripts/make-release.py --version 0.1.0 [--build out/aosp] [--out out/release]

Input: out/aosp/system.raw, boot-area.img, blobs.json (scripts/build-image.sh).
Output: out/release/<name>-<version>/ with system.raw.xz, boot-area.img, manifest.json,
flash.py, blobs.py, README.md, SHA256SUMS. No file in it contains a proprietary part: the
image has placeholders (scripts/blobs.py). To get the SHA-256 of the finished image, this
script downloads the proprietary files once and adds them to a temporary copy; that copy is
deleted.
"""

import argparse
import gzip
import json
import lzma
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / 'scripts'))
sys.dont_write_bytecode = True   # no __pycache__ in the repository
import flash  # noqa: E402

NAME = flash.PRODUCT.lower()
TITLE = flash.PRODUCT

README = '''# {title} {version}
{status}
{title} for the Spotify Car Thing. Source code, build steps, and documents: the {title}
repository. This folder has the image and the flash tool.

> **Warning: use at your own risk.** This replaces the software on your Car Thing. It comes
> "as is", with no warranty of any kind. The authors are not responsible for any damage: a Car
> Thing that does not start ("bricked"), lost data, damage to other equipment, or any other
> loss. It was tested on one Car Thing only (S905D2, 512 MiB RAM, ST7701S panel); your unit can
> be different. Keep the backup that the tool makes. Do not use the Car Thing in a way that
> takes your attention from the road while you drive.

## What is inside

- Android 9 Go (32-bit, SELinux enforcing) on Spotify's Linux 4.9 kernel with my patches. The
  stock software stays in slot A (preset 1 at power-on starts it).
- GPU graphics (Mali-G31) with my own display composer, and hardware H.264 decoding.
- Bluetooth audio, the microphones, automatic brightness, the knob, and the buttons.
- A persistent `/data` in one file on the stock data partition (no new partition table).
- {apps}

## What you need

- A Spotify Car Thing whose stock software runs from slot A (the tool checks this).
- A USB-C hub with Power Delivery input and USB-A ports (tested: Anker 6-in-1), and a PD
  charger. The Car Thing uses its USB-C port as a USB host through the hub.
- A Mac or a Linux computer with Python 3.8 or later, git, and libusb (macOS:
  `brew install libusb`), about 5 GB of free disk space, and an internet connection.
  Flash tool (`flash.py`): macOS and Linux. I tested it on macOS with an Apple Silicon Mac; an
  Intel Mac and Linux should work (Linux needs the udev rule below), but I did not test them yet.
  Windows is not supported: the tool stops at the start. Use a Mac or a Linux computer.
  On Linux, give your user access to the Car Thing's USB mode (`1b8e:c003`) once:

  ```sh
  echo 'SUBSYSTEM=="usb", ATTR{{idVendor}}=="1b8e", ATTR{{idProduct}}=="c003", MODE="0666"' | \\
      sudo tee /etc/udev/rules.d/70-car-thing.rules && sudo udevadm control --reload-rules
  ```

  (With `sudo python3 flash.py ...` instead, root owns the files that the tool makes here.)
- A USB cable to connect the Car Thing directly to the computer (no hub for this step).

## Buttons and USB modes

The Car Thing has no battery. It is off when it is not connected, and it starts when you
connect it. The buttons that you hold at that moment select what starts. The four preset
buttons are the row on the top edge: preset 1 is at the left end, preset 4 at the right end.

| Hold while you connect | What starts | When you need it |
|---|---|---|
| nothing | {title} (after the install) | Normal use, through the hub with the charger |
| preset 1 | The stock software | To use the stock software, or when {title} does not start |
| preset 4 | USB burn mode of the Car Thing's own U-Boot (after the first install, which adds it) | Update, `restore`, `check` |
| presets 1 and 4 | Mask-ROM USB mode (the SoC's boot ROM) | First install only: the stock software has no preset-4 burn mode yet |

To start a mode:

1. Disconnect the Car Thing (it turns off).
2. Push and hold the button or buttons.
3. Connect the USB cable. For a USB mode, connect the Car Thing directly to the computer, not
   through a hub; the computer's USB port gives it power.
4. Keep holding for about 3 seconds, then release.

You can start the tool before or after this: it waits up to 2 minutes for the Car Thing, tells
you which buttons to hold, and shows the mode that it found ("found: USB burn mode ..." or
"found: mask-ROM mode"). `python3 flash.py check` shows the mode and the state of the Car Thing
and writes nothing. In mask-ROM mode it asks before it loads a USB U-Boot into RAM (see
"Coming from another firmware").

To leave a USB mode, disconnect the Car Thing and connect it again without a button: through
the hub, it starts {title}. For normal use, connect the charger to the hub's Power Delivery
input, and the hub's USB-C plug to the Car Thing.

## Coming from another firmware

The tool works on a Car Thing with the **stock software**, or with **PlayThing** or
**superbird-android**. Firmwares based on the stock software (for example the Thing Labs /
DeskThing image) keep Spotify's bootloader and partition table; the tool installs into slot B,
and preset 1 still starts that firmware from slot A. If such a firmware changed the boot command
or moved the stock software to slot B, the tool stops before it writes anything.

**BridgeThing, Nocturne, and other firmwares with their own bootloader and a GPT partition
table:** restore the stock software first, with that project's instructions, then install. Do
not run this tool on such a Car Thing: in mask-ROM mode it loads a USB U-Boot that writes an
Amlogic partition table at 36 MiB, which can damage those firmwares. The tool asks before this
step, and it stops if it finds a GPT. Companion apps such as MacThing or Carthios run on top of
a firmware; what counts is the firmware below them.

## Install

```sh
python3 flash.py install
```

At the first run, the tool makes a Python environment (`.venv`, about 1 minute). Then it
shows its steps, and a numbered header and a progress line (percent, time left) for each one:

1. **Tools:** the USB code of [superbird-tool](https://github.com/thinglabsoss/superbird-tool)
   (pinned version).
2. **Image** (`work/system.raw`): it downloads the files that this folder does not contain
   from their public sources, checks them, and adds them:
{downloads}
   The result must have the SHA-256 in `manifest.json`.
3. **Connect:** the Car Thing in mask-ROM mode: hold presets 1 and 4 while you connect it
   directly to the computer ("Buttons and USB modes"). The tool tells you this too.
4. **Check:** it reads the partition table, the slot, and the boot command, shows what it
   found, and asks before it writes anything.
5. **First install only:** a backup of the U-Boot env, `system_b`, and the full eMMC (about 6
   minutes, 3.7 GB) to `backups/` (keep that folder). Then the tool asks you to disconnect the
   Car Thing and connect it again in USB burn mode (preset 4): it checks the partition table of
   the Car Thing's own U-Boot, and only then changes the boot command.
6. **Write:** the image goes to `system_b` (the stock software does not use it). The tool reads
   every block back.

When it says DONE, disconnect the Car Thing and connect it to the hub with the charger, without
a button. {title} starts in about 1 minute (the first start takes longer). From now on, the
buttons work as "Buttons and USB modes" shows.

## Update

Put the Car Thing in USB burn mode (hold preset 4 while you connect it directly to the
computer, "Buttons and USB modes"), and run `python3 flash.py install`. The tool finds the
earlier install, asks, and writes only the blocks that changed (about 3 minutes). Your data
stays. On a Car Thing with the newer Spotify U-Boot, the tool first asks you to connect it once
more with preset 4, to check its partition table.

PlayThing and superbird-android use the same `/data`. When you change from one to the other,
Android deletes the data of the apps that the new image does not have (for example, DiPlay's
settings and the iPhone trust).

`python3 flash.py check` reads the state of the Car Thing and writes nothing.
{diplay_update}
## Remove

Put the Car Thing in USB burn mode (preset 4, "Buttons and USB modes"), and run
`python3 flash.py restore backups/<folder of the first install>`. It writes the stock boot
command and the backed-up `system_b` back. {title} keeps its data in the folder
`{folder}` of the stock data partition (a 1 GiB file); the stock software ignores it.

## If something goes wrong

- **The tool stops before a write.** It tells you why, and that it wrote nothing. Correct the
  cause and run it again.
- **The tool does not find the Car Thing.** Connect it directly to the computer (no hub), try
  another cable or port, and hold the buttons while you connect it. On Linux, check the udev
  rule.
- **A write stops** (cable, power, Ctrl-C). The tool tells you what is written. Connect the
  Car Thing with preset 4 held and run the same command again: it continues. Until then, hold
  preset 1 at power-on to start the stock software.
- **The screen stays black** more than 3 minutes after an install. Disconnect the Car Thing,
  hold preset 1, and connect it: the stock software starts. Then run `python3 flash.py check`
  (preset 4) and the install again, or `restore`.

## Contents

| File | Content |
|---|---|
| `system.raw.xz` | The Android system file system, with placeholders for the downloaded files |
| `boot-area.img` | Kernel, ramdisk, and boot script (the last 32 MiB of `system_b`) |
| `manifest.json` | SHA-256 values, and the source, size, and place of the downloaded files |
| `flash.py`, `blobs.py` | The flash tool |
| `kernel-source.tar.xz`, `aosp-gpl-source.tar.xz` | Source code of the GPL and LGPL parts |

The Bluetooth firmware is not in the image either: Android reads it from the stock software
on your Car Thing (slot A, read-only).

## Licenses and source code

{licenses}

Spotify and Car Thing are trademarks of Spotify AB; Apple, CarPlay, and iPhone are trademarks
of Apple Inc.; Android is a trademark of Google LLC. This project is not affiliated with them.
{thanks}'''
DIPLAY_UPDATE = '''## DiPlay updates (over the air)

DiPlay can update itself on the Car Thing. It needs internet; the iPhone gives it over
Bluetooth:

1. Pair the iPhone with the Car Thing: on the Car Thing, Settings → Bluetooth (or the Bluetooth
   tile of the home screen) → Pair new device.
2. On the iPhone, turn on Settings → Personal Hotspot → Allow Others to Join.
3. On the Car Thing, open Settings → Bluetooth, tap the gear icon of the connected iPhone, and
   turn on "Internet access".
4. In DiPlay, open Settings → About → "Check for updates", and install the update.

Do this while the car is parked: Android compiles the update on the Car Thing, and that takes
some time. The update goes into `/data`; the copy in the image stays. Ethernet through the hub
also gives internet.
'''
DIPLAY_THANKS = '''## Acknowledgments

PlayThing is built around [DiPlay](https://github.com/shihabal3amri/DiPlay) by
[shihabal3amri](https://github.com/shihabal3amri), an Android CarPlay receiver. DiPlay does the
CarPlay work: the wired link to the iPhone, the video, the audio, and the knob input. Without
DiPlay, PlayThing would not exist. Thank you!

DiPlay is a separate project with its own license (GPL-3.0, with AGPL-3.0 parts). PlayThing
downloads its release APK and does not change it.
'''


def ramdisk_has_adb_key(boot_area):
    """True if the ramdisk in the boot area (uImage at 22 MiB, gzip cpio) has /adb_keys."""
    with open(boot_area, 'rb') as f:
        f.seek(22 * flash.MIB)
        header = f.read(64)
        size = int.from_bytes(header[12:16], 'big')
        cpio = gzip.decompress(f.read(size))
    return b'adb_keys\0' in cpio


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--version', required=True)
    parser.add_argument('--build', type=Path, default=REPO / 'out/aosp')
    parser.add_argument('--out', type=Path, default=REPO / 'out/release')
    parser.add_argument('--licenses', type=Path, default=REPO / 'docs/release-licenses.md',
                        help='Markdown text for the section "Licenses and source code"')
    parser.add_argument('--private', action='store_true',
                        help='allow a ramdisk with a developer ADB key (a private release, do not publish)')
    parser.add_argument('--extra', type=Path, nargs='*', default=[],
                        help='more files for the folder (kernel-source.tar.xz, DiPlay source zip)')
    args = parser.parse_args()

    system_raw = args.build / 'system.raw'
    boot_area = args.build / 'boot-area.img'
    blobs_json = json.loads((args.build / 'blobs.json').read_text())
    if flash.sha256_file(system_raw) != blobs_json['image_sha256']:
        sys.exit('system.raw is not the image of blobs.json: run scripts/build-image.sh again')
    if boot_area.stat().st_size != flash.BOOT_AREA_SIZE:
        sys.exit('boot-area.img has the wrong size')
    if ramdisk_has_adb_key(boot_area) and not args.private:
        sys.exit('the ramdisk has a developer ADB key (device/spotify/superbird/adb_keys.pub): '
                 'build a public release without it, or use --private')
    folder = args.out / f'{NAME}-{args.version}'
    if folder.exists():
        sys.exit(f'{folder} exists')
    folder.mkdir(parents=True)

    print('== SHA-256 of the finished image')
    with tempfile.TemporaryDirectory(dir=args.out) as tmp:
        work = Path(tmp) / 'system.raw'
        shutil.copyfile(system_raw, work)
        flash.add_files(work, blobs_json['files'], args.out / '.cache')
        final_sha256 = flash.sha256_file(work)

    print('== pack')
    packed = folder / 'system.raw.xz'
    if shutil.which('xz'):
        with open(packed, 'wb') as out:
            subprocess.run(['xz', '-T0', '-6', '-c', str(system_raw)], stdout=out, check=True)
    else:
        with open(system_raw, 'rb') as src, lzma.open(packed, 'wb', preset=6) as dst:
            shutil.copyfileobj(src, dst, 4 * flash.MIB)
    shutil.copyfile(boot_area, folder / 'boot-area.img')
    manifest = {
        'name': TITLE,
        'version': args.version,
        'image': 'system.raw',
        'image_size': blobs_json['image_size'],
        'image_sha256': blobs_json['image_sha256'],
        'final_sha256': final_sha256,
        'boot_area_sha256': flash.sha256_file(boot_area),
        'files': blobs_json['files'],
    }
    (folder / 'manifest.json').write_text(json.dumps(manifest, indent=1) + '\n')
    for f in ('flash.py', 'blobs.py'):
        shutil.copy2(REPO / 'scripts' / f, folder / f)
    for f in args.extra:
        shutil.copy2(f, folder / f.name)
    licenses = args.licenses.read_text().strip() if args.licenses.exists() else 'See the repository.'
    downloads = '\n'.join(f'   - {f["what"]}' for f in blobs_json['files'])
    diplay_update = thanks = ''
    status = ''
    if 'beta' in args.version:
        status = ('\n**Beta version.** It is tested on one Car Thing only, and it has known issues '
                  '(`docs/known-issues.md`\nin the repository).\n')
    if any(f['name'] == 'DiPlay.apk' for f in blobs_json['files']):
        apps = 'CarPlay with DiPlay (downloaded by the tool), a home screen, and a minimal keyboard.'
        diplay_update, thanks = '\n' + DIPLAY_UPDATE, '\n' + DIPLAY_THANKS
    else:
        apps = 'The AOSP Go launcher and a minimal keyboard, as a base for other Android apps.'
    (folder / 'README.md').write_text(README.format(title=TITLE, version=args.version,
                                                    folder=flash.DATA_FOLDER, licenses=licenses,
                                                    downloads=downloads, apps=apps,
                                                    diplay_update=diplay_update, thanks=thanks,
                                                    status=status))
    sums = ''.join(f'{flash.sha256_file(p)}  {p.name}\n' for p in sorted(folder.iterdir()))
    (folder / 'SHA256SUMS').write_text(sums)
    print(sums)
    print(f'release: {folder}')


if __name__ == '__main__':
    main()
