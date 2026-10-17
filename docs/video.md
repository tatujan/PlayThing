# Hardware video decoder

H.264 is decoded by the S905D2's video decoder (Amlogic VDEC), not by the CPU. In a CarPlay
session (800 × 480, 8-11 Mbit/s) the decoder gives up to 60 fps with a decode time of about
5 ms per frame (p50), and the decoder thread uses about 6 % of one core.

## Kernel side

- The decoder drivers are Amlogic's `media_modules` (commit `810d965`, `scripts/media-modules.sh`),
  built as modules for my kernel: `media_clock`, `firmware`, `decoder_common`, `stream_input`,
  `amvdec_mh264` (multi-instance H.264), and `amvdec_ports` (a V4L2 memory-to-memory decoder,
  `/dev/video26`).
- `init.superbird.rc` loads them in `post-fs`, early, because the firmware loader needs an
  order-4 allocation that can fail later. `amvdec_mh264` gets `fast_output_enable=8`: each
  frame comes out at once, with no frame of delay (I and P frames only; CarPlay has no
  B-frames). Do not unload `decoder_common`: `rmmod` hangs.
- The firmware `video_ucode.bin` (Amlogic) loads from `/vendor/lib/firmware/video/`. It is not
  in the published image; `flash.py` adds it (`docs/release.md`).
- Memory: the boot area sets `codec_mm_cma` to 64 MiB (the stock 308 MiB cannot be allocated).
  A CarPlay session uses about 48 MiB of it. CMA holds movable pages while the decoder is idle.
- `/dev/video26` belongs to `mediacodec:camera` (`ueventd.superbird.rc`).

## Android side: `OMX.google.cpt.h264.decoder`

`device/spotify/superbird/codecs/cpth264dec/` (`libstagefright_soft_cpth264dec`), registered
in the software OMX plugin (`patches/aosp/frameworks/av/0003-*`) and first in
`media_codecs.xml`, so MediaCodec chooses it for `video/avc`.

- The name has the `OMX.google.` prefix, so ACodec uses its software renderer path: byte
  buffers, which MediaCodec copies to the surface. This works with the display path, which has
  no overlay plane.
- Input: each access unit goes into a V4L2 OUTPUT buffer, with the OMX timestamp. Codec config
  (SPS/PPS) is kept and sent again after a flush.
- Output: the decoder writes NV21 (V before U, although V4L2 reports NV12); the component copies
  each frame to I420 in the OMX output buffer. After an input frame it waits a short time for its
  picture, so that the picture leaves at once, with no second thread.
- Capture buffers: cached, contiguous memory from the ION heap `codec_mm_ion` (ID 5), given to
  the decoder as `V4L2_MEMORY_USERPTR` (videobuf2 invalidates the CPU cache at QBUF and DQBUF).
  A frame copy then takes about 0.5 ms; from the driver's uncached buffers it took about 5 ms.
  Release order: STREAMOFF, REQBUFS 0, unmap, close (buffers still queued at process exit stay
  allocated until a reboot).
- Fallback: after two hardware failures (for example too few capture buffers), the component
  refuses to start until the codec service restarts, and MediaCodec uses the software decoder
  `OMX.google.h264.decoder`.

Properties (for tests):

| Property | Default | Effect |
|---|---|---|
| `persist.plaything.hw_avc` | `true` | `false`: the component does not start; MediaCodec uses the software decoder |
| `persist.plaything.vdec_swap_uv` | `true` | `false`: NV12 chroma order |
| `persist.plaything.vdec_ion` | `true` | `false`: the driver's own (uncached) capture buffers |
| `persist.plaything.avc_decode_order` | `1` | the software decoder outputs in decode order (lower latency for streams without B-frames) |

## Test tool

`scripts/device/cpt-vdec.c` decodes an H.264 Annex-B file through `/dev/video26` with no
Android, and prints frame rate, latency (p50/p90/max), CPU load, and the frame copy time. Build
it with `scripts/device-tools.sh` and push it with adb.
