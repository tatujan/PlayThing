#!/bin/bash
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the Amlogic media_modules decoder modules (docs/video.md) for our kernel.
# Run in the kernel container after the "android" kernel build:
#   scripts/kernel.sh /plaything/scripts/media-modules.sh
#
# Source: codewalkerster/android_hardware_amlogic_media_modules, branch
# g12_9.0.0_32_master, cloned to /kernel/donors/media_modules/src (the kernel container
# has no network: clone on the Mac into out/donors/, then copy it). The branch head
# (2021) needs a newer Amlogic media framework than the Spotify kernel. Commit 810d965
# ("mh264/h265/avs2 [2/2]", 2019-05-07) is the pair of the newest Spotify media header
# change ("[1/2]", 2019-04-08), and it builds. The donor checkout is not changed: the
# build uses an exported copy in /kernel/build/media_modules.
#
# Output in /out/media-modules/: the .ko files, video_ucode.bin, SHA256SUMS.
set -euo pipefail

SRC=/kernel/donors/media_modules/src
COMMIT=810d965ec5f97a6f65f5b801ddc1ce76bf0f4441
BUILD=/kernel/build/media_modules
KOUT=/kernel/out/android
OUT=/out/media-modules
# H.264 only: the iPhone CarPlay stream is H.264 (DiPlay: hevc=false).
CONFIGS="CONFIG_AMLOGIC_MEDIA_VDEC_H264=m CONFIG_AMLOGIC_MEDIA_VDEC_H264_MULTI=m"

rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD" "$OUT"
git -C "$SRC" archive "$COMMIT" | tar -x -C "$BUILD"
M=$BUILD/drivers
make -C "$KOUT" M="$M" ARCH=arm64 CC=gcc-6 $CONFIGS EXTRA_CFLAGS+=-I"$M"/include \
    -j8 modules > /kernel/logs/media-modules-build.log 2>&1 || {
    grep -E "error:|Error [0-9]" /kernel/logs/media-modules-build.log | head -n 20
    exit 1
}
# A missing kernel symbol is only a modpost warning, but the module then does not load.
if grep -q "undefined!" /kernel/logs/media-modules-build.log; then
    grep "undefined!" /kernel/logs/media-modules-build.log
fi
find "$M" -name "*.ko" -exec cp {} "$OUT"/ \;
cp "$BUILD"/firmware/video_ucode.bin "$OUT"/
(cd "$OUT" && sha256sum *.ko video_ucode.bin > SHA256SUMS && cat SHA256SUMS)
