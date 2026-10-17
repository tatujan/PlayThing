# Android configuration

Product `aosp_superbird` (`device/spotify/superbird/`), AOSP `android-security-9.0.0_r76`, build
variant `userdebug`.

## Base

- Android 9 Go: `core_tiny.mk` and `go_defaults_512.mk` (`ro.config.low_ram=true`, the low-RAM
  framework settings).
- 32-bit userspace (`armeabi-v7a`, `zygote32`) on the 64-bit kernel; the binder uses the 64-bit
  protocol (`TARGET_USES_64_BIT_BINDER`).
- No vendor partition and no full Treble: vendor files are in `/system/vendor`. `/system` is the
  ext4 file system at the start of `system_b`, read-only. The ramdisk has `init.superbird.rc`,
  `fstab.superbird`, `ueventd.superbird.rc`, `data-setup.sh`, and `bt-setup.sh`, because init
  reads them before `/system` is mounted.
- Display: 800 × 480 landscape on the portrait panel (`SF_PRIMARY_DISPLAY_ORIENTATION := 90`,
  read from the configstore service), density 240.

## Memory

| Item | Setting |
|---|---|
| Swap | zram0, 128 MiB (`fstab.superbird`) |
| Low-memory killer | `lmkd` in user space with memory cgroups; the in-kernel killer is off |
| Log buffers | 256 KiB each |
| Typical `MemAvailable` | about 190-210 MB after the start, about 120 MB during a CarPlay session |

## Start sequence (`rootdir/init.superbird.rc`)

| Stage | What happens |
|---|---|
| `on init` | USB gadget for ADB (configfs) |
| `on fs` | zram1 size, `mount_all` (`/system`, swap), read-only mount of the stock root file system (`system_a`) at `/mnt/vendor/stock-root` for the Bluetooth firmware |
| `on post-fs` | `/data` (`data-setup.sh`, `docs/persistent-data.md`); `mali.ko`; the decoder firmware path and the decoder modules (`docs/video.md`) |
| `on boot` | file owners for the backlight; a wake lock: the Car Thing has no battery, and the buttons cannot wake it, so it must not suspend |
| Bluetooth | `bt-setup.sh`: power on the chip (GPIO 493) and set the address from the eFuse |
| `sys.boot_completed=1` | `diplay-setup.sh` (PlayThing, `docs/diplay.md`) |

## Packages beyond `core_tiny`

SystemUI, Settings, VpnDialogs (DiPlay's VPN), PackageInstaller (Android needs exactly one
installer), `ethernet-service` (Ethernet through a USB adapter), `libcompiler_rt` (the zygote
preloads it), the HALs below, the tinyalsa tools, and the apps: CptKeyboard; in PlayThing also
DiPlay and CptHome; in superbird-android Launcher3Go.

| HAL | Implementation |
|---|---|
| Graphics | Mali gralloc, my HWC (`docs/graphics.md`) |
| Media codec | my OMX H.264 decoder (`docs/video.md`) |
| Audio | `audio.primary.superbird` (microphones), `audio.a2dp.default`, `audio.usb.default` (`docs/bluetooth-audio.md`) |
| Bluetooth | `android.hardware.bluetooth@1.0` with Broadcom `libbt-vendor` |
| Lights, sensors | `lights.superbird`, `sensors.superbird` (`docs/display-brightness.md`) |
| Other | keymaster 3.0, health 2.0, configstore 1.1 |

## Features and overlays

- Features: USB host, Bluetooth and Bluetooth LE, the light sensor, multi-touch, OpenGL ES AEP,
  and Wi-Fi: the Car Thing has no Wi-Fi, but SystemUI crashes without a Wi-Fi service; with the
  feature the service runs and stays disabled.
- PlayThing only: `android.hardware.type.television` (`permissions/plaything-knob.xml`). With it,
  DiPlay drives the CarPlay knob from the DPAD keys and tells the iPhone that the knob is the
  primary input; touch still works. The Android UI mode is then "television".
- Framework overlay: automatic brightness and its curve; `networkAttributes` without the mobile
  types, so Settings does not show "Mobile network" (no phone app); PlayThing only:
  `config_disableUsbPermissionDialogs` (no dialog for the iPhone).
- SettingsProvider overlay: automatic brightness on by default.

## AOSP patches (`patches/aosp/`)

| Patch | Why |
|---|---|
| `art/0001` | Host build under Rosetta: Rosetta ignores `MAP_32BIT`, so host `dex2oat` uses ART's low-4GB allocator on x86_64 too. |
| `frameworks/av/0001` | With `ro.audio.a2dp_in_communication=true`, calls in `MODE_IN_COMMUNICATION` (CarPlay calls through DiPlay) can use A2DP. |
| `frameworks/av/0002` | With `persist.plaything.avc_decode_order=1`, the software H.264 decoder outputs in decode order (lower latency for streams without B-frames). Only for the software fallback. |
| `frameworks/av/0003` | Registers my hardware H.264 decoder component. |
| `frameworks/base/0001` | With `ro.config.knob_sequential_focus=true`, knob LEFT/RIGHT that an app does not handle move the focus in focus order, as SHIFT+TAB and TAB (`docs/input.md`). |
| `frameworks/base/0002` | The zygote gives the Bluetooth process `CAP_NET_ADMIN` (the kernel has no Android paranoid-network groups), so Bluetooth tethering (PAN) can create its network interface. |
| `packages/apps/Settings/0001` | Settings opens in the television UI mode (it has no action bar there), and it hides its search bar when no search app is installed (the image has none). |
| `hardware/amlogic/gralloc/0001` | The gralloc without Amlogic's extension (`docs/graphics.md`). |

## Debug access

- ADB over TCP, port 5555, through the Ethernet port of the hub: the USB-C port is a host, so
  USB ADB is not available. Key authentication: put your public key at
  `device/spotify/superbird/adb_keys.pub` before the build, or accept the "Allow USB debugging?"
  dialog on the screen.
- Logs of earlier starts: `/data/misc/logd/` (`docs/known-issues.md`).
