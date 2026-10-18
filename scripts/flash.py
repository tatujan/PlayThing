#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""PlayThing flash tool for the Spotify Car Thing (docs/release.md).

  python3 flash.py install           install PlayThing, or update it
  python3 flash.py restore BACKUP    remove PlayThing: write system_b and the boot command back
  python3 flash.py prepare           only make the image (no device)
  python3 flash.py check             read the Car Thing's state, write nothing
  python3 flash.py diag              collect a diagnostic folder (diag/): env, partition table,
                                     image check, stock device tree, PlayThing logs; writes nothing

Before its first run, the tool makes a Python environment (.venv: pyusb, pyamlboot).
"install" then does these steps, and it numbers them in its output:
  1. Tools: it downloads the USB code and the USB burn-mode U-Boot of superbird-tool (pinned
     commit, SHA-256 checked) to .tools/.
  2. Image: it unpacks system.raw, downloads the files that the release may not contain from
     their public sources, checks their SHA-256, and writes them into their placeholders. The
     result must have the SHA-256 of the release (manifest.json).
  3. Connect: the Car Thing in USB mode.
  4. Check: it reads the partition table and the U-Boot env, checks that the stock software
     runs from slot A, shows what it found, and asks before it writes.
  5. First install only: a backup (env, system_b, and the full eMMC unless --no-full-backup),
     then the env change: preset 4 at power-on enters USB burn mode, the normal boot starts
     PlayThing, and preset 1 at power-on starts the stock software.
     The tool writes system_b only when the partition table of the Car Thing's own U-Boot has
     system_b where the tool writes (with or without fip_a/fip_b). After a mask-ROM entry the table
     comes from superbird-tool's U-Boot, so the tool first adds only the preset-4 burn mode, asks
     for a new connection with preset 4, checks the table, and then changes the boot command.
  6. Write: the image to system_b (the stock B slot, which the stock software does not use),
     each block read back. If a write stops, the same command finishes it.
It does not change the bootloader, the stock A slot, or the partition table. PlayThing keeps
its /data in one folder on the stock data partition (plaything/userdata.img, 1 GiB).

