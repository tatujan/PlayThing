#!/bin/sh
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Copy the Android kernel, the DTB, and the Mali module to /out/kernel for the AOSP build.
# Run it in the kernel container after the "android" kernel and kbase builds
# (docs/kernel.md, docs/graphics.md):
#   scripts/kernel.sh /plaything/scripts/kernel-export.sh
set -eu

KERNEL_OUT=/kernel/out/android
KBASE=/kernel/build/kbase-r16p0/drivers/gpu/arm/midgard/mali_kbase.ko
DST=/out/kernel

mkdir -p "$DST"
cp "$KERNEL_OUT/arch/arm64/boot/Image.gz" "$DST/Image.gz"
# The boot area (scripts/mk-systemb-tail.py) needs the uncompressed Image: U-Boot booti.
mkdir -p "$DST/android"
cp "$KERNEL_OUT/arch/arm64/boot/Image" "$DST/android/Image"
cp "$KERNEL_OUT/arch/arm64/boot/dts/amlogic/superbird_evt_512.dtb" "$DST/superbird.dtb"
# The Spotify DTB has an Android early-mount node (firmware_ab.dtsi): /system with
# "slotselect" and AVB vbmeta. Android 9 init reads it in its first stage, before
# fstab.superbird, and this boot has no slot suffix and no vbmeta. Its /system is a
# late mount (fstab.superbird), so remove the node.
fdtput -r "$DST/superbird.dtb" /firmware/android
# Touch: the controller reports portrait coordinates. Android 9 rotates only the display,
# so the driver rotates the touch (patches/kernel/spotify-4.9/0002-*): X = raw y,
# Y = max - raw x.
TOUCH=/soc/cbus@ffd00000/i2c@1f000/tlsc6x_ts@2e
fdtput -t s "$DST/superbird.dtb" $TOUCH touchscreen-inverted-x ""
fdtput -t s "$DST/superbird.dtb" $TOUCH touchscreen-swapped-x-y ""
cp "$KBASE" "$DST/mali_kbase.ko"
(cd "$DST" && sha256sum Image.gz superbird.dtb mali_kbase.ko > SHA256SUMS)
cat "$DST/SHA256SUMS"
