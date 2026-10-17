#!/bin/sh
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Prepare the AOSP tree for the PlayThing build. Run it in the AOSP container:
#   scripts/aosp.sh /plaything/scripts/aosp-prepare.sh
# Steps:
#   1. Add the donor projects (manifests/plaything.xml) and sync them.
#   2. Put placeholders for the files that the image must not contain (Mali blob, video
#      firmware, DiPlay APK); scripts/flash.py writes them in (scripts/blobs.py,
#      docs/release.md).
#   3. Copy device/spotify/superbird from this repository into the tree.
#   4. Copy the kernel files from /out/kernel (scripts/kernel-export.sh makes them).
#      Copy the hardware video decoder modules from /out/media-modules if they exist.
#   5. Apply the patches in patches/aosp/.
set -eu

REPO=/plaything
AOSP=/aosp
DONORS="hardware/amlogic/gralloc"

# Placeholders; the sources and SHA-256 of the real files are in scripts/blobs.py.
MALI_DST=$AOSP/vendor/plaything/mali/libGLES_mali.so
DIPLAY_DST=$AOSP/vendor/plaything/diplay/DiPlay.apk

cd "$AOSP"

echo "== donor projects"
mkdir -p .repo/local_manifests
cp "$REPO/manifests/plaything.xml" .repo/local_manifests/plaything.xml
# Remove the old patch changes, so that the sync does not stop. Step 5 applies them again.
for p in $DONORS; do
  if [ -d "$p/.git" ]; then
    git -C "$p" checkout -q -- .
    git -C "$p" clean -fdq
  fi
done
repo sync -c --no-tags --no-clone-bundle -j4 $DONORS

echo "== placeholders"
mkdir -p "$(dirname "$MALI_DST")" "$(dirname "$DIPLAY_DST")"
python3 "$REPO/scripts/blobs.py" placeholder libGLES_mali.so "$MALI_DST"
python3 "$REPO/scripts/blobs.py" placeholder DiPlay.apk "$DIPLAY_DST"

echo "== device configuration"
mkdir -p device/spotify
rsync -a --delete "$REPO/device/spotify/superbird/" device/spotify/superbird/

echo "== kernel files"
mkdir -p device/spotify/superbird-kernel
for f in Image.gz superbird.dtb mali_kbase.ko; do
  cp "/out/kernel/$f" device/spotify/superbird-kernel/
done

# The Bluetooth firmware is not in the image: Android reads it from the unit's stock root
# file system (init.superbird.rc). Remove the folder of older builds.
rm -rf device/spotify/superbird-firmware

echo "== hardware video decoder"
# Amlogic media_modules for our kernel (scripts/media-modules.sh makes them in
# /out/media-modules). They must come from the same kernel build as the boot area kernel.
mkdir -p device/spotify/superbird-media
rm -f device/spotify/superbird-media/*
for f in media_clock.ko firmware.ko decoder_common.ko stream_input.ko amvdec_mh264.ko \
         amvdec_ports.ko; do
  if [ -f "/out/media-modules/$f" ]; then
    cp "/out/media-modules/$f" device/spotify/superbird-media/
  else
    echo "no /out/media-modules/$f: the image has no hardware video decoder"
  fi
done
# The decoder firmware (Amlogic) is a placeholder; scripts/flash.py writes the real file.
python3 "$REPO/scripts/blobs.py" placeholder video_ucode.bin device/spotify/superbird-media/video_ucode.bin

echo "== patches"
"$REPO/scripts/aosp-patches.sh"
