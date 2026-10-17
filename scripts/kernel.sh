#!/bin/sh
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Run a command in the kernel build container (linux/arm64, native).
# The kernel trees are in the named volume "kernel", at /kernel in the container.
# This repository is at /plaything in the container (read-only).
# The folder out/ of this repository is at /out (read-write). It moves files
# between the kernel container and the AOSP container. Git ignores it.
# Usage: scripts/kernel.sh [command ...]   With no command, it starts bash.
# Change the resources with KERNEL_CPUS and KERNEL_MEMORY.
# Set KERNEL_NAME to name the container, so that `container exec` can reach it.
# Do not run two containers with the volume at the same time: it is an ext4 disk image.
set -eu

IMAGE="${KERNEL_IMAGE:-plaything/kernel-4.9:2}"
VOLUME="kernel"
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$REPO_ROOT/out"

tty_flags=""
if [ -t 0 ] && [ -t 1 ]; then
  tty_flags="-it"
fi

name_flags=""
if [ -n "${KERNEL_NAME:-}" ]; then
  name_flags="--name $KERNEL_NAME"
fi

if [ "$#" -eq 0 ]; then
  set -- bash
fi

exec container run --rm $tty_flags $name_flags \
  --arch arm64 --cpus "${KERNEL_CPUS:-8}" --memory "${KERNEL_MEMORY:-8g}" \
  --volume "$VOLUME:/kernel" \
  --volume "$REPO_ROOT:/plaything:ro" \
  --volume "$REPO_ROOT/out:/out" \
  "$IMAGE" "$@"
