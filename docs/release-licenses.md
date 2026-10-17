The image contains only parts whose licenses allow redistribution:

| Part | License | Source code |
|---|---|---|
| My device files, HWC, apps, scripts | GPL-3.0-or-later; files based on AOSP code: Apache-2.0 | This project's repository |
| Android 9 (AOSP `android-security-9.0.0_r76`) and my AOSP patches | Apache-2.0 and the licenses of its parts (in the image: Settings, About, Legal information) | [android.googlesource.com](https://android.googlesource.com), tag `android-security-9.0.0_r76`; my patches: `patches/aosp/` in the repository. The GPL and LGPL parts of AOSP in the image (for example e2fsprogs, iptables, iproute2, dnsmasq, libnl, OpenJDK `libcore`): `aosp-gpl-source.tar.xz` in this folder, as built |
| Linux 4.9.113 (Spotify kernel) and my patches; Mali kbase r16p0; Amlogic decoder modules | GPL-2.0 | `kernel-source.tar.xz` in this folder: the kernel tree as built, its configuration, and the two module trees |
| Mali gralloc (Arm) with my patch | Apache-2.0 | [codewalkerster/android_hardware_amlogic_gralloc](https://github.com/codewalkerster/android_hardware_amlogic_gralloc) `e2821da`; my patch: `patches/aosp/hardware/amlogic/gralloc/` |

These parts are **not** in this folder or in the image. `flash.py` downloads the first three on
your computer from the public sources below; Android reads the last one from your own Car Thing.

| Part | Owner | Source |
|---|---|---|
| Mali-G31 userspace driver `libGLES_mali.so` (r16p0) | Arm | [codewalkerster/android_vendor_amlogic_common_gpu-lib](https://github.com/codewalkerster/android_vendor_amlogic_common_gpu-lib) `42d8ae4` |
| Video decoder firmware `video_ucode.bin` | Amlogic | [codewalkerster/android_hardware_amlogic_media_modules](https://github.com/codewalkerster/android_hardware_amlogic_media_modules) `810d965` |
| DiPlay 0.2.20, the CarPlay app (PlayThing only; GPL-3.0, with AGPL-3.0 parts). Not in the image, because its release APK contains a CarPlay accessory identity. | DiPlay's author | [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) release v0.2.20 |
| Bluetooth firmware `BCM20703A2.hcd` | Broadcom (Cypress) | the stock software of your Car Thing (`system_a`, read-only) |
