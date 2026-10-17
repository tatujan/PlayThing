#!/bin/sh
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Apply the PlayThing patches to the AOSP tree. Run it in the build container:
#   scripts/aosp.sh /plaything/scripts/aosp-patches.sh
# patches/aosp/<project path>/*.patch applies to /aosp/<project path>.
# Run it again after each `repo sync`. It skips patches that are already applied.
set -eu

PATCH_ROOT=/plaything/patches/aosp
AOSP=/aosp

find "$PATCH_ROOT" -name '*.patch' | sort | while read -r patch; do
  rel=${patch#"$PATCH_ROOT"/}
  project=$(dirname "$rel")
  dir="$AOSP/$project"
  if git -C "$dir" apply --reverse --check "$patch" 2>/dev/null; then
    echo "already applied: $rel"
  else
    git -C "$dir" apply --check "$patch"
    git -C "$dir" apply "$patch"
    echo "applied: $rel"
  fi
done
