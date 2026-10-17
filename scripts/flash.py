#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""PlayThing flash tool for the Spotify Car Thing (docs/release.md).

  python3 flash.py install           install PlayThing, or update it
  python3 flash.py restore BACKUP    remove PlayThing: write system_b and the boot command back
  python3 flash.py prepare           only make the image (no device)
  python3 flash.py check             read the Car Thing's state, write nothing

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
from datetime import datetime
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


def connect(sbd, need_stock_uboot):
    """Return (Device, layout) in USB burn mode. layout is 'stock' or 'superbird-tool'."""
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
        say("  found: USB burn mode of the Car Thing's own U-Boot")
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
    layout = mpt_layout(dev)
    if need_stock_uboot and layout != 'stock':
        dev = reconnect_stock(sbd, dev)
        layout = 'stock'
    return dev, layout


def reconnect_stock(sbd, dev):
    """Close dev, and connect again in the burn mode of the Car Thing's own U-Boot."""
    dev.close()
    wait_for_device(sbd, ('not-found', 'normal'), 'Unplug the Car Thing now.')
    mode = wait_for_device(sbd, ('usb-burn', 'usb'), (
        'The next step needs the burn mode of the Car Thing\'s own U-Boot:\n'
        '  1. Hold preset button 4 (top row, right).\n'
        '  2. Connect the USB cable, keep holding for 3 s, release.'))
    if mode != 'usb-burn':
        stop('the Car Thing is in mask-ROM mode (buttons 1 and 4?). Hold only preset 4.')
    time.sleep(2)
    dev = Device(sbd)
    if mpt_layout(dev) != 'stock':
        stop('the partition table is still the superbird-tool one')
    say("  found: USB burn mode of the Car Thing's own U-Boot, stock partition table")
    return dev


def mpt_layout(dev):
    """'stock' when the MPT has the stock layout, 'superbird-tool' when it has fip_a/fip_b."""
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
    if 'fip_a' in entries or 'fip_b' in entries:
        return 'superbird-tool'
    if entries.get('system_b') != SYSTEM_B:
        stop(f'system_b is at {entries.get("system_b")}, expected {SYSTEM_B}: '
             'this Car Thing has another layout. Nothing was written.')
    return 'stock'


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


# ------------------------------------------------------------------ commands

INSTALL_STEPS = [
    "Tools: superbird-tool's USB code",
    'Image: download the files that this folder does not contain, and make work/system.raw',
    'Connect: the Car Thing in USB mode',
    'Check: partition table, slot, and boot command (reads only), then a question',
    'First install only: a backup (about 6 minutes) and the boot command',
    'Write: the image to system_b (about 3 minutes), each block read back',
]
RESTORE_STEPS = [
    "Tools: superbird-tool's USB code",
    "Connect: the Car Thing in the burn mode of its own U-Boot (preset 4)",
    'Check: partition table, slot, and boot command (reads only), then a question',
    'Boot command: the stock one',
    'Write: system_b from the backup (about 3 minutes), each block read back',
]


def show_state(layout, env, state, restore=False):
    if layout == 'stock':
        say(f'  partition table: stock layout (system_b at {hex(SYSTEM_B[0])}, {SYSTEM_B[1] // MIB} MiB)')
    else:
        say("  partition table: superbird-tool's layout (its U-Boot is in RAM; the stock U-Boot writes")
        say('                   the stock one back). Before the write, the tool asks you to connect')
        say('                   again with preset 4.')
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
    dev, layout = connect(sbd, need_stock_uboot=False)
    say(f'== partition table: {layout} layout'
        + (' (superbird-tool U-Boot in RAM; the stock U-Boot writes the stock table back)'
           if layout != 'stock' else ''))
    env = read_env(dev)
    say(f'  active_slot={env.get("active_slot")!r}')
    say(f'  bootcmd={env.get("bootcmd")!r}')
    say(f'  burn-mode button (preset 4): {"yes" if BURN_BUTTON.strip() in env.get("storeargs", "") else "no"}')
    state = env_state(env)
    say(f'== state: {"boot command installed: install updates" if state == "installed" else "stock: install is a first install"}')
    say(f'  eMMC user area: {user_area_size(dev) // MIB} MiB')
    dev.close()
    say('Nothing was written.')


def cmd_install(args):
    global PHASE
    manifest = release_files(args.images)
    overview(f'{PRODUCT} {manifest["version"]}: install or update. Steps:', INSTALL_STEPS)
    step(1)
    sbd = load_tools()
    step(2)
    system_raw, manifest = prepare(args.images)
    step(3)
    dev, layout = connect(sbd, need_stock_uboot=False)
    step(4)
    env = read_env(dev)
    state = env_state(env)
    show_state(layout, env, state)
    if state == 'stock':
        ask(f'First install of {PRODUCT} {manifest["version"]}. The tool makes a backup (about 6 minutes),\n'
            'changes the U-Boot env, and writes the image to system_b (about 3 minutes).\n'
            'You do this at your own risk (README.md, "Warning").')
    else:
        ask(f'Update: the tool writes {PRODUCT} {manifest["version"]} to system_b (about 3 minutes; it skips '
            'the blocks\nthat are already the same). Your /data stays. If the Car Thing has a different image now, '
            'Android\ndeletes the data of the apps that this image does not have (README.md, "Update").')
    folder = None
    if state == 'stock':
        step(5, 'Backup and boot command (first install)')
        folder = backup(dev, env, full=not args.no_full_backup)
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
    if layout != 'stock':
        dev = reconnect_stock(sbd, dev)
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
    dev, layout = connect(sbd, need_stock_uboot=True)
    step(3)
    env = read_env(dev)
    state = env_state(env)
    show_state(layout, env, state, restore=True)
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
    args = parser.parse_args()
    if sys.platform not in ('darwin', 'linux'):
        stop('this tool runs on macOS and Linux only. Windows is not supported: use a Mac or a Linux '
             'computer.')
    args.images = args.images.resolve()
    HERE_IMAGES = args.images
    if args.command != 'prepare':
        bootstrap()
    {'install': cmd_install, 'restore': cmd_restore, 'prepare': cmd_prepare,
     'check': cmd_check}[args.command](args)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        stop('interrupted.' + (' The tool wrote nothing to the Car Thing.' if PHASE == 'read' else ''))