Needs: Python 3.8 or later, git, and libusb (macOS: brew install libusb). On Linux, add a udev
rule for the USB device 1b8e:c003 (README.md), or run it with sudo. Connect the Car Thing
directly to the computer, not through a hub.
"""

import argparse
import contextlib
import hashlib
import io
import json
import lzma
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
import zlib
from datetime import datetime, timezone
from pathlib import Path

PRODUCT = 'PlayThing'
DATA_FOLDER = 'plaything'   # the /data folder on the stock data partition (data-setup.sh)

HERE = Path(__file__).resolve().parent
VENV = HERE / '.venv'
TOOLS = HERE / '.tools'
CACHE = HERE / '.cache'
WORK = HERE / 'work'
BACKUPS = HERE / 'backups'

# superbird-tool (thinglabsoss): "unlicensed", with MIT-licensed parts (LICENSES/).
SBT_COMMIT = 'da05fb49f7ebb2d0aebff128d3e197fd087d1709'
SBT_URL = 'https://raw.githubusercontent.com/thinglabsoss/superbird-tool/' + SBT_COMMIT + '/'
SBT_FILES = {
    'superbird_device.py': 'f03d6723793ab3562dff180312ad3bc59de2afdc3df2ff117c72fdf87dc450ed',
    'superbird_partitions.py': '9b2b76a4762f67766399f59e39b24c09bf61cf87e1986e914805d4b95f649400',
    'images/superbird.bl2.encrypted.bin': 'd6aad144ea090e425a986dbd174a605d80d5768410ba4039e3d160497ca93049',
    'images/superbird.bootloader.img': '2635177c767228f3cf13ca0e286015b9eaf90c3263219cea4c155cd59cd8b75a',
    'LICENSES/car-thing-bootloader-tool-MIT.txt': '529c1f1f8ef8570a8ae6f3de91839dca5ab7ea1c2b8f0b3c6dfe99a8091d4a03',
}
PIP_PACKAGES = [
    'git+https://github.com/pyusb/pyusb@3241c0e6a7debafd7597925e8403d9d3ea02ed93',
    'git+https://github.com/superna9999/pyamlboot@d2857f4371bcdb7a90d885f24987e03b6d647a8a',
]

# eMMC layout of the Car Thing (stock U-Boot partition table, docs/boot.md).
MIB = 2**20
SECTOR = 512
MPT_OFFSET = 36 * MIB
ENV_OFFSET, ENV_REGION = 116 * MIB, 8 * MIB
ENV_SIZE = 0x10000
SYSTEM_B = (0x3120b000, 0x2040b000)
BOOT_AREA_OFFSET = 0x1e40b000   # inside system_b: the last 32 MiB
# Two kinds of Spotify U-Boot are in the field. The older one (for example v1.0-57) has no
# fip_a/fip_b partitions; the newer one (for example v1.0-74) adds fip_a and fip_b after env. In
# both, system_b (and boot_a, system_a, data) are at the same place. superbird-tool's USB U-Boot
# always adds fip_a and fip_b to the Car Thing's own list, so on a Car Thing with the older U-Boot
# its table has every partition from logo 24 MiB further (2 x (4 MiB + 8 MiB gap)).
FIP_SHIFT = 24 * MIB
USB_IDS = ((0x1b8e, 0xc003), (0x18d1, 0x4e40))   # Amlogic USB modes; the stock software's gadget
BOOT_AREA_SIZE = 32 * MIB

# U-Boot env changes (scripts/sbt-env-cpt-boot.py). cpt_boot reads the boot script from the
# boot area (sector 0x27b0b0 = system_b + BOOT_AREA_OFFSET) and runs it. Preset 1 (GPIOA_0) held
# at power-on: stock boot. Preset 4 (GPIOA_3): USB burn mode (superbird-tool's change).
CPT_BOOT = ('mmc dev 1 0; mmc read 0x08000000 0x27b0b0 0x80; env import -t 0x08000000 0x10000; '
            'run cpt_script')
BOOTCMD = 'if gpio input GPIOA_0; then run check_charger; else run cpt_boot; run check_charger; fi'
BOOTCMD_STOCK = 'run check_charger'
BURN_BUTTON = ' if gpio input GPIOA_3; then run update; fi;'
BULKCMD_MAX = 127

BUFFER = 0x02000000   # U-Boot RAM buffer for writes (16 MiB)
VERIFY = 0x03000000   # U-Boot RAM buffer for reads (16 MiB)
BLOCK = 16 * MIB
RETRIES = 3
MMC_TIMEOUT = 30000   # ms


# What the tool has written so far. stop() adds the text that tells how to continue.
PHASE = 'read'
RECOVERY = {
    'button': ('The tool added only the preset-4 USB burn mode to the U-Boot env. The boot command is\n'
               'not changed, so the Car Thing starts the stock software as before. To continue: hold\n'
               'preset 4 while you connect it directly, and run "python3 flash.py install" again.'),
    'env': ('The boot command is changed, but the image is not written yet. Until it is, the Car\n'
            'Thing starts the stock software. Connect it with preset 4 held, and run\n'
            '"python3 flash.py install" again.'),
    'write': ('system_b is not complete. Connect the Car Thing with preset 4 held, and run\n'
              '"python3 flash.py install" again: it writes the missing blocks. Until then, hold\n'
              'preset 1 at power-on to start the stock software.'),
    'restore': ('The boot command is the stock one, so the Car Thing starts the stock software (it\n'
                'does not use system_b). To finish, connect it with preset 4 held, and run the same\n'
                'restore command again.'),
}
STEPS = []


def say(text=''):
    print(text, flush=True)


def stop(text):
    say()
    say('STOPPED: ' + text)
    if PHASE in RECOVERY:
        say(RECOVERY[PHASE])
    sys.exit(1)


def ask(text):
    say()
    try:
        input(text + '\nPress Enter to continue (Ctrl-C to stop). ')
    except EOFError:
        stop('no answer (the tool needs a terminal for its questions)')
    if not sys.stdin.isatty():
        say()


def overview(title, steps):
    """Print the steps of a command. step() prints the header of each one."""
    global STEPS
    STEPS = steps
    say(title)
    for i, text in enumerate(steps, 1):
        say(f'  {i}. {text}')


def step(n, text=None):
    say()
    say(f'== Step {n} of {len(STEPS)}: {text or STEPS[n - 1]}')


def duration(seconds):
    seconds = int(seconds + 0.5)
    return f'{seconds} s' if seconds < 60 else f'{seconds // 60} min {seconds % 60:02d} s'


def blocks(n):
    return f'{n} block' + ('' if n == 1 else 's')


class Progress:
    """A progress line: MiB done, percent, speed, and time left. On a terminal the line is
    written again in place; else (a log) a new line comes at each 10 %."""

    def __init__(self, label, total):
        self.label, self.total, self.start = label, max(total, 1), time.time()
        self.tty = sys.stdout.isatty()
        self.width = 0
        self.tenth = 0

    def _show(self, text, last=False):
        if self.tty:
            print('\r' + text.ljust(self.width), end='\n' if last else '', flush=True)
            self.width = len(text)
        else:
            print(text, flush=True)

    def update(self, done):
        tenth = 10 * done // self.total
        if not self.tty and (tenth == self.tenth or done >= self.total):
            return
        self.tenth = tenth
        rate = done / max(time.time() - self.start, 1e-3)
        left = (self.total - done) / rate if rate else 0
        self._show(f'  {self.label}: {done // MIB}/{self.total // MIB} MiB ({100 * done // self.total} %), '
                   f'{rate / MIB:.1f} MiB/s, about {duration(left)} left')

    def end(self, text):
        self._show(f'  {self.label}: {text}, in {duration(time.time() - self.start)}', last=True)


OTHER_FIRMWARE = ('This tool works only on a Car Thing with the stock software or with PlayThing or\n'
                  'superbird-android. Firmwares with their own bootloader and partition table (for\n'
                  'example BridgeThing or Nocturne) must be removed first: restore the stock software\n'
                  'with that project\'s instructions (README.md, "Coming from another firmware").')


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(MIB), b''):
            h.update(chunk)
    return h.hexdigest()


def download(url, dest, sha256, size=None, quiet=False):
    """Download url to dest, unless dest already has the SHA-256. Check the result."""
    dest = Path(dest)
    if dest.exists() and sha256_file(dest) == sha256:
        if not quiet:
            say(f'    from the cache ({dest.parent.name}/), SHA-256 correct')
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    say(f'    download {url}')
    tmp = dest.with_name(dest.name + '.part')
    request = urllib.request.Request(url, headers={'User-Agent': 'plaything-flash'})
    try:
        with urllib.request.urlopen(request, timeout=60) as r, open(tmp, 'wb') as f:
            shutil.copyfileobj(r, f, MIB)
    except Exception as e:
        tmp.unlink(missing_ok=True)
        hint = ''
        if 'CERTIFICATE_VERIFY_FAILED' in str(e):
            hint = ' (macOS python.org Python: run "Install Certificates.command" once)'
        stop(f'download failed: {e}{hint}')
    if (size is not None and tmp.stat().st_size != size) or sha256_file(tmp) != sha256:
        tmp.unlink()
        stop(f'{url}: wrong size or SHA-256')
    tmp.replace(dest)
    if not quiet:
        say(f'    {dest.stat().st_size / MIB:.1f} MiB, SHA-256 correct')
    return dest


# ------------------------------------------------------------------ environment and tools

def bootstrap():
    """Run this script again in .venv, with pyusb and pyamlboot."""
    if os.environ.get('PLAYTHING_FLASH_VENV') == '1':
        return
    python = VENV / 'bin' / 'python'
    marker = VENV / '.plaything-ready'
    if not marker.exists():
        if shutil.which('git') is None:
            stop('git is missing (macOS: xcode-select --install; Linux: install git).')
        say('== Python environment for this tool (.venv; only at the first run, about 1 minute)')
        subprocess.run([sys.executable, '-m', 'venv', str(VENV)], check=True)
        subprocess.run([str(python), '-m', 'pip', 'install', '--quiet', '--disable-pip-version-check']
                       + PIP_PACKAGES, check=True)
        marker.write_text('ok\n')
        say('  ready')
    os.environ['PLAYTHING_FLASH_VENV'] = '1'
    os.execv(str(python), [str(python), str(Path(__file__).resolve())] + sys.argv[1:])


def load_tools():
    base = TOOLS / 'superbird-tool'
    for rel, sha in SBT_FILES.items():
        download(SBT_URL + rel, base / rel, sha, quiet=True)
    sys.path.insert(0, str(base))
    try:
        import superbird_device  # noqa: F401
    except SystemExit:
        stop('pyusb or pyamlboot does not load (macOS: brew install libusb). Then run again.')
    say(f'  superbird-tool {SBT_COMMIT[:7]}: {len(SBT_FILES)} files in .tools/, SHA-256 correct')
    return superbird_device


# ------------------------------------------------------------------ the image

def release_files(images):
    manifest_path = images / 'manifest.json'
    if not manifest_path.exists():
        stop(f'no manifest.json in {images}')
    manifest = json.loads(manifest_path.read_text())
    return manifest


def blob_placeholder(entry):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import blobs
    return blobs.placeholder(entry)


def add_files(image, files, cache):
    """Download each file, check it, and write it into its placeholder blocks of image."""
    with open(image, 'r+b') as img:
        for entry in files:
            say(f'  {entry["path"]}: {entry["what"]}')
            blob = download(entry['url'], Path(cache) / entry['name'], entry['sha256'], entry['size'])
            data = blob.read_bytes()
            expected = blob_placeholder(entry)
            pos = 0
            for offset, length in entry['extents']:
                img.seek(offset)
                if img.read(length) != expected[pos:pos + length]:
                    stop(f'{entry["path"]}: the image has no placeholder at {hex(offset)}')
                img.seek(offset)
                img.write(data[pos:pos + length])
                pos += length
            if pos != entry['size']:
                stop(f'{entry["path"]}: the extents do not cover the file')


def prepare(images):
    """Make work/system.raw: the release image with the downloaded files. Return its path."""
    manifest = release_files(images)
    boot = images / 'boot-area.img'
    if not boot.exists() or boot.stat().st_size != BOOT_AREA_SIZE or \
            sha256_file(boot) != manifest['boot_area_sha256']:
        stop('boot-area.img is missing or is not the file of manifest.json')
    out = WORK / 'system.raw'
    if out.exists() and out.stat().st_size == manifest['image_size'] and \
            sha256_file(out) == manifest['final_sha256']:
        say(f'  work/system.raw: made at an earlier run, SHA-256 of the release correct')
        return out, manifest
    WORK.mkdir(exist_ok=True)
    tmp = WORK / 'system.raw.part'
    packed = images / (manifest['image'] + '.xz')
    plain = images / manifest['image']
    if packed.exists():
        bar = Progress(f'unpack {packed.name}', manifest['image_size'])
        with lzma.open(packed) as src, open(tmp, 'wb') as dst:
            while True:
                chunk = src.read(4 * MIB)
                if not chunk:
                    break
                dst.write(chunk)
                bar.update(dst.tell())
        bar.end(f'{manifest["image_size"] // MIB} MiB')
    elif plain.exists():
        shutil.copyfile(plain, tmp)
    else:
        stop(f'no {packed.name} in {images}')
    if tmp.stat().st_size != manifest['image_size'] or sha256_file(tmp) != manifest['image_sha256']:
        stop(f'{manifest["image"]} is not the image of manifest.json (size or SHA-256)')
    add_files(tmp, manifest['files'], CACHE)
    if sha256_file(tmp) != manifest['final_sha256']:
        stop('the finished image does not have the SHA-256 of the release')
    tmp.replace(out)
    say(f'  work/system.raw: ready, SHA-256 of the release correct')
    return out, manifest


# ------------------------------------------------------------------ U-Boot over USB

class Device:
    """The Car Thing in USB burn mode, with raw eMMC reads and writes (user area)."""

    def __init__(self, sbd, dev=None):
        self.sbd = sbd
        # One USB handle only: a second handle to the same device makes the commands collide,
        # and U-Boot then stops answering until a power cycle.
        self.dev = dev or sbd.SuperbirdDevice()
        fd, self.tmp = tempfile.mkstemp(suffix='.blk')
        os.close(fd)

    def command(self, cmd, timeout=None):
        try:
            return 'success' in self.dev.decode(self.dev.device.bulkCmd(cmd, timeout=timeout))
        except Exception:
            return False

    def read(self, offset, size):
        """Read eMMC bytes (at most BLOCK). Return None on failure."""
        aligned = -(-size // MIB) * MIB
        for _ in range(RETRIES):
            if self.command('mmc dev 1 0') and \
                    self.command(f'mmc read {hex(VERIFY)} {hex(offset // SECTOR)} {hex(aligned // SECTOR)}',
                                 MMC_TIMEOUT) and \
                    self.command(f'upload mem {hex(VERIFY)} normal {hex(aligned)}'):
                try:
                    # superbird-tool prints a progress line for each 64 KiB; keep the output short.
                    with contextlib.redirect_stdout(io.StringIO()):
                        self.dev._fast_read_to_file('raw', aligned, VERIFY, self.tmp)
                    data = Path(self.tmp).read_bytes()
                    if len(data) == aligned:
                        return data[:size]
                except Exception:
                    self.dev._fast_reopen()
            time.sleep(1)
        return None

    def read_ram(self, address, size):
        """Read U-Boot RAM (at most BLOCK) through the upload protocol. Return None on failure."""
        aligned = -(-size // MIB) * MIB
        if not self.command(f'upload mem {hex(address)} normal {hex(aligned)}'):
            return None
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                self.dev._fast_read_to_file('raw', aligned, address, self.tmp)
            data = Path(self.tmp).read_bytes()
            return data[:size] if len(data) == aligned else None
        except Exception:
            self.dev._fast_reopen()
            return None

    def read_small(self, offset, size):
        """Read a few sectors through readMemory (no upload protocol). The caller checks the data
        (as sbt-raw-write.py does): the status of "mmc read" differs between the U-Boots."""
        self.command('mmc dev 1 0')
        self.command(f'mmc read {hex(VERIFY)} {hex(offset // SECTOR)} {hex(-(-size // SECTOR))}')
        return self.dev.read_memory(VERIFY, size)

    def write(self, offset, data):
        """Write one block, read it back, and compare. Stop on failure."""
        for attempt in range(1, RETRIES + 1):
            self.dev.device.writeLargeMemory(BUFFER, data, 4096, True)
            self.command('mmc dev 1 0')
            if not self.command(f'mmc write {hex(BUFFER)} {hex(offset // SECTOR)} {hex(len(data) // SECTOR)}',
                                MMC_TIMEOUT):
                stop(f'{hex(offset)}: mmc write did not report success')
            if self.read(offset, len(data)) == data:
                return
            say(f'  {hex(offset)}: read-back differs (attempt {attempt})')
        stop(f'{hex(offset)}: read-back differs after {RETRIES} writes')

    def close(self):
        self.command('mmc dev 1 0')
        os.unlink(self.tmp)
        try:
            import usb.util
            usb.util.dispose_resources(self.dev.device.dev)
        except Exception:
            pass


def usb_present():
    """True while a Car Thing is on the USB bus (any mode). It does not read the product string,
    so a USB error cannot look like a disconnected Car Thing."""
    try:
        import usb.core
        return any(usb.core.find(idVendor=v, idProduct=p) is not None for v, p in USB_IDS)
    except Exception:
        return True


def wait_for_unplug():
    say()
    say('Disconnect the Car Thing now (unplug the USB cable).')
    absent = None
    for i in range(480):
        if usb_present():
            absent = None
        elif absent is None:
            absent = time.time()
        elif time.time() - absent >= 1.0:
            say('  disconnected')
            return
        if i and i % 60 == 0:
            say('  still waiting ...')
        time.sleep(0.25)
    stop('the Car Thing is still connected. Unplug the USB cable, and run the same command again.')


def wait_for_device(sbd, modes, text):
    say()
    say(text)
    hint = True
    for i in range(240):
        mode = sbd.find_device(silent=True)
        if mode in modes:
            return mode
        if mode == 'normal' and hint and 'normal' not in modes:
            say('  The Car Thing started its normal software. Unplug it. On a Car Thing that has not\n'
                '  had the first install, hold preset buttons 1 and 4 while you connect it.')
            hint = False
        if i and i % 30 == 0:
            say('  still waiting ...')
        time.sleep(0.5)
    stop('no Car Thing in USB mode. Check the cable (directly to the computer, no hub).')


def connect(sbd, strict=True):
    """Connect in USB burn mode. Return (Device, trusted, MPT entries). trusted: the table comes from
    the Car Thing's own U-Boot. A table with fip_a/fip_b can come from superbird-tool's U-Boot (after a
    mask-ROM entry, also from an earlier run without a power cycle), so only reconnect_own() makes
    it trusted. A table without fip_a/fip_b always comes from the Car Thing's own (older) U-Boot."""
    mode = sbd.find_device(silent=True)
    if mode not in ('usb', 'usb-burn'):
        mode = wait_for_device(sbd, ('usb', 'usb-burn'), (
            'Connect the Car Thing in USB mode:\n'
            '  1. Unplug the Car Thing.\n'
            f'  2. {PRODUCT} already installed: hold preset button 4 (top row, right).\n'
            '     First install: hold preset buttons 1 and 4 (top row, left and right).\n'
            '  3. Connect the USB cable directly to this computer, keep holding for 3 s, release.'))
    burn = None
    if mode == 'usb-burn':
        say('  found: USB burn mode')
    if mode == 'usb':
        say('  found: mask-ROM mode')
        # superbird-tool's U-Boot writes its own partition table (MPT) at 36 MiB when it starts.
        # The stock U-Boot writes the stock one back, but on a Car Thing with a GPT firmware that
        # area belongs to another partition. So ask before anything touches the eMMC.
        ask('The Car Thing is in mask-ROM mode. The tool now loads a USB U-Boot into its RAM.\n'
            + OTHER_FIRMWARE)
        say("  load superbird-tool's U-Boot into RAM (USB burn mode)")
        burn = sbd.enter_burn_mode(sbd.SuperbirdDevice())
        if burn is None:
            stop('the Car Thing did not enter USB burn mode')
        say('  USB burn mode: ready')
    dev = Device(sbd, burn)
    entries = read_mpt(dev)
    trusted = mode == 'usb-burn' and not has_fip(entries)
    layout = layout_of(entries)
    if strict and (layout == 'other' or (trusted and layout != 'ok')):
        stop(other_layout_text(entries))
    return dev, trusted, entries


