# Hardware

The facts below were measured on my Car Thing. Units can differ (for example, another panel
model on some units).

| Part | What PlayThing uses |
|---|---|
| SoC | Amlogic S905D2 (G12A), 4 × Cortex-A53, up to 1.8 GHz |
| RAM | 512 MiB; `MemTotal` 499,684 kB. CMA pools of the stock device tree: `ion-dev` 128 MiB (graphics buffers), `di_cma` 40 MiB, `vm0_cma` 32 MiB, `meson-fb` 8 MiB, and smaller ones; PlayThing adds `codec_mm_cma` 64 MiB for the video decoder. CMA holds movable pages while no driver uses it. |
| eMMC | 4 GB; user area 3776 MiB (`docs/boot.md` has the partitions) |
| GPU | Arm Mali-G31 (Bifrost); the kbase driver reports one core group, `r0p0`, DVFS 285-846 MHz (`docs/graphics.md`) |
| Panel | 480 × 800, portrait-native, `ST7701S` (`lcd_8`), MIPI DSI, 52 × 86 mm, 60 Hz. Frame buffer `/dev/graphics/fb0` (Amlogic OSD), no DRM. The fb0 origin is at the bottom-left of the landscape screen, so a landscape point (X, Y) is at fb0 (479 - Y, X): SurfaceFlinger turns the UI with `ROT_90` to 800 × 480 landscape. |
| Touch | `tlsc6x`, I2C bus 0, address `0x2e`, multi-touch type B. My kernel patch maps it to landscape (`docs/input.md`). |
| Knob | Rotary encoder (`rotary@0`, `GPIOZ_8`/`GPIOZ_9`) and a press switch (`GPIOZ_7`) |
| Buttons | `gpio-keys-polled`: presets 1-4 (`GPIOA_0`-`GPIOA_3`), back (`GPIOA_5`), mute (`GPIOAO_3`) |
| USB | One USB-C port, also the power input. Stock: device mode only. PlayThing: host mode with power in, through a USB-C hub with Power Delivery input (tested: Anker 6-in-1 with a PD charger); the Car Thing gives no VBUS. The xHCI host controller is `ff500000.dwc3`; the device controller (UDC, for ADB over USB in the stock device mode) is `ff400000.dwc2_a`. |
| Bluetooth | Broadcom BCM20703A2 (HCI reports Cypress) on UART `/dev/ttyS1`, 3 Mbaud. Power: `GPIOX_17` (GPIO 493, `BT_EN`, active low). Address from eFuse (`/sys/class/efuse/mac_bt`). Firmware `BCM20703A2.hcd` in the stock root file system (`docs/bluetooth-audio.md`). |
| Wi-Fi | None |
| Audio | One ALSA card, `AML-AUGESOUND`: a PDM capture device with 4 microphones. No playback device (no speaker): audio goes to the car over Bluetooth, or to a USB audio adapter. |
| Light sensor | ams/TAOS TMD2772, I2C `2-0039`, kernel driver `tsl2x7x` (`docs/display-brightness.md`) |
| Backlight | `/sys/class/backlight/aml-bl`, PWM, inverted: a higher value gives less light |
| MFi chip | Apple Authentication 3.0 coprocessor, I2C bus 3, address `0x10`. Not used: DiPlay brings its own CarPlay identity. |
| Video decoder | Amlogic VDEC (H.264), through the media_modules kernel drivers (`docs/video.md`) |

## Stock software

Linux 4.9.113 and a 32-bit Buildroot userspace (glibc). Its root file system is in `system_a`
(ext4); PlayThing mounts it read-only for the Bluetooth firmware. The stock kernel modules (for
example `mali.ko`) do not load into my kernel build, because the symbol versions differ.
