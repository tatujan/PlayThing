#!/bin/sh
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Run a command in the AOSP Pie build container (linux/amd64 under Rosetta).
# The AOSP tree is in the named volume "aosp", at /aosp in the container.
# This repository is at /plaything in the container (read-only).
# The folder out/ of this repository is at /out (read-write). It moves files
# between the kernel container and the AOSP container. Git ignores it.
# Usage: scripts/aosp.sh [command ...]   With no command, it starts bash.
# Change the resources with AOSP_CPUS and AOSP_MEMORY.
# Set AOSP_NAME to name the container, so that `container exec` can reach it.
# Do not run two containers with the volume at the same time: it is an ext4 disk image.
set -eu

IMAGE="${AOSP_IMAGE:-plaything/aosp-pie:1}"
VOLUME="aosp"
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$REPO_ROOT/out"

tty_flags=""
if [ -t 0 ] && [ -t 1 ]; then
  tty_flags="-it"
fi

name_flags=""
if [ -n "${AOSP_NAME:-}" ]; then
  name_flags="--name $AOSP_NAME"
fi

if [ "$#" -eq 0 ]; then
  set -- bash
fi

exec container run --rm $tty_flags $name_flags \
  --arch amd64 --cpus "${AOSP_CPUS:-8}" --memory "${AOSP_MEMORY:-16g}" \
  --volume "$VOLUME:/aosp" \
  --volume "$REPO_ROOT:/plaything:ro" \
  --volume "$REPO_ROOT/out:/out" \
  "$IMAGE" "$@"
