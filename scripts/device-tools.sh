#!/bin/bash
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the small device tools in scripts/device/ as static aarch64 binaries.
# They run on the 64-bit kernel under any userspace (Android or stock).
# Run in the kernel container (native arm64):
#   scripts/kernel.sh /plaything/scripts/device-tools.sh
# Output: /out/device-tools/ (out/device-tools/ in this repository).
# Test tools, not in the image: push them with adb (for example to /data/local/tmp).
set -euo pipefail

OUT=/out/device-tools
mkdir -p "$OUT"
for src in /plaything/scripts/device/*.c; do
  name=$(basename "$src" .c)
  gcc -static -O2 -Wall -Werror -o "$OUT/$name" "$src"
  strip "$OUT/$name"
  echo "$OUT/$name: $(stat -c %s "$OUT/$name") bytes"
done
