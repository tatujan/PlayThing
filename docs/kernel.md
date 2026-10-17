# Kernel

## Source

- [spsgsb/kernel-common](https://github.com/spsgsb/kernel-common), branch
  `buildroot-openlinux-201904-g12a`, commit `9a2e9190b35c`: Spotify's Car Thing kernel, Linux
  4.9.113 (Amlogic's G12A release of April 2019 with Spotify changes). It already drives the
  panel, touch, knob, buttons, and Bluetooth UART.
- The production configuration is `arch/arm64/configs/superbird_evt_defconfig`.
- Out-of-tree modules: the Mali kbase driver r16p0
  ([codewalkerster/android_vendor_amlogic_common_gpu](https://github.com/codewalkerster/android_vendor_amlogic_common_gpu),
  `bifrost/r16p0/kernel`) and the Amlogic decoder drivers
  ([codewalkerster/android_hardware_amlogic_media_modules](https://github.com/codewalkerster/android_hardware_amlogic_media_modules),
  commit `810d965`, the pair of the media headers in Spotify's kernel).

## Configuration

`superbird_evt_defconfig`, then the AOSP Pie fragments (`kernel/configs`, `p/android-4.9/`:
`android-base.cfg`, `android-base-arm64.cfg`), then `kernel/configs/superbird-android.cfg`:

| Need | Options |
|---|---|
| Android base | binder, ashmem, and the other required options of the AOSP fragments |
| tmpfs with labels (`/dev`, `/mnt`) | `TMPFS`, `TMPFS_POSIX_ACL`, `TMPFS_XATTR` |
| Storage | `SDCARD_FS`, `FUSE_FS` |
| Graphics fences | `SW_SYNC`, `AMLOGIC_MEDIA_FB_OSD_SYNC_FENCE` (the HWC's present fences) |
| Memory | `MEMCG`, `MEMCG_SWAP` (lmkd), `ZRAM`, `ZSMALLOC`; the in-kernel low-memory killer stays off |
| DiPlay's USB network to the iPhone | `TUN` (`VpnService`) |
| USB Ethernet (ADB over TCP) and USB audio | `USB_USBNET`, `USB_NET_AX88179_178A`, `USB_RTL8152`, `USB_NET_CDCETHER`, `SND_USB_AUDIO`. Not `USB_NET_CDC_NCM`: it would take the iPhone's NCM interface, which DiPlay drives from user space. |
| Video decoder | `AMLOGIC_VIDEOBUF_RESOURCE`, `AMLOGIC_VIDEOBUF2_ION`, `AMLOGIC_IONVIDEO`, `AMLOGIC_MEDIA_GE2D`, `AMLOGIC_MEDIA_MULTI_DEC` |
| Debug | `KALLSYMS` (symbols in pstore crash logs) |
| Security | `ARM64_SW_TTBR0_PAN` |

`scripts/kernel-android-check.sh` compares a `.config` with the AOSP fragments.

## Patches (`patches/kernel/spotify-4.9/`)

| Patch | Why |
|---|---|
| 0001 vpu: HDMI ISO table initializers | The table had positional values in the wrong fields; `vpu_probe` crashed when U-Boot had not powered the VPU (for example in a RAM boot). |
| 0002 tlsc6x: standard touchscreen axis properties | The touch reports portrait coordinates; with `touchscreen-inverted-x` and `touchscreen-swapped-x-y` (set by the boot area) it reports landscape coordinates that match the rotated display. |
| 0003 g12a USB3 PHY: `force-host-mode` | The USB-C port has no CC controller, so it stays in device mode. With this property the PHY connects port 0 to the xHCI host, without VBUS: the hub powers the Car Thing. |
| 0005 rotary-encoder: key codes per step | With `linux,key-codes = <ccw cw>`, each knob step is one key press (Android does not turn wheel events into keys). |
| 0006 v4l_util: select `VIDEOBUF_GEN` | Without it, the kernel does not link with the decoder options. |
| 0007 xHCI: "no TDs queued" as a debug message | DiPlay cancels USB bulk reads often; the warning filled the kernel log (about 300 lines per second). |

## Build

The kernel builds in the native arm64 container (`containers/kernel-4.9`, `scripts/kernel.sh`)
with `CC=gcc-6`: the Amlogic drivers build with `-Werror`, and GCC 7 adds warnings.

```sh
cd /kernel/spotify-4.9            # the Spotify tree with patches/kernel/ applied
out=/kernel/out/android
make ARCH=arm64 CC=gcc-6 O=$out superbird_evt_defconfig
ARCH=arm64 scripts/kconfig/merge_config.sh -m -O $out $out/.config \
  <aosp kernel/configs>/p/android-4.9/android-base.cfg \
  <aosp kernel/configs>/p/android-4.9/android-base-arm64.cfg \
  <this repository>/kernel/configs/superbird-android.cfg
make ARCH=arm64 CC=gcc-6 O=$out olddefconfig
make ARCH=arm64 CC=gcc-6 O=$out -j8 Image Image.gz superbird_evt_512.dtb modules
```

Mali kbase module (`mali_kbase.ko`, installed as `/vendor/lib/modules/mali.ko`):

```sh
B=/kernel/build/kbase-r16p0       # a copy of bifrost/r16p0/kernel
make -C /kernel/out/android ARCH=arm64 CC=gcc-6 M=$B/drivers/gpu/arm/midgard \
  EXTRA_CFLAGS="-DCONFIG_MALI_PLATFORM_DEVICETREE -DCONFIG_MALI_MIDGARD_DVFS -DCONFIG_MALI_BACKEND=gpu -I$B/include" \
  EXTRA_LDFLAGS+="--strip-debug" \
  CONFIG_MALI_MIDGARD=m CONFIG_MALI_PLATFORM_DEVICETREE=y CONFIG_MALI_MIDGARD_DVFS=y CONFIG_MALI_BACKEND=gpu \
  -j8 modules
```

Decoder modules: `scripts/media-modules.sh`. Then `scripts/kernel-export.sh` copies `Image`,
`Image.gz`, the DTB, and `mali_kbase.ko` to `out/kernel/` for the image build (`docs/build.md`).

`init.superbird.rc` loads the modules early in the start (`post-fs`): `mali.ko`, then the
decoder modules in dependency order, before Android fragments the memory (the decoder firmware
loader needs an order-4 allocation).