def reconnect_own(sbd, dev):
    """Close dev, and connect again after a power cycle, in the burn mode of the Car Thing's own
    U-Boot (preset 4). Only that U-Boot's partition table shows where the partitions really are.
    Return (Device, MPT entries)."""
    dev.close()
    say()
    say("To check the partition table of the Car Thing's own U-Boot, the tool needs a new connection.")
    wait_for_unplug()
    mode = wait_for_device(sbd, ('usb-burn', 'usb'), (
        'Connect the Car Thing in the burn mode of its own U-Boot:\n'
        '  1. Hold preset button 4 (top row, right end).\n'
        '  2. Connect the USB cable directly to this computer, keep holding for 3 s, release.'))
    if mode != 'usb-burn':
        stop('the Car Thing is in mask-ROM mode (buttons 1 and 4?). Hold only preset 4.')
    time.sleep(2)
    dev = Device(sbd)
    entries = read_mpt(dev)
    if layout_of(entries) != 'ok':
        stop(other_layout_text(entries))
    say("  found: USB burn mode of the Car Thing's own U-Boot")
    say(f'  partition table: {table_kind(entries)}; system_b at {hex(SYSTEM_B[0])} ({SYSTEM_B[1] // MIB} MiB): '
        'supported')
    return dev, entries


