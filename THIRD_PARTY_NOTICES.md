# Third-party notices

The project's own files are licensed under the GNU GPL version 3 or later (`LICENSE`), except
where a file says otherwise. This file lists the third-party material in this repository, and
the third-party software that the build scripts and the flash tool download or use.

## In this repository

### Phosphor Icons (MIT)

Icon path data from [Phosphor Icons](https://phosphoricons.com) (`@phosphor-icons/core` 2.1.1),
Copyright (c) 2023 Phosphor Icons, MIT license (`LICENSES/MIT.txt`): `device/spotify/superbird/apps/CptHome/res/drawable/ic_*.xml`, `device/spotify/superbird/apps/CptKeyboard/res/drawable/ic_*.xml`, `device/spotify/superbird/apps/CptManager/res/drawable/ic_*.xml`, `device/spotify/superbird/apps/ThingPod/res/drawable/ic_*.xml`, and `device/spotify/superbird/diplay/carplay-home-icon.*`.

### Android Open Source Project (Apache-2.0)

- `device/spotify/superbird/audio/hal/audio_hw.c` is based on AOSP
  `hardware/libhardware/modules/audio/audio_hw.c`, Copyright (C) 2011 The Android Open Source
  Project.
- `device/spotify/superbird/codecs/cpth264dec/SoftCptAVC.*` follow AOSP
  `frameworks/av/media/libstagefright/codecs/avcdec/SoftAVCDec.*`, Copyright 2015 The Android
  Open Source Project.
- `patches/aosp/` changes AOSP projects and the Arm gralloc; the patches are Apache-2.0
  (`patches/aosp/README.md`).

These files are licensed under the Apache License 2.0 (`LICENSES/Apache-2.0.txt`).

### Linux kernel patches (GPL-2.0-only)

`patches/kernel/` (`patches/kernel/README.md`).

### Elevation data in the launcher wallpaper

`device/spotify/superbird/apps/CptHome/res/drawable-nodpi/wallpaper.png` shows contour lines
made from
[Terrain Tiles on AWS](https://registry.opendata.aws/terrain-tiles/) (Tilezen/Mapzen). For this
area (Hyalite Peaks, Montana) the sources are USGS 3DEP and SRTM, which are in the public
domain. Attribution: elevation data from the U.S. Geological Survey 3D Elevation Program and
NASA SRTM, served by Terrain Tiles; see
[the Terrain Tiles attribution](https://github.com/tilezen/joerd/blob/master/docs/attribution.md).

## Downloaded or used by the build and the flash tool, not in this repository

| Component | Source | License |
|---|---|---|
| Android 9 (AOSP) | `android-security-9.0.0_r76` | Apache-2.0 and others (see AOSP) |
| Linux kernel 4.9.113 | [spsgsb/kernel-common](https://github.com/spsgsb/kernel-common) | GPL-2.0 |
| Mali kbase kernel driver r16p0 | [codewalkerster/android_vendor_amlogic_common_gpu](https://github.com/codewalkerster/android_vendor_amlogic_common_gpu) | GPL-2.0 |
| Gralloc | [codewalkerster/android_hardware_amlogic_gralloc](https://github.com/codewalkerster/android_hardware_amlogic_gralloc), `manifests/plaything.xml` | Apache-2.0 (Arm, AOSP). Its `amlogic/` files have Amlogic's own terms; the build does not use them (`patches/aosp/hardware/amlogic/gralloc/`). |
| Video decoder modules | [codewalkerster/android_hardware_amlogic_media_modules](https://github.com/codewalkerster/android_hardware_amlogic_media_modules), `scripts/media-modules.sh` | GPL-2.0 |
| Mali userspace driver `libGLES_mali.so` | [codewalkerster/android_vendor_amlogic_common_gpu-lib](https://github.com/codewalkerster/android_vendor_amlogic_common_gpu-lib); `scripts/flash.py` downloads it on the user's computer | Proprietary (Arm). Not in this repository and not in the image (`docs/release.md`). |
| Video decoder firmware `video_ucode.bin` | the media_modules repository above; `scripts/flash.py` downloads it | Proprietary (Amlogic). Not in this repository and not in the image. |
| Bluetooth firmware `BCM20703A2.hcd` | read at run time from the stock software of your own Car Thing (`docs/bluetooth-audio.md`) | Proprietary (Broadcom/Cypress). Not in this repository and not in the image. |
| superbird-tool | [thinglabsoss/superbird-tool](https://github.com/thinglabsoss/superbird-tool) (USB burn mode; `scripts/flash.py` downloads its USB module) | Unlicensed (public domain dedication), with an MIT-licensed part |
| pyusb, pyamlboot | [pyusb/pyusb](https://github.com/pyusb/pyusb), [superna9999/pyamlboot](https://github.com/superna9999/pyamlboot) (installed by `scripts/flash.py`) | BSD-3-Clause, Apache-2.0 |
| DiPlay | [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) release APK; `scripts/flash.py` downloads it on the user's computer | GPL-3.0, with parts under AGPL-3.0 (see its repository). Not in this repository and not in the image: the release APK contains a CarPlay accessory identity. |

## Built images

The image has no proprietary part: placeholders take the place of the Mali driver, the video
firmware, and (PlayThing) the DiPlay APK, and `scripts/flash.py` adds them on the user's
computer (`docs/release.md`). A
release folder (`scripts/make-release.py`) can be published with the source code of its GPL
parts (`docs/release-licenses.md`).

## Trademarks

Spotify and Car Thing are trademarks of Spotify AB. Apple, CarPlay, and iPhone are trademarks
of Apple Inc. Android is a trademark of Google LLC. This project is not affiliated with or
endorsed by any of them.
