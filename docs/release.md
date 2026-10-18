# Releases and the flash tool

A release is a folder that anyone may download: the image, the flash tool, and the source code of
the GPL parts. The image contains no file whose license forbids redistribution, and no copy of
DiPlay; the flash tool adds those files on the user's computer.

## Contents of a release folder

| File | Content |
|---|---|
| `system.raw.xz` | The Android system file system, with placeholders |
| `boot-area.img` | Kernel, ramdisk, and boot variables (the last 32 MiB of `system_b`, `docs/boot.md`) |
| `manifest.json` | SHA-256 values of the image (with placeholders, and finished), of the boot area, and of each downloaded file, with its source URL and its byte ranges in the image |
| `flash.py`, `blobs.py` | The flash tool |
| `kernel-source.tar.xz` | The kernel tree as built, its configuration, kbase, and media_modules (without its firmware folder) |
| `aosp-gpl-source.tar.xz` | The AOSP projects with GPL or LGPL parts in the image, as built |
| `README.md`, `SHA256SUMS` | |

## What the image does not contain

| File | Owner | How the device gets it |
|---|---|---|
| Mali userspace driver `libGLES_mali.so` | Arm | `flash.py` downloads it (codewalkerster `android_vendor_amlogic_common_gpu-lib`, pinned commit) |
| Video decoder firmware `video_ucode.bin` | Amlogic | `flash.py` downloads it (codewalkerster `android_hardware_amlogic_media_modules`, pinned commit) |
| DiPlay release APK (PlayThing) | DiPlay's author; it contains a CarPlay accessory identity | `flash.py` downloads it (DiPlay's GitHub release) |
| Bluetooth firmware `BCM20703A2.hcd` | Broadcom | Android reads it from the unit's stock root file system (`docs/bluetooth-audio.md`) |

Not used at all: Amlogic's HWC and gralloc extension (I wrote my own HWC, `docs/graphics.md`),
and AOSP's prebuilt `gdbserver` (GPL-3.0, its source is not in the AOSP tree; `build-image.sh`
removes it). `scripts/build-image.sh` stops if the output has one of these files.

## Placeholders (`scripts/blobs.py`)

- The build puts a placeholder of the real size in place of each downloaded file
  (`scripts/aosp-prepare.sh`). Every byte of a placeholder is text (e2fsdroid stores blocks of
  zeros as holes), and each 4 KiB block has its own number, so that no two blocks are the same.
- After the build, `blobs.py map` finds the blocks of each placeholder in `system.raw`
  (`debugfs`), checks that they hold the placeholder, and writes `blobs.json`.
- `flash.py` downloads each file, checks its size and SHA-256, checks that each byte range still
  holds the placeholder, and writes the file there. The ext4 file system has no checksums of data
  blocks (no `metadata_csum`), and the inodes do not change, so the file system stays valid.
- `make-release.py` does the same once on a temporary copy and records the SHA-256 of the
  finished image. `flash.py` writes nothing to the Car Thing unless its image has that SHA-256.

## The flash tool (`flash.py`)

Flash tool (`flash.py`): macOS and Linux. I tested it on macOS with an Apple Silicon Mac; an
Intel Mac and Linux should work (Linux needs a udev rule for `1b8e:c003`, see the release README), but I did not test them yet.
Windows is not supported: the tool stops at the start. Use a Mac or a Linux computer.

| Command | What it does |
|---|---|
| `install` | first install from the stock software, or an update of PlayThing or superbird-android |
| `restore BACKUP` | writes the stock boot command and the backed-up `system_b` back |
| `check` | shows the partition table (each partition with offset and size), the env, and the eMMC size; writes nothing |
| `diag` | makes a diagnostic folder `diag/<time>/`; writes nothing (see below) |
| `prepare` | only makes the finished image (`work/system.raw`); no device |

Before its first run, the tool makes a Python environment (`.venv`: pyusb and pyamlboot at
pinned commits). Then it prints the steps of `install`, and a numbered header for each one. Long
steps show a progress line (MiB, percent, speed, time left; on a terminal it is written again in
place, in a log one line for each 10 %) and end with a summary line.

1. **Tools:** superbird-tool's USB module and burn-mode U-Boot (`thinglabsoss/superbird-tool`
   `da05fb4`, each file checked with SHA-256).