def has_fip(entries):
    return 'fip_a' in entries or 'fip_b' in entries


def layout_of(entries):
    """'ok': system_b is where the tool writes. 'shifted': superbird-tool's table on a Car Thing with
    the older U-Boot (the real system_b is then also where the tool writes). 'other': unknown."""
    if entries.get('system_b') == SYSTEM_B:
        return 'ok'
    if has_fip(entries) and entries.get('system_b') == (SYSTEM_B[0] + FIP_SHIFT, SYSTEM_B[1]):
        return 'shifted'
    return 'other'


def table_kind(entries):
    return ('newer Spotify layout, with fip_a/fip_b' if has_fip(entries)
            else 'older Spotify layout, without fip_a/fip_b')


def other_layout_text(entries):
    where = entries.get('system_b')
    where = f'{hex(where[0])} ({where[1] // MIB} MiB)' if where else 'not in the table'
    return (f'system_b is at {where}, but {PRODUCT} needs it at {hex(SYSTEM_B[0])} '
            f'({SYSTEM_B[1] // MIB} MiB): this Car Thing has another partition layout. The tool did not\n'
            'write system_b. "python3 flash.py check" shows the table; please report it.')


def read_mpt(dev):
    """The Amlogic partition table (MPT) at 36 MiB: {name: (offset, size)}."""
    data = dev.read_small(MPT_OFFSET, 8 * SECTOR)
    if not data or data[:3] != b'MPT':
        gpt = dev.read_small(SECTOR, SECTOR)
        if gpt and gpt[:8] == b'EFI PART':
            stop('this Car Thing has a GPT partition table, so it runs another firmware.\n'
                 + OTHER_FIRMWARE + '\nNothing was written by this tool.')
        stop('no partition table (MPT) at 36 MiB. Nothing was written by this tool.')
    count, = struct.unpack_from('<I', data, 16)
    entries = {}
    for i in range(min(count, 32)):
        name, size, offset, _mask = struct.unpack_from('<16sQQI', data, 24 + i * 40)
        entries[name.split(b'\0')[0].decode(errors='replace')] = (offset, size)
    return entries


def read_env(dev):
    data = dev.read(ENV_OFFSET, ENV_SIZE)
    if data is None:
        stop('cannot read the U-Boot env')
    crc, = struct.unpack_from('<I', data)
    if zlib.crc32(data[4:]) & 0xffffffff != crc:
        stop('the U-Boot env has a wrong CRC')
    env = {}
    for item in data[4:].split(b'\0'):
        if not item:
            break
        key, _, value = item.decode(errors='replace').partition('=')
        env[key] = value
    return env


def env_state(env):
    if env.get('active_slot') != '_a':
        stop(f'the stock software runs from slot {env.get("active_slot")!r}, not "_a". {PRODUCT} '
             'uses slot B, so it would overwrite the stock software. Nothing was written.')
    if env.get('bootcmd') == BOOTCMD and env.get('cpt_boot') == CPT_BOOT:
        return 'installed'
    if env.get('bootcmd') == BOOTCMD_STOCK and 'cpt_boot' not in env:
        return 'stock'
    stop(f'unknown boot command: bootcmd={env.get("bootcmd")!r}. Nothing was written.\n'
         + OTHER_FIRMWARE)


def run_env_commands(dev, commands):
    for command in commands:
        if len(command) > BULKCMD_MAX:
            stop(f'U-Boot command too long: {command}')
    if not dev.command('amlmmc env'):
        stop('amlmmc env failed')
    for command in commands:
        if not dev.command(command):
            stop(f'U-Boot command failed: {command}')
    if not dev.command('env save'):
        stop('env save failed')


# ------------------------------------------------------------------ backup and writes

def dump(dev, path, offset, length):
    bar = Progress(path.name, length)
    with open(path, 'wb') as out:
        done = 0
        while done < length:
            size = min(BLOCK, length - done)
            data = dev.read(offset + done, size)
            if data is None:
                stop(f'read failed at {hex(offset + done)}')
            out.write(data)
            done += size
            bar.update(done)
    bar.end(f'{length // MIB} MiB' if length >= MIB else f'{length // 1024} KiB')


def user_area_size(dev):
    """The eMMC user area size: the end of the last readable sector (binary search)."""
    low, high = 1 << 30, 1 << 36   # readable, not readable (bytes)
    while high - low > SECTOR:
        mid = (low + high) // 2 // SECTOR * SECTOR
        dev.command('mmc dev 1 0')
        if dev.command(f'mmc read {hex(VERIFY)} {hex(mid // SECTOR)} 0x1'):
            low = mid
        else:
            high = mid
    return high


def backup(dev, env, full):
    folder = BACKUPS / datetime.now().strftime('%Y-%m-%d-%H%M%S')
    folder.mkdir(parents=True)
    need = (4.5 if full else 0.6) * 2**30
    if shutil.disk_usage(folder).free < need:
        stop(f'not enough free disk space for the backup ({need / 2**30:.1f} GiB)')
    say(f'  backup to {folder}')
    (folder / 'env.txt').write_text(''.join(f'{k}={v}\n' for k, v in env.items()))
    dump(dev, folder / 'env-region.img', ENV_OFFSET, ENV_REGION)
    dump(dev, folder / 'system_b.img', *SYSTEM_B)
    if full:
        size = user_area_size(dev)
        say(f'  eMMC user area: {size // MIB} MiB (the 1 MiB at 36 MiB, the partition table, '
            'cannot be read and is zeros)')
        bar = Progress('emmc-user.img', size)
        with open(folder / 'emmc-user.img', 'wb') as out:
            done = 0
            while done < size:
                part = min(BLOCK, size - done)
                data = dev.read(done, part)
                if data is None:
                    data = b''.join(dev.read(done + p, MIB) or bytes(MIB) for p in range(0, part, MIB))
                out.write(data)
                done += part
                bar.update(done)
        bar.end(f'{size // MIB} MiB')
    say('  SHA-256 of the backup files')
    sums = ''.join(f'{sha256_file(p)}  {p.name}\n' for p in sorted(folder.iterdir()) if p.suffix == '.img')
    (folder / 'SHA256SUMS').write_text(sums)
    say(f'  backup complete. Keep this folder: "flash.py restore {folder}" needs it.')
    return folder


def write_file(dev, path, offset, label):
    """Write a file at an eMMC offset in 16 MiB blocks. Skip blocks that are already the same."""
    size = path.stat().st_size
    written = same = done = 0
    bar = Progress(label, size)
    with open(path, 'rb') as f:
        while done < size:
            data = f.read(BLOCK)
            if dev.read(offset + done, len(data)) == data:
                same += 1
            else:
                dev.write(offset + done, data)
                written += 1
            done += len(data)
            bar.update(done)
    bar.end(f'{size // MIB} MiB, {blocks(written)} written and read back, {blocks(same)} already the same')


def write_image(dev, system_raw):
    global PHASE
    if system_raw.stat().st_size > BOOT_AREA_OFFSET:
        stop('system.raw is larger than its region')
    say('  Do not disconnect the Car Thing until the tool says DONE.')
    PHASE = 'write'
    write_file(dev, system_raw, SYSTEM_B[0], 'system')
    # The boot area last: until it is complete, the boot command falls back to the stock boot.
    write_file(dev, HERE_IMAGES / 'boot-area.img', SYSTEM_B[0] + BOOT_AREA_OFFSET, 'boot area')


# ------------------------------------------------------------------ diag: a read-only ext4 reader

BOOT_A_OFFSET = 0xd600000    # boot_a: the same place with the older and the newer U-Boot
SYSTEM_A_OFFSET = 0x10600000  # system_a (the stock root file system): the same with both
DATA_OFFSET = 0x63616000     # data: the same place with both (data-setup.sh checks it)


