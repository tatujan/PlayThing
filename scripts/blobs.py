#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Proprietary files of the PlayThing image, and their placeholders (docs/release.md).

The image does not contain these files: the licenses of the first two do not allow
redistribution, DiPlay's release APK contains a CarPlay accessory identity, and Droid-ify (the
app store, GPL-3.0) comes as its developer's release, as DiPlay.
The build puts a placeholder of the same size in their place. The flash tool
(scripts/flash.py) downloads each file from its public source, checks its SHA-256, and
writes it into the blocks of its placeholder in system.raw, on the user's computer.
The Bluetooth firmware is not here: Android reads it from the unit's own stock root file
system (init.superbird.rc, docs/bluetooth-audio.md).

Usage:
  blobs.py placeholder NAME OUT        write the placeholder of NAME to OUT (aosp-prepare.sh)
  blobs.py map SYSTEM_RAW OUT_JSON     find the placeholder blocks in SYSTEM_RAW with debugfs,
                                       check them, and write the manifest for flash.py

A placeholder has text in every byte: e2fsdroid stores blocks of zeros as holes (no data
blocks to write), and each 4 KiB block has its own number, so that no block is the same as
another one (no shared blocks).

Python 3.6 or later (the AOSP container has 3.6).
"""

import hashlib
import json
import os
import subprocess
import sys

BLOCK = 4096

BLOBS = [
    {
        'name': 'libGLES_mali.so',
        'path': '/vendor/lib/egl/libGLES_mali.so',
        'size': 28201600,
        'sha256': '049f480f01f11650029c65d3cb1c9c3b126a34ae152f5b47551eaf07deac9074',
        'url': 'https://raw.githubusercontent.com/codewalkerster/android_vendor_amlogic_common_gpu-lib/'
               '42d8ae4e201c25327f686b6c3a34efea63df13cf/dvalin_ion/libGLES_mali_default_8a_32-p-r16p0gralloc1.so',
        'what': 'Arm Mali-G31 userspace driver r16p0 (proprietary, Arm)',
    },
    {
        'name': 'video_ucode.bin',
        'path': '/vendor/lib/firmware/video/video_ucode.bin',
        'size': 1671680,
        'sha256': '785349dbc0b617b236af5622a75c8331e0cdc8895f11751a7fc39d74c56215a0',
        'url': 'https://raw.githubusercontent.com/codewalkerster/android_hardware_amlogic_media_modules/'
               '810d965ec5f97a6f65f5b801ddc1ce76bf0f4441/firmware/video_ucode.bin',
        'what': 'Amlogic video decoder firmware (proprietary, Amlogic)',
    },
    {
        # Only in the PlayThing image (superbird-android has no DiPlay).
        'name': 'DiPlay.apk',
        'path': '/app/DiPlay/DiPlay.apk',
        'size': 18459546,
        'sha256': 'f4d96cf469c5df12350946404a43e737ccc779a30e3b492c29f9c2338806f098',
        'url': 'https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.20/DiPlay-0.2.20.apk',
        'what': 'DiPlay 0.2.20, the CarPlay app (GPL-3.0; release APK from its GitHub releases)',
        'optional': True,
    },
    {
        # The app store (docs/apps.md), in both images. Its native libraries are stored
        # uncompressed (extractNativeLibs=false), so Android uses the APK from /system as it is.
        'name': 'Droidify.apk',
        'path': '/app/Droidify/Droidify.apk',
        'size': 5997579,
        'sha256': '3e7934ff9b811c2f77794793381241095a02bac2d163bf922cb5a5a0ed25ca82',
        'url': 'https://github.com/Droid-ify/client/releases/download/v0.7.8/app-release.apk',
        'what': 'Droid-ify 0.7.8, the app store (GPL-3.0; release APK from its GitHub releases)',
    },
]


def blob(name):
    for b in BLOBS:
        if b['name'] == name:
            return b
    sys.exit('blobs: unknown file ' + name)


def placeholder(b):
    """The placeholder bytes of blob b."""
    out = bytearray()
    index = 0
    while len(out) < b['size']:
        line = ('PLAYTHING PLACEHOLDER %s %s block %08d\n' % (b['name'], b['sha256'][:16], index)).encode()
        out += (line * (BLOCK // len(line) + 1))[:BLOCK]
        index += 1
    return bytes(out[:b['size']])


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(2**20), b''):
            h.update(chunk)
    return h.hexdigest()


def debugfs(image, request):
    return subprocess.check_output(['debugfs', '-R', request, image], stderr=subprocess.DEVNULL).decode()


def extents(image, b):
    """Byte ranges [offset, length] of the file in the image, in file order."""
    stats = debugfs(image, 'stats')
    block_size = int(stats.split('Block size:')[1].split()[0])
    blocks = [int(x) for x in debugfs(image, 'blocks ' + b['path']).split()]
    if len(blocks) != -(-b['size'] // block_size):
        sys.exit('blobs: %s has %d blocks in the image, expected %d' % (b['path'], len(blocks),
                                                                         -(-b['size'] // block_size)))
    ranges = []
    for i, p in enumerate(blocks):
        length = min(block_size, b['size'] - i * block_size)
        offset = p * block_size
        if ranges and ranges[-1][0] + ranges[-1][1] == offset:
            ranges[-1][1] += length
        else:
            ranges.append([offset, length])
    return ranges


def read_ranges(f, ranges):
    data = bytearray()
    for offset, length in ranges:
        f.seek(offset)
        data += f.read(length)
    return bytes(data)


def in_image(image, path):
    return 'Inode:' in debugfs(image, 'stat ' + path)


def make_map(image, out_json):
    files = []
    with open(image, 'rb') as f:
        for b in BLOBS:
            if b.get('optional') and not in_image(image, b['path']):
                print('%s: not in this image' % b['path'])
                continue
            ranges = extents(image, b)
            if read_ranges(f, ranges) != placeholder(b):
                sys.exit('blobs: the blocks of %s in the image are not its placeholder' % b['path'])
            entry = dict(b)
            entry.pop('optional', None)
            entry['extents'] = ranges
            files.append(entry)
            print('%s: %d extents' % (b['path'], len(ranges)))
    manifest = {
        'image': os.path.basename(image),
        'image_size': os.path.getsize(image),
        'image_sha256': sha256_file(image),
        'files': files,
    }
    with open(out_json, 'w') as f:
        json.dump(manifest, f, indent=1)
        f.write('\n')
    print('manifest: %s' % out_json)


def main():
    if len(sys.argv) == 4 and sys.argv[1] == 'placeholder':
        with open(sys.argv[3], 'wb') as f:
            f.write(placeholder(blob(sys.argv[2])))
    elif len(sys.argv) == 4 and sys.argv[1] == 'map':
        make_map(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
