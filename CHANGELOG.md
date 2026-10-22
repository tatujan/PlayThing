# Changelog

The changes of each PlayThing release. The releases are on GitHub, with the image and the flash
tool in one zip.

## 0.1.0-beta.6

- **Apps.** Droid-ify is the app store: it installs, updates, and removes apps without a prompt.
  An App manager shows the storage and the memory of each app; it can stop an app, clear its
  cache or its data, remove it, and clean up the caches of all apps (`docs/apps.md`).
- **ThingPod**, an iPod-style music player: the knob is the click wheel. It plays the music on
  the Car Thing and on USB sticks, and the car stereo shows the title and the cover over
  Bluetooth (`docs/thingpod.md`). The image now has Android's media library (MediaProvider).
- **Home screen.** The Apps tile opens an app drawer. A rotate button (next to the Bluetooth
  status) turns the screen in steps of 90 degrees, to landscape or portrait; the home screen has
  a portrait layout. While an iPhone is connected, the first press only shows a message: a
  rotation starts the CarPlay session again, and the iPhone can ask "Trust" again. A second
  press in 4 s rotates (`docs/home-screen.md`).
- **Fix: frozen screen after an app restart.** When the app in front stopped and started again
  at once (for example after `am force-stop`), the screen could stop until a restart. Cause:
  the hardware composer gave Android the fence of the next frame instead of the fence of the
  shown frame (`docs/graphics.md`).
- **Fix: kernel panic in the fence driver** (kernel patch 0008, `docs/kernel.md`). A restart of
  Android's display service could cause a kernel panic and a reboot.
- **Fix: screenshots and the rotation animation** showed a stretched part of the screen (a
  SurfaceFlinger patch for the turned panel).
- The boot script takes `panel_type` from the Car Thing's own U-Boot env (`lcd_8` when the env
  has none), for units with another panel entry (`docs/boot.md`).
- `python3 flash.py diag` also shows Android's bootloader message (`misc`) and the kind of file
  system on the stock data partition. The flash tool names Nocturne 4 and Mira (installed by
  Terbium) as other firmware that must be removed first.
- Docs: USB audio adapters (`docs/bluetooth-audio.md`, "USB audio").
- The system image and the boot area changed. An update keeps `/data`.

## 0.1.0-beta.5

- **Display fix for Car Things with the stock software v8** (newer Spotify U-Boot, for example
  board revision 4). There, U-Boot applied a device-tree overlay at start that moves the display
  to another driver (DRM), so the display of PlayThing could not start: the screen showed noise
  after the Spotify logo. The boot script now tells U-Boot to apply no overlay (only in RAM; the
  stock software is not changed).
- `python3 flash.py diag` also lists the device-tree overlays of the Car Thing.
- The system image is the same as in 0.1.0-beta.4; only the boot area changed.

## 0.1.0-beta.4

- `python3 flash.py diag` replays the ext4 journal in memory (it still writes nothing). After a
  power cut, the newest file sizes are only in the journal, so 0.1.0-beta.3 could show the log
  files with 0 bytes.
- The images are the same as in 0.1.0-beta.2.

## 0.1.0-beta.3

- `python3 flash.py diag` (hold preset 4 while you connect): a diagnostic folder for problem
  reports. It writes nothing. Post its `summary.txt`; send its `data/` folder (Android logs with
  device data) only privately.
- The images are the same as in 0.1.0-beta.2.

## 0.1.0-beta.2

- The flash tool works on Car Things with the newer Spotify U-Boot (with `fip_a`/`fip_b` in the
  partition table). 0.1.0-beta stopped on them with "STOPPED: the partition table is still the
  superbird-tool one".
- The tool writes only when the partition table of the Car Thing's own U-Boot has `system_b`
  where the tool writes. It detects the unplug by USB presence, and `python3 flash.py check`
  shows the full partition table.
- The images are the same as in 0.1.0-beta.

## 0.1.0-beta

- The first public version.
