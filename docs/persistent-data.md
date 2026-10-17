# Persistent /data

`/data` is a 1 GiB ext4 image file on the stock `data` partition, mounted through a loop device:
`/mnt/vendor/cpt-stock/plaything/userdata.img`. So pairings, settings, and apps stay after a
power cycle, and the partition table does not change. The stock files on that partition do not
change; the stock software ignores the folder. PlayThing and superbird-android use the same
folder (the partition has room for one image).

If a step fails, `/data` is ext4 on zram1 (in RAM, empty at each start), and the start
continues.

## How it starts

SELinux lets only `init` mount file systems, so `init` does the mounts, and
`rootdir/data-setup.sh` (domain `cpt_data_setup`) does the steps between them, in `post-fs`:

| Step | `data-setup.sh` | Then `init` |
|---|---|---|
| `stock` | Checks the stock partition (below), runs `e2fsck -p` on it, and sets `ro.plaything.stock_dev` | mounts it at `/mnt/vendor/cpt-stock` (`noatime,nosuid,nodev,noexec,lazytime`), and makes and labels the folder `plaything/` |
| `data` | Makes the image if it is missing, attaches a loop device, runs `e2fsck -p`, and sets `ro.plaything.data_dev` | mounts it at `/data` (`noatime,nosuid,nodev`) |
| `check` | If `/data` is mounted: `ro.plaything.data=emmc`. Else: detaches the loop device, unmounts the stock file system, formats zram1, and sets `ro.plaything.ram_dev` and `ro.plaything.data=ram` | mounts zram1 at `/data` |

A mount whose property is not set does not run. The script writes its messages to the kernel
log (`data-setup:`).

Checks before any write: the kernel's `data` partition starts at sector 3256496 (the stock
layout; a shifted partition table fails this check), has at least 1.5 GiB, and holds ext4 with
the label `data`.

The first start writes the full 1 GiB with zeros, then makes the file system with
`mke2fs -E nodiscard`: all blocks are allocated once, so later writes do not change the extents
of the stock file system. This takes about 75 s once. At each start `fallocate` keeps the file
fully allocated, and the loop device does not pass discards (`discard_max_bytes` 0), so
Android's `fstrim` cannot punch holes in the image.

The Car Thing has no battery, so each unplug is a power cut. Both file systems have a journal,
and `e2fsck -p` runs at each start.

The mount point is under `/mnt/vendor`: at its start `vold` unmounts every other mount under
`/mnt`, and it waits for a busy one.

## SELinux labels

The stock file system has no SELinux labels (`unlabeled`). `init` labels only the `plaything` folder and its
files (`cpt_stock_data_file`, `file_contexts`); the stock files do not change.

## Flags

Create the file (as root over ADB), then restart:

| File | Effect |
|---|---|
| `/mnt/vendor/cpt-stock/plaything/wipe` | Delete the image and make a new, empty `/data` |
| `/mnt/vendor/cpt-stock/plaything/use-ram` | `/data` in RAM; the stock file system stays unmounted after the check |

With `use-ram`, the stock file system is not mounted. To delete the flag:

```sh
adb shell 'mount -t ext4 /dev/block/data /mnt/vendor/cpt-stock && rm /mnt/vendor/cpt-stock/plaything/use-ram && umount /mnt/vendor/cpt-stock'
```

## Change between PlayThing and superbird-android

Both use the same `/data`. At its start, Android deletes the data of apps that are not in its
system image, so superbird-android deletes DiPlay's and CptHome's data, and PlayThing deletes the
data of Launcher3Go. For a short test of the other image, set `use-ram` first.
