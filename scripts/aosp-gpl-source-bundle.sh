#!/bin/bash
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# The source code of the GPL and LGPL parts of AOSP that are in the image (docs/release.md), as
# they were built: the working trees of these projects (with the patches of this repository). The list comes from
# the "gnu" tag in out/target/product/superbird/module-info.json and from the GPL texts in the
# image's NOTICE.html; libcore and the art JVMTI/JDWP parts are GPL-2.0 with the Classpath
# exception. All other AOSP source: android.googlesource.com, tag android-security-9.0.0_r76.
# Run it in the AOSP container after a build:
#   scripts/aosp.sh /plaything/scripts/aosp-gpl-source-bundle.sh
# Output: out/release/aosp-gpl-source.tar.xz
set -euo pipefail

TAG=android-security-9.0.0_r76
PROJECTS="art libcore external/dnsmasq external/e2fsprogs external/f2fs-tools external/gptfdisk
  external/iproute2 external/iptables external/libcap external/libexif external/libnl external/lz4
  external/mmc-utils external/oj-libjdwp external/selinux"
OUT=/out/release
mkdir -p "$OUT"
cd /aosp

list=$(mktemp)
{
  echo "AOSP $TAG: the GPL and LGPL projects in the image, as built."
  echo "project revision local-changes"
  for p in $PROJECTS; do
    echo "$p $(git -C "$p" rev-parse HEAD) $(git -C "$p" status --short | wc -l)"
  done
} > "$list"
cp "$list" /tmp/PROJECTS.txt
tar --exclude=.git --transform 's,^,aosp-gpl-source/,' -cf - $PROJECTS \
    -C /tmp PROJECTS.txt | xz -T0 -6 > "$OUT/aosp-gpl-source.tar.xz"
rm -f "$list" /tmp/PROJECTS.txt
ls -l "$OUT/aosp-gpl-source.tar.xz"