2. **Image:** the finished image (see above).
3. **Connect:** the Car Thing in USB mode: mask-ROM mode (presets 1 and 4 at power-on; the tool
   asks before it loads superbird-tool's U-Boot into RAM), or the burn mode of Spotify's U-Boot
   (preset 4, after the first install).
4. **Check:** it reads the partition table (MPT at 36 MiB) and the U-Boot env (116 MiB, 64 KiB,
   CRC checked). It stops if the stock software is not in slot A (`active_slot`), if the boot
   command is neither the stock one nor the one that the tool writes, or if the eMMC has a GPT
   (another firmware). It shows what it found and asks before it writes (first install and
   update).
5. **First install only:** a backup to `backups/<time>/` (`env.txt`, the env region,
   `system_b`, and by default the full eMMC user area, with `SHA256SUMS`; about 6 minutes,
   3.7 GB), then the env change (`docs/boot.md`), which it reads back and checks. If the
   partition table is not yet trusted (see below), the tool first adds only the preset-4 burn
   mode, asks for a new connection, checks the table, and then changes the boot command. An
   update skips this step (`--backup` backs up `system_b` first).
6. **Write:** if the table is not yet trusted, the tool first asks for a new connection with
   preset 4. It writes `system.raw` at the start of `system_b`, then `boot-area.img`
   at `+0x1e40b000`, in 16 MiB blocks: read, skip if the same, write, read back, compare. The
   boot area is last: on a first install, the boot falls back to the stock software until it is
   complete.

**Partition table check.** The tool writes `system_b` only when the partition table of the Car
Thing's **own** U-Boot has `system_b` at `0x3120b000` with `0x2040b000` bytes (`docs/boot.md`,
"Partition tables": with or without `fip_a`/`fip_b`). A table without `fip_a`/`fip_b` always
comes from the Car Thing's own (older) U-Boot, so the tool trusts it at once. A table with
`fip_a`/`fip_b` can come from superbird-tool's U-Boot in RAM (after a mask-ROM entry, or from an
earlier run without a power cycle), so the tool trusts it only after a new connection: the Car
Thing must be off the USB bus for 1 second, and then come back in burn mode with preset 4. Any
other place of `system_b` stops the tool before the boot command changes.

If the tool stops or is interrupted, it says what it wrote and how to continue: after the env
change or during a write, the same command again finishes the work (it skips the blocks that are
already written). `restore` also finishes an earlier restore that stopped.

The tool uses one USB handle to the device: a second handle makes the U-Boot commands collide,
and U-Boot then stops answering until a power cycle.

## Diagnostic folder (`diag`)

`python3 flash.py diag` (preset 4 is best) reads, and writes nothing to the eMMC:

| File | Content |
|---|---|
| `summary.txt` | everything below in short, as the tool shows it |
| `env.txt`, `mpt.txt` | the U-Boot env and the partition table |
| (in `summary.txt`) | the SHA-256 check of the installed image and boot area against the release |
| `boot_a.dtb` | the stock device tree: U-Boot decrypts it into RAM (`imgread dtb boot_a`), and the tool reads the RAM over USB |
| (in `summary.txt`) | the stock software version (`/etc/superbird/version` in `system_a`) and the Bluetooth firmware |
| `data/` | from `userdata.img` on the stock data partition: `misc/logd` (Android and kernel logs), `tombstones`, `anr`, `misc/bootstat` |

For `data/` the tool reads two ext4 file systems over USB: the stock data partition, and the
ext4 image `plaything/userdata.img` in it. After a power cut, the newest metadata (file sizes,
block lists) is still in the ext4 journal, so the tool replays the journal in memory, as a
recovery does (`e2fsck` gives the same files), and writes nothing. `data/` has device data (Bluetooth addresses, device names);
the other files have none.

## Coming from another firmware

| Kind | Examples | Result |
|---|---|---|
| Companion apps | MacThing, Carthios | What counts is the firmware below them. |
| Based on the stock software | Thing Labs/DeskThing image, GlanceThing | Spotify's U-Boot and partition table stay: the tool installs into slot B (the backup keeps the old slot-B content), and preset 1 starts that firmware from slot A. A changed boot command or `active_slot=_b` stops the tool before any write. |
| Own bootloader and a GPT | BridgeThing, Nocturne (yocto-superbird: mainline U-Boot, GPT) | Not supported: PlayThing needs Spotify's U-Boot, the stock layout, and the stock root file system. Restore the stock software first with that project's instructions. In mask-ROM mode superbird-tool's U-Boot writes an Amlogic partition table at 36 MiB, which can damage such a firmware: the tool asks before this step. |

## Tested (on my Car Thing)

- First install from mask-ROM mode with the full backup, restore to stock, updates, and the
  change between PlayThing and superbird-android.
- After the install: SELinux enforcing with no enforcing denials, `/data` kept, the Bluetooth
  firmware from `system_a`, the Mali driver and the decoder firmware loaded, CarPlay with the
  downloaded DiPlay.

Not tested: the burn-mode button change on a Car Thing that does not have it yet (the command is
superbird-tool's), other panels, other eMMC sizes.

## Make a release

`docs/build.md`, then:

```sh
scripts/kernel.sh <repo mount>/scripts/kernel-source-bundle.sh   # out/release/kernel-source.tar.xz
scripts/aosp.sh <repo mount>/scripts/aosp-gpl-source-bundle.sh   # out/release/aosp-gpl-source.tar.xz
python3 scripts/make-release.py --version <version> \
    --extra out/release/kernel-source.tar.xz out/release/aosp-gpl-source.tar.xz
```

`make-release.py` refuses a ramdisk with a developer ADB key (`--private` for a private
release).
