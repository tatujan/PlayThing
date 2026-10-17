#!/bin/bash
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the PlayThing image in the AOSP container (docs/release.md):
#   scripts/aosp.sh /plaything/scripts/build-image.sh
# It needs the kernel files in out/kernel (scripts/kernel-export.sh) and the decoder modules
# in out/media-modules (scripts/media-modules.sh).
# Output in out/aosp:
#   system.raw      the system file system (ext4), with placeholders for the proprietary files
#   boot-area.img   the 32 MiB boot area at the end of system_b (kernel, ramdisk, boot script)
#   blobs.json      where the placeholders are in system.raw (scripts/blobs.py, scripts/flash.py)
# The image has no file whose license forbids redistribution; the last step checks this.
set -eo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd)
AOSP=/aosp
DEST=/out/aosp
KERNEL=/out/kernel/android/Image
# The system file system size (BoardConfig.mk); the boot area starts after it.
FS_SIZE=507555840

"$REPO/scripts/aosp-prepare.sh"

cd "$AOSP"
source build/envsetup.sh > /dev/null
lunch aosp_superbird-userdebug > /dev/null
P=out/target/product/superbird
# The images take every file in the output folder, also files of modules that left the
# product (for example an old HWC or firmware). installclean deletes the installed files;
# the build installs them again from its intermediates.
m installclean > /dev/null
m -j8
# gdbserver (a userdebug debug tool) is a GPL-3.0 prebuilt whose source is not in the AOSP tree,
# so it is not in the image. snod makes the system image again without its dependencies.
rm -f $P/system/bin/gdbserver
m snod > /dev/null

echo "== check: no proprietary files"
bad=0
for f in $(find $P/system $P/root -type f \( -name '*.hcd' -o -name 'hwcomposer.aosp_superbird.so' \
           -o -name 'libamgralloc*' -o -name 'libge2d*' -o -name 'gdbserver' \)); do
  echo "forbidden file: $f"
  bad=1
done
[ $bad = 0 ]

mkdir -p $DEST
simg2img $P/system.img $DEST/system.raw
cp $P/ramdisk.img $DEST/ramdisk.img
python3 "$REPO/scripts/mkuimage-ramdisk.py" $DEST/ramdisk.img $DEST/ramdisk.uimg
python3 "$REPO/scripts/mk-systemb-tail.py" --kernel $KERNEL --ramdisk $DEST/ramdisk.uimg \
    --mode host --fs-size $FS_SIZE --out $DEST/boot-area.img
# Also checks that every proprietary file in the image is its placeholder.
python3 "$REPO/scripts/blobs.py" map $DEST/system.raw $DEST/blobs.json
ls -l $DEST/system.raw $DEST/boot-area.img $DEST/blobs.json
echo BUILD-DONE