class ChunkReader:
    """Bytes of the eMMC from base on, with a cache of 1 MiB chunks (each USB read costs time)."""

    def __init__(self, dev, base):
        self.dev, self.base, self.cache = dev, base, {}

    def read(self, offset, size):
        out = bytearray()
        pos = self.base + offset
        while size > 0:
            chunk = pos // MIB
            if chunk not in self.cache:
                if len(self.cache) >= 64:
                    self.cache.clear()
                data = self.dev.read(chunk * MIB, MIB)
                if data is None:
                    raise IOError(f'eMMC read failed at {hex(chunk * MIB)}')
                self.cache[chunk] = data
            start = pos - chunk * MIB
            part = min(size, MIB - start)
            out += self.cache[chunk][start:start + part]
            pos += part
            size -= part
        return bytes(out)


class FileSource:
    """A file in an ext4 file system as a byte source (for the ext4 image in userdata.img)."""

    def __init__(self, fs, ino):
        self.fs, self.runs, self.size = fs, fs.runs(ino), ino['size']

    def read(self, offset, size):
        out = bytearray()
        while size > 0:
            lblock, inside = divmod(offset, self.fs.bs)
            part = min(size, self.fs.bs - inside)
            phys = self.fs.physical(self.runs, lblock)
            out += (bytes(part) if phys is None else
                    self.fs.read(phys * self.fs.bs + inside, part))
            offset += part
            size -= part
        return bytes(out)


class Ext4:
    """Enough of ext4 to list folders and read files: extents (and old block maps), 64-bit. After
    a power cut, the newest metadata is in the journal: the reader replays it in memory."""

    def __init__(self, src):
        self.src = src
        self.overlay = {}          # file system block -> its newest copy in the journal
        sb = src.read(1024, 1024)
        if struct.unpack_from('<H', sb, 56)[0] != 0xEF53:
            raise ValueError('no ext4 file system')
        self.bs = 1024 << struct.unpack_from('<I', sb, 24)[0]
        self.ipg = struct.unpack_from('<I', sb, 40)[0]
        self.inode_size = struct.unpack_from('<H', sb, 88)[0] or 128
        incompat = struct.unpack_from('<I', sb, 96)[0]
        self.desc_size = (struct.unpack_from('<H', sb, 254)[0] or 32) if incompat & 0x80 else 32
        self.gdt = (struct.unpack_from('<I', sb, 20)[0] + 1) * self.bs
        self.label = sb[120:136].split(b'\0')[0].decode(errors='replace')
        self.clean = bool(struct.unpack_from('<H', sb, 58)[0] & 1)
        self.mount_time, self.write_time = struct.unpack_from('<II', sb, 44)
        self.journal_needs_recovery = bool(incompat & 0x4)
        self.replayed = 0
        if self.journal_needs_recovery and struct.unpack_from('<I', sb, 92)[0] & 0x4:
            self._replay(struct.unpack_from('<I', sb, 224)[0])

    def read(self, offset, size):
        """Bytes of the file system, with the replayed journal blocks in place."""
        if not self.overlay:
            return self.src.read(offset, size)
        out = bytearray()
        while size > 0:
            blk, inside = divmod(offset, self.bs)
            part = min(size, self.bs - inside)
            if blk in self.overlay:
                out += self.overlay[blk][inside:inside + part]
            else:
                out += self.src.read(offset, part)
            offset += part
            size -= part
        return bytes(out)

    def _replay(self, journal_inode):
        """Read the jbd2 journal like a recovery does, and keep the result in self.overlay."""
        journal = FileSource(self, self.inode(journal_inode))
        jblock = lambda n: journal.read(n * self.bs, self.bs)
        jsb = jblock(0)
        magic, kind = struct.unpack_from('>II', jsb, 0)
        if magic != 0xC03B3998 or kind not in (3, 4):
            return
        maxlen, first, sequence, start = struct.unpack_from('>IIII', jsb, 16)
        incompat = struct.unpack_from('>I', jsb, 40)[0] if kind == 4 else 0
        if not start:
            return
        csum3, csum2, wide = incompat & 0x10, incompat & 0x8, incompat & 0x2
        tag_size = 16 if csum3 else (8 + (2 if csum2 else 0) + (4 if wide else 0))
        tail = 4 if (csum2 or csum3) else 0
        nxt = lambda n: first if n + 1 >= maxlen else n + 1
        committed, pending, revoked = [], [], {}
        pos, expect = start, sequence
        for _ in range(maxlen):
            head = jblock(pos)
            magic, kind, seq = struct.unpack_from('>III', head, 0)
            if magic != 0xC03B3998 or seq != expect:
                break
            if kind == 1:                                    # descriptor: tags, then the blocks
                off, data_pos = 12, nxt(pos)
                while off + tag_size <= self.bs - tail:
                    if csum3:
                        lo, flags, hi = struct.unpack_from('>III', head, off)
                    else:
                        lo, = struct.unpack_from('>I', head, off)
                        flags, = struct.unpack_from('>H', head, off + 6)
                        hi = struct.unpack_from('>I', head, off + 8)[0] if wide else 0
                    pending.append(((hi << 32 if wide else 0) | lo, data_pos, flags & 1))
                    data_pos = nxt(data_pos)
                    off += tag_size + (0 if flags & 2 else 16)   # 16-byte UUID unless SAME_UUID
                    if flags & 8:                                 # LAST_TAG
                        break
                pos = data_pos
            elif kind == 2:                                  # commit
                committed.append((seq, pending))
                pending, expect, pos = [], expect + 1, nxt(pos)
            elif kind == 5:                                  # revoke: older copies must not return
                count, = struct.unpack_from('>I', head, 12)
                rsize = 8 if wide else 4
                for off in range(16, min(count, self.bs), rsize):
                    blk = int.from_bytes(head[off:off + rsize], 'big')
                    revoked[blk] = max(revoked.get(blk, -1), seq)
                pos = nxt(pos)
            else:
                break
        for seq, blocks in committed:
            for blk, where, escaped in blocks:
                if revoked.get(blk, -1) >= seq:
                    continue
                data = bytearray(jblock(where))
                if escaped:
                    data[0:4] = b'\xc0\x3b\x39\x98'
                self.overlay[blk] = bytes(data)
        self.replayed = len(committed)

    def inode(self, n):
        group, index = divmod(n - 1, self.ipg)
        d = self.read(self.gdt + group * self.desc_size, self.desc_size)
        table = struct.unpack_from('<I', d, 8)[0]
        if self.desc_size >= 64:
            table |= struct.unpack_from('<I', d, 0x28)[0] << 32
        raw = self.read(table * self.bs + index * self.inode_size, 128)
        return {'n': n, 'mode': struct.unpack_from('<H', raw, 0)[0],
                'size': struct.unpack_from('<I', raw, 4)[0] | (struct.unpack_from('<I', raw, 108)[0] << 32),
                'mtime': struct.unpack_from('<I', raw, 16)[0], 'flags': struct.unpack_from('<I', raw, 32)[0],
                'iblock': raw[40:100]}

    def runs(self, ino):
        """[(first logical block, first physical block or None for zeros, count)], sorted."""
        if ino['flags'] & 0x10000000:
            raise ValueError('inline data is not supported')
        out = []
        if ino['flags'] & 0x80000:
            self._extents(ino['iblock'], out)
        else:
            ptrs = list(struct.unpack('<15I', ino['iblock']))
            per = self.bs // 4
            blocks = [(i, b) for i, b in enumerate(ptrs[:12])]
            if ptrs[12]:
                blocks += [(12 + i, b) for i, b in enumerate(struct.unpack(f'<{per}I', self.block(ptrs[12])))]
            if ptrs[13]:
                for j, ind in enumerate(struct.unpack(f'<{per}I', self.block(ptrs[13]))):
                    if ind:
                        blocks += [(12 + per + j * per + i, b) for i, b in
                                   enumerate(struct.unpack(f'<{per}I', self.block(ind)))]
            out = [(lb, pb or None, 1) for lb, pb in blocks]
        return sorted(out, key=lambda r: r[0])

    def _extents(self, node, out):
        magic, entries, _max, depth = struct.unpack_from('<HHHH', node, 0)
        if magic != 0xF30A:
            raise ValueError('bad extent header')
        for k in range(entries):
            e = node[12 + 12 * k:24 + 12 * k]
            if depth == 0:
                lblock, length, hi, lo = struct.unpack('<IHHI', e)
                start = (hi << 32) | lo
                if length > 32768:            # an uninitialized extent reads as zeros
                    out.append((lblock, None, length - 32768))
                else:
                    out.append((lblock, start, length))
            else:
                _lblock, lo, hi, _ = struct.unpack('<IIHH', e)
                self._extents(self.block((hi << 32) | lo), out)

    def block(self, n):
        return self.read(n * self.bs, self.bs)

    @staticmethod
    def physical(runs, lblock):
        for first, phys, count in runs:
            if first <= lblock < first + count:
                return None if phys is None else phys + lblock - first
        return None

    def read_file(self, ino, limit=None):
        size = ino['size'] if limit is None else min(ino['size'], limit)
        return FileSource(self, ino).read(0, size)

    def listdir(self, ino):
        data, out = self.read_file(ino), {}
        for base in range(0, len(data), self.bs):
            pos = base
            while pos + 8 <= base + self.bs and pos + 8 <= len(data):
                child, rec_len, name_len, _ftype = struct.unpack_from('<IHBB', data, pos)
                if rec_len < 8:
                    break
                if child and name_len:
                    out[data[pos + 8:pos + 8 + name_len].decode(errors='replace')] = child
                pos += rec_len
        return out

    def lookup(self, path):
        ino = self.inode(2)
        for part in [p for p in path.split('/') if p]:
            entries = self.listdir(ino)
            if part not in entries:
                return None
            ino = self.inode(entries[part])
        return ino


