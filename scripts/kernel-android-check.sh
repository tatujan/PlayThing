#!/bin/sh
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Compare a kernel .config with Android kernel config fragments.
# Run it in the kernel container:
#   scripts/kernel.sh /plaything/scripts/kernel-android-check.sh <kernel source> <.config> <fragment>...
# Output: one line for each option in the fragments: STATUS OPTION WANT HAVE
#   ok       the .config has the wanted value
#   off      the source defines the option, but the .config has a different value
#   missing  no Kconfig file in the source defines the option (a missing patch)
# "n" means "not set". The last lines give the count for each status.
set -eu

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <kernel source> <.config> <fragment>..." >&2
  exit 2
fi

src=$1
config=$2
shift 2

symbols=$(mktemp)
trap 'rm -f "$symbols"' EXIT
grep -rhoE '^[[:space:]]*(menu)?config[[:space:]]+[A-Za-z0-9_]+' --include='Kconfig*' "$src" \
  | awk '{ print "CONFIG_" $2 }' | sort -u > "$symbols"

awk -v symbols="$symbols" -v config="$config" '
  function parse(line, kv) {
    if (line ~ /^# CONFIG_[A-Za-z0-9_]+ is not set/) {
      split(line, f, " "); kv[1] = f[2]; kv[2] = "n"; return 1
    }
    if (line ~ /^CONFIG_[A-Za-z0-9_]+=/) {
      i = index(line, "="); kv[1] = substr(line, 1, i - 1); kv[2] = substr(line, i + 1)
      gsub(/^"|"$/, "", kv[2])
      return 1
    }
    return 0
  }
  BEGIN {
    while ((getline s < symbols) > 0) known[s] = 1
    while ((getline line < config) > 0) if (parse(line, kv)) have[kv[1]] = kv[2]
  }
  {
    if (!parse($0, kv) || (kv[1] in seen)) next
    seen[kv[1]] = 1
    want = kv[2]
    got = (kv[1] in have) ? have[kv[1]] : "n"
    if (!(kv[1] in known)) status = (want == "n") ? "ok" : "missing"
    else if (got == want) status = "ok"
    else status = "off"
    count[status]++
    printf "%-8s %-45s want=%-6s have=%s\n", status, kv[1], want, got
  }
  END {
    printf "total: ok=%d off=%d missing=%d\n", count["ok"], count["off"], count["missing"]
  }
' "$@"
