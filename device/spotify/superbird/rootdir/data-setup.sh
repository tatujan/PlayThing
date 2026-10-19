#!/system/bin/sh
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Persistent /data (docs/persistent-data.md): an ext4 image file on the stock "data"
# partition, through a loop device. If a check or a step fails, /data is in RAM (zram1), and
# the boot continues.
#
# SELinux lets only init mount file systems (AOSP neverallow), so init.superbird.rc does the
# mounts, and this script (domain cpt_data_setup, docs/selinux.md) does the steps between
# them, at the start of "on post-fs" (after mount_all and logd, before post-fs-data):
#   stock  Check the stock partition and its file system, then set ro.plaything.stock_dev.
#          init mounts it on /mnt/vendor/cpt-stock and labels plaything/ in it.
#   data   Make or check the image and attach a loop device, then set
#          ro.plaything.data_dev. init mounts it on /data.
#   check  If /data is not mounted: prepare zram1, then set ro.plaything.ram_dev, and init
#          mounts zram1 on /data. Sets ro.plaything.data (emmc or ram).
# init does not run a mount if its property is not set ("doesn't exist while expanding").
#
# Flags in the stock file system, for ADB (then reboot):
#   /mnt/vendor/cpt-stock/plaything/wipe     delete the image, and make a new empty /data
#   /mnt/vendor/cpt-stock/plaything/use-ram  /data in RAM, the stock partition stays unmounted

STOCK=/dev/block/data
SYS=/sys/class/block/data
# The stock layout (docs/boot.md): offset 0x63616000 (sector 3256496). Another start
# means a shifted partition table (superbird-tool U-Boot, 24 MiB too far): then write nothing.
# The size depends on the eMMC (2292097024 bytes on the eMMC of 3776 MiB); it must hold the image and more.
START=3256496
MIN_SECTORS=3145728
# Under /mnt/vendor: at its start, vold force-unmounts all other mounts under /mnt. It cannot
# unmount this busy file system, and it waits 20 s for it (4 tries, 5 s each).
MNT=/mnt/vendor/cpt-stock
DIR=$MNT/plaything
IMG=$DIR/userdata.img
IMG_MIB=1024
DATA=/data
RAM=/dev/block/zram1
PROP=ro.plaything

LOOP=

log() { echo "data-setup: $*" > /dev/kmsg; }
# No grep: /system/bin/grep is not toybox in Android 9, and has the type system_file.
mounted() { case "$(cat /proc/mounts)" in *" $1 "*) return 0 ;; esac; return 1; }

# The stock partition: correct place, the stock file system. e2fsck -p: 0 clean,
# 1-3 repaired, 4+ errors.
stock() {
    [ "$(cat $SYS/start)" = $START ] && [ "$(cat $SYS/size)" -ge $MIN_SECTORS ] || {
        log "the data partition is not at the stock offset"; return; }
    # The stock file system: ext4 with the label "data" (each unit has its own UUID).
    # toybox blkid: AOSP's blkid has a private SELinux type, which device policy cannot use.
    case "$(toybox blkid $STOCK)" in
    *'LABEL="data"'*'TYPE="ext4"'*) ;;
    *) log "the data partition has no stock file system"; return ;;
    esac
    e2fsck -p $STOCK > /dev/kmsg 2>&1
    rc=$?
    [ $rc -lt 4 ] || { log "e2fsck of the stock file system gave $rc"; return; }
    setprop $PROP.stock_dev $STOCK
}

no_data() {
    log "$1"
    [ -n "$LOOP" ] && losetup -d $LOOP 2>/dev/null
    umount $MNT
    exit 0
}

data() {
    mounted $MNT || { log "the stock file system is not mounted"; return; }
    [ -d $DIR ] || no_data "no folder $DIR"
    [ -e $DIR/use-ram ] && no_data "flag use-ram"
    if [ -e $DIR/wipe ]; then
        rm -f $IMG $DIR/wipe
        log "flag wipe: image deleted"
    fi

    # First boot: make the image. Write all of it once, so that later writes do not change
    # the extents of the stock file system (less metadata to lose at a power cut). mke2fs
    # must not discard: on a file, a discard punches holes (only 43 MB of the 1 GiB file stays
    # allocated).
    if [ ! -f $IMG ]; then
        log "new image $IMG ($IMG_MIB MiB)"
        rm -f $IMG.new
        dd if=/dev/zero of=$IMG.new bs=1048576 count=$IMG_MIB 2>/dev/null || no_data "dd of the image"
        mke2fs -t ext4 -b 4096 -q -E nodiscard -L cpt-data $IMG.new || no_data "mke2fs of the image"
        sync
        mv $IMG.new $IMG || no_data "rename of the image"
        sync
    fi

    # fallocate allocates the holes of an image that has holes; for an allocated image it
    # changes nothing. No discard on the loop device: Android's fstrim
    # would punch holes in the image again.
    fallocate -l $((IMG_MIB * 1048576)) $IMG || log "fallocate failed"
    LOOP=$(losetup -sf $IMG) || no_data "losetup"
    echo 0 > /sys/block/${LOOP##*/}/queue/discard_max_bytes || log "discard is still on"
    e2fsck -p $LOOP > /dev/kmsg 2>&1
    rc=$?
    [ $rc -lt 4 ] || no_data "e2fsck of /data gave $rc"
    setprop $PROP.data_dev $LOOP
}

check() {
    if mounted $DATA; then
        setprop $PROP.data emmc
        log "/data on $(getprop $PROP.data_dev) ($IMG)"
        return
    fi
    LOOP=$(getprop $PROP.data_dev)
    [ -n "$LOOP" ] && { log "$LOOP does not mount"; losetup -d $LOOP 2>/dev/null; }
    mounted $MNT && umount $MNT
    log "/data in RAM"
    mke2fs -t ext4 -b 4096 -q $RAM && setprop $PROP.ram_dev $RAM
    setprop $PROP.data ram
}

case "$1" in
stock|data|check) $1 ;;
*) log "unknown step '$1'" ;;
esac
