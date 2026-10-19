#!/bin/bash
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# The source code of the GPL-2.0 kernel parts in the boot area and the image (docs/release.md):
# the Spotify kernel tree with the patches of this repository, its build configuration, the Mali kbase module
# (r16p0), and the Amlogic decoder modules (media_modules 810d965), as they were built.
# Run it in the kernel container after the kernel, kbase, and decoder module builds:
#   scripts/kernel.sh /plaything/scripts/kernel-source-bundle.sh
# Output: out/release/kernel-source.tar.xz (about 130 MB).
set -euo pipefail

OUT=/out/release
mkdir -p "$OUT"
cd /kernel
cp out/android/.config build/android.config
# media_modules/firmware has Amlogic firmware (video_ucode.bin, h264_enc.bin): its license does
# not allow redistribution, and the modules do not need it to build.
tar --exclude=.git --exclude='*.o' --exclude='*.ko' --exclude='*.cmd' --exclude='*.mod.c' \
    --exclude='*.o.d' --exclude=modules.order --exclude=Module.symvers \
    --exclude=build/media_modules/firmware \
    --transform 's,^build/android.config,kernel-source/android.config,' \
    --transform 's,^spotify-4.9,kernel-source/spotify-4.9,' \
    --transform 's,^build/kbase-r16p0,kernel-source/kbase-r16p0,' \
    --transform 's,^build/media_modules,kernel-source/media_modules,' \
    -cf - spotify-4.9 build/kbase-r16p0 build/media_modules build/android.config |
  xz -T0 -6 > "$OUT/kernel-source.tar.xz"
rm build/android.config
if tar -tJf "$OUT/kernel-source.tar.xz" | grep -E '\.(bin|hcd)$'; then
  echo "binary firmware in the bundle: stop"; rm "$OUT/kernel-source.tar.xz"; exit 1
fi
ls -l "$OUT/kernel-source.tar.xz"
