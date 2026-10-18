# Boot

PlayThing does not replace the Car Thing's bootloader or its partition table. Spotify's boot
chain (BL2, BL31, U-Boot) starts it, and the stock software stays in slot A.

## Buttons at power-on

The Car Thing has no battery: it starts when it gets power. Hold the buttons while you connect
it, and for about 3 seconds after. The presets are the row on the top edge, preset 1 at the left
end, preset 4 at the right end.

| Hold | Result |
|---|---|
| nothing | Android (PlayThing or superbird-android) |
| preset 1 | the stock software from slot A |
| preset 4 | USB burn mode of Spotify's U-Boot (for `flash.py`); the first install adds it to the env |
| presets 1 and 4 | mask-ROM USB mode (`1b8e:c003`); the SoC boot ROM, independent of the eMMC |

Both USB modes need a direct USB connection to the computer (not through a hub).

## Where Android is

Android uses the stock B slot, `system_b` (516.04 MiB at `0x3120b000`), which the stock software
does not use:

| Offset in `system_b` | Size | Content |
|---|---|---|
| 0 | 484 MiB | Android system file system (ext4, `system.raw`) |
| `0x1e40b000` | 32 MiB | Boot area (`boot-area.img`, `scripts/mk-systemb-tail.py`) |

Boot area layout:

| Offset | Content |
|---|---|
| 0 | Boot variables (text for `env import -t`, at most 64 KiB) |
| 1 MiB | Kernel `Image`, uncompressed (at most 21 MiB) |
| 22 MiB | Ramdisk as a U-Boot uImage (at most 6 MiB) |
| 28 MiB | 4 MiB of zeros, for debug output |

`/data` is a 1 GiB image file on the stock `data` partition (`docs/persistent-data.md`).

## The U-Boot env change

`flash.py install` (first install) changes two variables, and adds the preset-4 burn mode if it
is missing:

```
cpt_boot=mmc dev 1 0; mmc read 0x08000000 0x27b0b0 0x80; env import -t 0x08000000 0x10000; run cpt_script
bootcmd=if gpio input GPIOA_0; then run check_charger; else run cpt_boot; run check_charger; fi
storeargs=<stock value> if gpio input GPIOA_3; then run update; fi;
```

- `cpt_boot` reads the boot variables from the boot area (sector `0x27b0b0`) into the RAM env
  (no save) and runs `cpt_script`. Later changes to the boot steps are thus `system_b` writes,
  not env writes.
- If `cpt_script` fails (for example, the boot area is not complete yet), `bootcmd` continues
  with `check_charger`, the stock boot.
- The env is 64 KiB with a CRC-32, at 116 MiB on the eMMC.

## What `cpt_script` does

1. `imgread dtb boot_a 0x1000000`: decrypt the stock device tree from the stock boot image. So
   PlayThing always uses the unit's own device tree (panel, touch, and so on).
2. Edit the device tree in RAM (`fdt`):
   - remove the Android early-mount node (`/firmware/android`): the system is a late mount;
   - ramoops: 512 KiB console, no ftrace (pstore after a crash);
   - touch: `touchscreen-inverted-x`, `touchscreen-swapped-x-y` (landscape coordinates);
   - `codec_mm_cma`: 64 MiB instead of 308 MiB (the stock size cannot be allocated);
   - keys: knob steps send `KEY_LEFT`/`KEY_RIGHT`, back sends `KEY_BACK`, presets 1-3 send
     previous/play-pause/next, preset 4 sends `KEY_F1` (DiPlay's Siri key), mute sends
     `KEY_MUTE` (`docs/input.md`);
   - USB host mode: the USB-C port becomes a host with power in (`docs/hardware.md`).
3. Read the kernel and the ramdisk from the boot area, set `bootargs`
   (`scripts/ramboot/env-android.txt`: `androidboot.hardware=superbird`, panel and display
   settings from the stock env, `zram.num_devices=2`, `loglevel=4`), and `booti`.

## Partition tables

The kernel builds its partitions from the Amlogic partition table (MPT) at 36 MiB. A U-Boot
writes the table when it starts: its fixed partitions (`bootloader`, `reserved`, `env`) and the
partitions of the device tree, each one 8 MiB after the one before it.

Two kinds of Spotify U-Boot are in the field:

| U-Boot (example) | Device tree | Table |
|---|---|---|
| older (`v1.0-57`, my unit) | `logo`, `dto`, `dtbo_a`/`dtbo_b` of 8 MiB | the table below, without `fip_a`/`fip_b` |
| newer (`v1.0-74`) | `logo`, `dtbo_a`/`dtbo_b` of 4 MiB, no `dto` | adds `fip_a` (132 MiB) and `fip_b` (144 MiB); `logo` at 156 MiB, `dtbo_a` 172, `dtbo_b` 184 |

In both, `vbmeta_a` and everything after it (`boot_a`, `system_a`, `system_b`, `misc`,
`settings`, `data`) are at the same places. superbird-tool's USB U-Boot (loaded in mask-ROM
mode) always adds `fip_a` and `fip_b`; on a Car Thing with the older U-Boot its table therefore
has every partition from `logo` 24 MiB further. The next start with the Car Thing's own U-Boot
writes the real table back. Thus:

- write only by raw offset (as `flash.py` and `scripts/sbt-raw-write.py` do), never by
  partition name in superbird-tool's burn mode;
- `flash.py` writes `system_b` only when the table of the Car Thing's own U-Boot has it at
  `0x3120b000` (`docs/release.md`, "Partition table check");
- do not RAM-boot a kernel after a mask-ROM entry without a normal start first.

The newer layout is from the public peek corpus and superbird-tool's partition list; I tested
PlayThing only on a Car Thing with the older U-Boot.

| Partition | Offset | Size |
|---|---|---|
| `bootloader` | 0 | 4 MiB |
| `reserved` (MPT at its start) | 36 MiB | 64 MiB |
| `env` | 116 MiB | 8 MiB |
| `logo` (older U-Boot) | 132 MiB | 8 MiB |
| `dto` (older U-Boot) | 148 MiB | 8 MiB |
| `dtbo_a`, `dtbo_b` (older U-Boot) | 164, 180 MiB | 8 MiB each |
| `vbmeta_a`, `vbmeta_b` | 196, 205 MiB | 1 MiB each |
| `boot_a`, `boot_b` | 214, 238 MiB | 16 MiB each |
| `system_a` | 262 MiB (`0x10600000`) | 516.04 MiB |
| `system_b` | 786.04 MiB (`0x3120b000`) | 516.04 MiB |
| `misc` | 1310.09 MiB | 8 MiB |
| `settings` | 1326.09 MiB | 256 MiB |
| `data` | 1590.09 MiB (`0x63616000`) | to the end of the user area (3776 MiB on my unit) |

## Debugging a start that hangs

- Android writes its kernel console to pstore (ramoops). After a crash, read it from burn mode:
  `scripts/sbt-readmem.py`, then `scripts/ramoops-decode.py`.
- `scripts/mk-systemb-tail.py --selinux permissive` makes a boot area with SELinux permissive.
- `scripts/sbt-ramboot.py` starts a kernel, ramdisk, and boot variables from RAM, with no write.
- Logs of earlier starts: `/data/misc/logd/` (`docs/known-issues.md`).
