#!/usr/bin/env python3
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare an image for an Android RGB_565 bitmap (CptHome wallpaper).

Android decodes a PNG to RGB_565 by truncation (8 to 5 or 6 bits), without dither, so dark
gradients get bands. This script does a 4x4 ordered (Bayer) dither to the RGB_565 levels on
the Mac, and writes each level as its canonical 8-bit value ((v5 << 3) | (v5 >> 2)). Then the
decode on the device loses nothing. Alpha is composited over BG and removed.

Usage: python3 -I scripts/rgb565-dither.py IN.png OUT.png [BG_HEX]
"""
import sys
from PIL import Image

BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def level(v, bits, t):
    # v 0..255; t is the threshold in (0, 1). Steps of 255 / (2^bits - 1).
    top = (1 << bits) - 1
    x = v * top / 255.0
    n = int(x)
    if x - n > t:
        n += 1
    n = min(n, top)
    return (n << (8 - bits)) | (n >> (2 * bits - 8))


def main():
    src, dst = sys.argv[1], sys.argv[2]
    bg = sys.argv[3] if len(sys.argv) > 3 else '161826'
    im = Image.open(src).convert('RGBA')
    base = Image.new('RGBA', im.size, '#' + bg)
    im = Image.alpha_composite(base, im).convert('RGB')
    w, h = im.size
    px = im.load()
    out = Image.new('RGB', (w, h))
    po = out.load()
    for y in range(h):
        row = BAYER[y & 3]
        for x in range(w):
            t = (row[x & 3] + 0.5) / 16.0
            r, g, b = px[x, y]
            po[x, y] = (level(r, 5, t), level(g, 6, t), level(b, 5, t))
    out.save(dst, optimize=True)


if __name__ == '__main__':
    main()
