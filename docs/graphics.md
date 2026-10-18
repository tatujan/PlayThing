# Graphics

| Part | What | Source |
|---|---|---|
| GPU kernel driver | Mali kbase r16p0 (`/vendor/lib/modules/mali.ko`, `/dev/mali0`) | codewalkerster `android_vendor_amlogic_common_gpu`, `bifrost/r16p0/kernel`, built for my kernel (`docs/kernel.md`) |
| GPU userspace driver | `libGLES_mali.so` r16p0 (`/vendor/lib/egl/`, OpenGL ES 3.2, 32-bit, gralloc1) | Arm's binary from codewalkerster `android_vendor_amlogic_common_gpu-lib`, `dvalin_ion/libGLES_mali_default_8a_32-p-r16p0gralloc1.so`. Not in the image: `flash.py` adds it (`docs/release.md`). The blob and the kbase driver are the same Arm DDK release (r16p0); keep them together. |
| gralloc | `gralloc.aosp_superbird.so` (gralloc1, ION) | Arm's Mali gralloc from codewalkerster `android_hardware_amlogic_gralloc`, with my patch |
| Hardware composer | `hwcomposer.superbird.so` (HWC2) | my own: `device/spotify/superbird/hwcomposer/` |

The donor of the GPU parts is Hardkernel's ODROID-C4 Android 9 port (S905X3, the same
Mali-G31), branch `g12_9.0.0_32_master`, which also uses a 32-bit userspace on a 64-bit kernel.

## Composition

The Amlogic display engine (OSD) has no IOMMU, cannot scan out scattered app buffers, and cannot
rotate. So SurfaceFlinger composes all layers with the GPU into one frame-buffer target (client
composition) and rotates the landscape UI onto the portrait panel. The GPU also draws the apps
(HWUI pipeline "Skia (OpenGL)"). Only the frame-buffer target must be physically contiguous.

## gralloc

Arm's Mali gralloc with Amlogic changes, built without Amlogic's extension (`amlogic/`, whose
license does not allow redistribution; `patches/aosp/hardware/amlogic/gralloc/0001-*`). Heap
choice: `GRALLOC_USAGE_HW_FB` buffers (the frame-buffer target) come from the ION DMA heap
(CMA, contiguous); all other buffers come from the ION system heap. Arm's default rule would put
every `GRALLOC_USAGE_HW_COMPOSER` buffer in CMA, and SurfaceFlinger gives that usage to every
app buffer.

## Hardware composer (`hwcomposer/hwcomposer.cpp`)

A small HWC2 module, loaded by the AOSP composer service
(`android.hardware.graphics.composer@2.1-service`, `ro.hardware.hwcomposer=superbird`). It needs
the Amlogic framebuffer driver (`meson-fb`, `/dev/graphics/fb0`); the boot script keeps that
driver on Car Things whose stock software v8 moves the display to DRM (`docs/boot.md`,
"Device-tree overlays"). Without `/dev/graphics/fb0`, the composer service does not start,
SurfaceFlinger waits for it, and the screen shows noise after the logo.

- **Validate:** every layer becomes `CLIENT`.
- **Present:** the frame buffer driver's sync-render ioctl (`0x4519`, single-layer mode, direct
  compose) on `/dev/graphics/fb0`. It takes the client target's dma-buf, waits for the acquire
  fence, scans the buffer out with no copy, and returns a fence that signals after the vsync that
  shows the buffer. That fence is the present fence; SurfaceFlinger releases the previous client
  target with it. The kernel closes the acquire fence that it gets, so the HWC gives it a copy.
- **Vsync:** a thread waits with `FBIO_WAITFORVSYNC_64` (the vsync time, CLOCK_MONOTONIC).
- **Display attributes:** from `/sys/class/display/vinfo`: 52 × 86 mm (DPI 234.5 × 236.3),
  60 Hz.
- **Power off:** the OSD layer goes off; the next frame turns it on.
- **Buffer data:** the gralloc1 functions for size, format, and stride; the dma-buf is the first
  fd of the handle.
- `dumpsys SurfaceFlinger` shows a status line: frames, failures, the last error.

The ioctl numbers and the request structure follow the frame buffer driver of the Spotify kernel
(`drivers/amlogic/media/osd/`).

## Notes

- `screencap` gives a distorted image on this device (the landscape frame in a portrait buffer).
  Check the panel itself.
- SELinux: the composer service needs read access to `/sys/class/display`, `/dev/vndbinder`, and
  `setsched` (`docs/selinux.md`).