def fdt_nodes(blob):
    """{node path: {property: bytes}} of a flattened device tree."""
    _magic, _total, off_struct, off_strings = struct.unpack_from('>IIII', blob, 0)
    pos, stack, nodes = off_struct, [], {}
    while pos < len(blob):
        tok, = struct.unpack_from('>I', blob, pos)
        pos += 4
        if tok == 1:
            end = blob.index(b'\0', pos)
            stack.append(blob[pos:end].decode(errors='replace'))
            nodes.setdefault('/'.join(stack) or '/', {})
            pos = (end + 4) & ~3
        elif tok == 2:
            stack.pop()
        elif tok == 3:
            length, nameoff = struct.unpack_from('>II', blob, pos)
            name = blob[off_strings + nameoff:blob.index(b'\0', off_strings + nameoff)].decode(errors='replace')
            nodes['/'.join(stack) or '/'][name] = blob[pos + 8:pos + 8 + length]
            pos = (pos + 8 + length + 3) & ~3
        elif tok == 9:
            break
    return nodes


def utc(t):
    return datetime.fromtimestamp(t, timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC') if t else '-'


def is_dir(ino):
    return ino['mode'] & 0xF000 == 0x4000


# ------------------------------------------------------------------ commands

INSTALL_STEPS = [
    "Tools: superbird-tool's USB code",
    'Image: download the files that this folder does not contain, and make work/system.raw',
    'Connect: the Car Thing in USB mode',
    'Check: partition table, slot, and boot command (reads only), then a question',
    'First install only: a backup (about 6 minutes), a check of the partition table, and the boot command',
    'Write: the image to system_b (about 3 minutes), each block read back',
]
RESTORE_STEPS = [
    "Tools: superbird-tool's USB code",
    "Connect: the Car Thing in the burn mode of its own U-Boot (preset 4)",
    'Check: partition table, slot, and boot command (reads only), then a question',
    'Boot command: the stock one',
    'Write: system_b from the backup (about 3 minutes), each block read back',
]


def show_state(trusted, entries, env, state, restore=False):
    if trusted:
        say(f"  partition table (the Car Thing's own U-Boot): {table_kind(entries)}; system_b at "
            f'{hex(SYSTEM_B[0])}: supported')
    else:
        say('  partition table: it can come from a U-Boot in RAM (superbird-tool\'s). Before it writes,')
        say("                   the tool checks the table of the Car Thing's own U-Boot: it asks you")
        say('                   to connect again with preset 4.')
    say(f'  stock software: slot {env["active_slot"][-1].upper()}')
    say('  USB burn mode with preset 4 at power-on: '
        + ('yes' if BURN_BUTTON.strip() in env.get('storeargs', '') else
           'no' if restore else 'no (the install adds it)'))
    if restore:
        say('  boot command: ' + ('changed by an install' if state == 'installed' else 'stock'))
    elif state == 'installed':
        say('  boot command: changed by an earlier install, so this is an update')
    else:
        say('  boot command: stock, so this is a first install')


def done_text(manifest, backup_folder=None):
    say()
    say(f'DONE. {PRODUCT} {manifest["version"]} is on the Car Thing.')
    if backup_folder:
        say(f'  Keep the backup folder: {backup_folder}')
    say('Next:')
    say('  1. Unplug the Car Thing. Connect it to the USB-C hub with the PD charger.')
    say(f'  2. {PRODUCT} starts in about 1 minute (the first start after an install takes longer).')
    say('  3. If the screen stays black for more than 3 minutes: README.md, "If something goes wrong".')
    say('Buttons at power-on: preset 1 = stock software; preset 4 = USB burn mode (updates, restore).')


def cmd_prepare(args):
    say('== Image: download the files that this folder does not contain, and make work/system.raw')
    prepare(args.images)


def cmd_check(args):
    sbd = load_tools()
    dev, trusted, entries = connect(sbd)
    say('== partition table (MPT at 36 MiB)'
        + (" of the Car Thing's own U-Boot" if trusted else
           ": it can come from superbird-tool's U-Boot in RAM (after a mask-ROM entry, or from an\n"
           "   earlier run without a power cycle). For the Car Thing's own table: disconnect it, hold\n"
           '   preset 4 while you connect it, and run check again'))
    for name, (offset, size) in sorted(entries.items(), key=lambda e: e[1][0]):
        say(f'  {name:10s} {offset / MIB:9.2f} MiB  ' + (f'{size / MIB:8.2f} MiB' if size < 2**40 else 'to the end'))
    layout = layout_of(entries)
    say('  ' + {'ok': f'{table_kind(entries)}: system_b at {hex(SYSTEM_B[0])}, supported',
                'shifted': "superbird-tool's table for a Car Thing with the older Spotify U-Boot",
                'other': 'system_b is not where the tool writes: another layout'}[layout])
    env = read_env(dev)
    say(f'  active_slot={env.get("active_slot")!r}')
    say(f'  bootcmd={env.get("bootcmd")!r}')
    say(f'  burn-mode button (preset 4): {"yes" if BURN_BUTTON.strip() in env.get("storeargs", "") else "no"}')
    state = env_state(env)
    say(f'== state: {"boot command installed: install updates" if state == "installed" else "stock: install is a first install"}')
    say(f'  eMMC user area: {user_area_size(dev) // MIB} MiB')
    dev.close()
    say('Nothing was written.')


DIAG_STEPS = [
    "Tools: superbird-tool's USB code",
    'Connect: the Car Thing in USB burn mode (preset 4 is best; the tool writes nothing)',
    'U-Boot env and partition table',
    'The installed image on system_b (about 1 minute)',
    'The stock device tree (boot_a, decrypted by U-Boot into RAM) and the stock software version',
    f'{PRODUCT} data and Android logs on the stock data partition',
]


def cmd_diag(args):
    manifest = release_files(args.images)
    folder = HERE / 'diag' / datetime.now().strftime('%Y-%m-%d-%H%M%S')
    folder.mkdir(parents=True)
    lines = []

    def note(text=''):
        say(text)
        lines.append(text)

    def section(n, fn):
        step(n)
        lines.append(f'== {STEPS[n - 1]}')
        try:
            fn()
        except Exception as e:     # a failed part must not stop the other parts
            note(f'  could not read this part: {type(e).__name__}: {e}')

    overview(f'{PRODUCT} {manifest["version"]}: diagnostic. Steps:', DIAG_STEPS)
    note(f'Folder: {folder}')
    step(1)
    sbd = load_tools()
    step(2)
    dev, trusted, entries = connect(sbd, strict=False)
    state = {}

    def part3():
        env = read_env(dev)
        state['env'] = env
        (folder / 'env.txt').write_text(''.join(f'{k}={v}\n' for k, v in env.items()))
        table = ''.join(f'{n:10s} {o / MIB:9.2f} MiB {(f"{z / MIB:9.2f} MiB" if z < 2**40 else " to the end")}\n'
                        for n, (o, z) in sorted(entries.items(), key=lambda e: e[1][0]))
        (folder / 'mpt.txt').write_text(table)
        layout = layout_of(entries)
        note(f'  partition table: {"own U-Boot" if trusted else "can be from a U-Boot in RAM"}, '
             f'{table_kind(entries)}, system_b {"where the tool writes" if layout == "ok" else layout}')
        bootcmd = env.get('bootcmd')
        note('  boot command: ' + ('installed by this tool' if bootcmd == BOOTCMD and env.get('cpt_boot') == CPT_BOOT
                                   else 'stock' if bootcmd == BOOTCMD_STOCK else f'other: {bootcmd!r}'))
        for key in ('active_slot', 'panel_type', 'outputmode', 'display_init', 'firstboot', 'fb_addr'):
            note(f'  {key}={env.get(key)!r}')
        note(f'  preset-4 burn mode: {"yes" if BURN_BUTTON.strip() in env.get("storeargs", "") else "no"}')
        note(f'  eMMC user area: {user_area_size(dev) // MIB} MiB')

    def part4():
        for label, offset, size, expected in (
                ('system', SYSTEM_B[0], manifest['image_size'], manifest['final_sha256']),
                ('boot area', SYSTEM_B[0] + BOOT_AREA_OFFSET, BOOT_AREA_SIZE, manifest['boot_area_sha256'])):
            h, done, bar = hashlib.sha256(), 0, Progress(label, size)
            while done < size:
                part = min(BLOCK, size - done)
                data = dev.read(offset + done, part)
                if data is None:
                    raise IOError(f'read failed at {hex(offset + done)}')
                h.update(data)
                done += part
                bar.update(done)
            same = h.hexdigest() == expected
            bar.end(f'{size // MIB} MiB')
            note(f'  {label}: ' + (f'the same as {PRODUCT} {manifest["version"]}' if same else
                                   f'NOT the same as {PRODUCT} {manifest["version"]} (SHA-256 {h.hexdigest()[:16]}...)'))

    def part5():
        if layout_of(entries) != 'ok':
            note('  skipped: the partition table in RAM does not show boot_a where it is')
        elif not dev.command('imgread dtb boot_a 0x1000000', MMC_TIMEOUT):
            note('  imgread dtb boot_a failed')
        else:
            blob = dev.read_ram(0x1000000, MIB)
            if not blob or blob[:4] != b'\xd0\x0d\xfe\xed':
                note('  no device tree in RAM after imgread')
            else:
                blob = blob[:struct.unpack_from('>I', blob, 4)[0]]
                (folder / 'boot_a.dtb').write_bytes(blob)
                nodes = fdt_nodes(blob)
                text = lambda v: v.split(b'\0')[0].decode(errors='replace') if v else None
                root = nodes.get('/', {})
                parts = [text(v.get('pname')) for k, v in nodes.items() if k.startswith('/partitions/') and 'pname' in v]
                note(f'  boot_a.dtb: {len(blob)} bytes, model {text(root.get("model"))!r}, '
                     f'amlogic-dt-id {text(root.get("amlogic-dt-id"))!r}')
                note(f'  device-tree partitions: {", ".join(parts)}')
                panels = sorted(k.split('/')[-1] for k in nodes if k.startswith('/lcd/lcd_'))
                note(f'  panel nodes: {", ".join(panels)}')
                dtbo_overlays(nodes)
        root = Ext4(ChunkReader(dev, SYSTEM_A_OFFSET))
        for path in ('etc/superbird/version', 'etc/sw-versions', 'usr/lib/os-release'):
            ino = root.lookup(path)
            value = root.read_file(ino, 400).decode(errors='replace').strip() if ino and not is_dir(ino) else 'not there'
            note(f'  stock /{path}: ' + ' | '.join(l for l in value.splitlines() if l.strip())[:300])
        hcd = root.lookup('lib/firmware/brcm/BCM20703A2.hcd')
        note('  stock Bluetooth firmware: ' + (f'{hcd["size"]} bytes' if hcd else 'NOT there (PlayThing needs it)'))

    def dtbo_overlays(base):
        """The overlays in dtbo_<slot>, which the newer U-Boot applies at booti when the env has
        androidboot.dtbo_idx (PlayThing's boot script deletes it in RAM)."""
        env = state.get('env', {})
        note(f'  env androidboot.dtbo_idx: {env.get("androidboot.dtbo_idx")!r}')
        name = 'dtbo' + env.get('active_slot', '_a')
        if name not in entries:
            note(f'  no {name} partition')
            return
        offset, size = entries[name]
        data = dev.read(offset, min(size, BLOCK))
        if not data or data[:4] != b'\xd7\xb7\xab\x1e':
            note(f'  {name}: no overlay table')
            return
        (folder / f'{name}.img').write_bytes(data)
        count, entries_offset = struct.unpack_from('>II', data, 16)
        entry_size, = struct.unpack_from('>I', data, 12)
        paths = {int.from_bytes(v['phandle'], 'big'): k for k, v in base.items() if 'phandle' in v}
        note(f'  {name}: {count} overlays (saved as {name}.img)')
        for i in range(min(count, 8)):
            dt_size, dt_offset = struct.unpack_from('>II', data, entries_offset + i * entry_size)
            overlay = fdt_nodes(data[dt_offset:dt_offset + dt_size])
            changes = []
            for path, props in overlay.items():
                if '/__overlay__' not in path:
                    continue
                frag = path.split('/__overlay__')[0]
                fp = overlay.get(frag, {})
                target = (fp['target-path'].split(b'\0')[0].decode(errors='replace') if 'target-path' in fp else
                          paths.get(int.from_bytes(fp.get('target', b'\0' * 4), 'big'), '?'))
                where = target.rstrip('/') + path.split('/__overlay__')[1]
                shown = {k: (v.split(b'\0')[0].decode(errors='replace') if v[-1:] == b'\0' else f'<{len(v)} bytes>')
                         for k, v in props.items() if k != 'phandle'}
                changes.append(f'{where or "/"} {shown}' if shown else f'{where or "/"} (node)')
            note(f'  overlay {i}: ' + ('; '.join(changes)[:1500] or 'empty'))

    def part6():
        outer = Ext4(ChunkReader(dev, DATA_OFFSET))
        note(f'  stock data file system: label {outer.label!r}, last mount {utc(outer.mount_time)}, '
             f'last write {utc(outer.write_time)}'
             + (f' (after a power cut: {outer.replayed} journal transactions replayed in memory)'
                if outer.journal_needs_recovery else ''))
        top = outer.lookup(DATA_FOLDER)
        if top is None or not is_dir(top):
            note(f'  no folder {DATA_FOLDER}/: {PRODUCT} never started far enough to make its /data '
                 '(data-setup.sh), or /data is in RAM')
            return
        listing = []
        for name, n in sorted(outer.listdir(top).items()):
            if name in ('.', '..'):
                continue
            ino = outer.inode(n)
            listing.append(f'{name}  {ino["size"]} bytes  {utc(ino["mtime"])}')
        note(f'  folder {DATA_FOLDER}/: ' + ('; '.join(listing) or 'empty'))
        img = outer.lookup(f'{DATA_FOLDER}/userdata.img')
        if img is None:
            note('  no userdata.img: /data was in RAM or was never made')
            return
        inner = Ext4(FileSource(outer, img))
        note(f'  /data file system: label {inner.label!r}, last mount {utc(inner.mount_time)}, '
             f'last write {utc(inner.write_time)}'
             + (f' (after a power cut: {inner.replayed} journal transactions replayed in memory)'
                if inner.journal_needs_recovery else ''))
        copied = 0
        for path in ('misc/logd', 'tombstones', 'anr', 'misc/bootstat'):
            d = inner.lookup(path)
            if d is None or not is_dir(d):
                note(f'  /data/{path}: not there')
                continue
            entries_d = {n: i for n, i in inner.listdir(d).items() if n not in ('.', '..')}
            note(f'  /data/{path}: {len(entries_d)} files')
            out = folder / 'data' / path
            out.mkdir(parents=True, exist_ok=True)
            for name in sorted(entries_d):
                ino = inner.inode(entries_d[name])
                if is_dir(ino) or ino['size'] > 4 * MIB:
                    continue
                (out / name).write_bytes(inner.read_file(ino))
                copied += ino['size']
        note(f'  copied {copied // 1024} KiB of logs to {folder / "data"}')

    section(3, part3)
    section(4, part4)
    section(5, part5)
    section(6, part6)
    dev.close()
    (folder / 'summary.txt').write_text('\n'.join(lines) + '\n')
    say()
    say(f'DONE. Nothing was written to the Car Thing. The diagnostic folder: {folder}')
    say('  summary.txt, env.txt, mpt.txt, boot_a.dtb: no personal data, you can post them.')
    say('  data/: Android logs with device data (Bluetooth addresses, device names): share it only privately.')


def cmd_install(args):
    global PHASE
    manifest = release_files(args.images)
    overview(f'{PRODUCT} {manifest["version"]}: install or update. Steps:', INSTALL_STEPS)
    step(1)
    sbd = load_tools()
    step(2)
    system_raw, manifest = prepare(args.images)
    step(3)
    dev, trusted, entries = connect(sbd)
    step(4)
    env = read_env(dev)
    state = env_state(env)
    show_state(trusted, entries, env, state)
    again = '' if trusted else ' (after a new connection with preset 4)'
    if state == 'stock':
        ask(f'First install of {PRODUCT} {manifest["version"]}. The tool makes a backup (about 6 minutes),\n'
            f'changes the U-Boot env{again}, and writes the image to system_b (about 3 minutes).\n'
            'You do this at your own risk (README.md, "Warning").')
    else:
        ask(f'Update: the tool writes {PRODUCT} {manifest["version"]} to system_b{again} (about 3 minutes; it\n'
            'skips the blocks that are already the same). Your /data stays. If the Car Thing has a different\n'
            'image now, Android deletes the data of the apps that this image does not have (README.md, "Update").')
    folder = None
    if state == 'stock':
        step(5, 'Backup, partition table, and boot command (first install)')
        folder = backup(dev, env, full=not args.no_full_backup)
        if not trusted:
            # Check the real partition table before the boot command changes. The new connection
            # needs the preset-4 burn mode, so add only that first (superbird-tool's
            # --enable_burn_mode_button makes the same change).
            if BURN_BUTTON.strip() not in env.get('storeargs', ''):
                say('  U-Boot env: the preset-4 USB burn mode')
                PHASE = 'button'
                run_env_commands(dev, ['setenv storeargs "${storeargs}' + BURN_BUTTON + '"'])
                if BURN_BUTTON.strip() not in read_env(dev).get('storeargs', ''):
                    stop('the env does not have the preset-4 burn mode after "env save"')
                say('  preset-4 USB burn mode added and read back')
            dev, entries = reconnect_own(sbd, dev)
            trusted = True
            env = read_env(dev)
            if env_state(env) != 'stock':
                stop('the boot command changed during the new connection. Nothing more was written.')
        say('  U-Boot env: the boot command')
        commands = [f'setenv cpt_boot "{CPT_BOOT}"', f'setenv bootcmd "{BOOTCMD}"']
        if BURN_BUTTON.strip() not in env.get('storeargs', ''):
            commands.insert(0, 'setenv storeargs "${storeargs}' + BURN_BUTTON + '"')
        PHASE = 'env'
        run_env_commands(dev, commands)
        new = read_env(dev)
        if env_state(new) != 'installed' or BURN_BUTTON.strip() not in new.get('storeargs', ''):
            stop('the env does not have the new values after "env save"')
        say('  boot command changed and read back (preset 1 at power-on: stock software)')
    elif args.backup:
        step(5, 'Backup of system_b (--backup)')
        backup(dev, env, full=False)
    else:
        step(5, 'Backup and boot command: not necessary for an update')
    step(6)
    if not trusted:
        dev, entries = reconnect_own(sbd, dev)
    write_image(dev, system_raw)
    dev.close()
    PHASE = 'done'
    done_text(manifest, folder)


def cmd_restore(args):
    global PHASE
    folder = Path(args.backup).resolve()
    image = folder / 'system_b.img'
    sums = (folder / 'SHA256SUMS').read_text() if (folder / 'SHA256SUMS').exists() else ''
    if not image.exists() or f'{sha256_file(image)}  system_b.img' not in sums:
        stop(f'{image} is missing or does not match SHA256SUMS')
    if image.stat().st_size != SYSTEM_B[1]:
        stop('system_b.img has the wrong size')
    overview(f'{PRODUCT}: restore the stock software. Steps:', RESTORE_STEPS)
    say(f'Backup: {folder} (system_b.img, SHA-256 correct)')
    step(1)
    sbd = load_tools()
    step(2)
    dev, trusted, entries = connect(sbd)
    if not trusted:
        dev, entries = reconnect_own(sbd, dev)
    step(3)
    env = read_env(dev)
    state = env_state(env)
    show_state(True, entries, env, state, restore=True)
    if state == 'installed':
        ask('Restore: the tool writes the stock boot command, then system_b from the backup (about 3\n'
            'minutes). Preset 4 at power-on keeps USB burn mode.')
        step(4)
        PHASE = 'restore'
        run_env_commands(dev, ['setenv cpt_boot', f'setenv bootcmd "{BOOTCMD_STOCK}"'])
        if env_state(read_env(dev)) != 'stock':
            stop('the env does not have the stock boot command after "env save"')
        say('  stock boot command written and read back')
    else:
        ask('The boot command is already the stock one (for example, an earlier restore stopped).\n'
            'The tool writes only system_b from the backup (about 3 minutes).')
        step(4, 'Boot command: already the stock one')
        PHASE = 'restore'
    step(5)
    say('  Do not disconnect the Car Thing until the tool says DONE.')
    write_file(dev, image, SYSTEM_B[0], 'system_b')
    dev.close()
    PHASE = 'done'
    say()
    say('DONE. The Car Thing starts the stock software. Unplug it and connect it to its power supply.')
    say(f'{PRODUCT}\'s data stays in the folder "{DATA_FOLDER}" of the stock data partition (a 1 GiB')
    say('file). The stock software does not use it.')


HERE_IMAGES = HERE


def main():
    global HERE_IMAGES
    parser = argparse.ArgumentParser(description=f'{PRODUCT} flash tool for the Spotify Car Thing.')
    parser.add_argument('--images', type=Path, default=HERE,
                        help='folder with manifest.json, system.raw.xz, boot-area.img (default: here)')
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('install', help=f'install {PRODUCT}, or update it')
    p.add_argument('--no-full-backup', action='store_true',
                   help='first install: back up only the env and system_b, not the full eMMC')
    p.add_argument('--backup', action='store_true', help='update: back up system_b first')
    p = sub.add_parser('restore', help='write the stock boot command and system_b back')
    p.add_argument('backup', help='backup folder of the first install (backups/...)')
    sub.add_parser('prepare', help='only make the image, no device')
    sub.add_parser('check', help="read the Car Thing's state, write nothing")
    sub.add_parser('diag', help='collect a diagnostic folder (diag/), write nothing')
    args = parser.parse_args()
    if sys.platform not in ('darwin', 'linux'):
        stop('this tool runs on macOS and Linux only. Windows is not supported: use a Mac or a Linux '
             'computer.')
    args.images = args.images.resolve()
    HERE_IMAGES = args.images
    if args.command != 'prepare':
        bootstrap()
    {'install': cmd_install, 'restore': cmd_restore, 'prepare': cmd_prepare,
     'check': cmd_check, 'diag': cmd_diag}[args.command](args)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        stop('interrupted.' + (' The tool wrote nothing to the Car Thing.' if PHASE == 'read' else ''))
