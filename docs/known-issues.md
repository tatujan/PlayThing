# Known issues

## Music through a hub's AUX jack

With a USB-C hub that has Power Delivery, data ports, and a 3.5 mm AUX jack, no sound came out of
the jack. The image has the USB audio parts (the HAL `audio.usb.default`, the USB audio policy,
`CONFIG_SND_USB_AUDIO`), but USB audio output was never tested on the Car Thing. Possible causes:
the hub's jack is analog over USB-C (no USB sound card in the hub), Android routes the sound to a
connected Bluetooth device first, or the USB sound card does not reach the audio policy. The
cause is not known yet.

## CarPlay USB drops in the car

In the car, the CarPlay session sometimes ends. The cause is not known yet. Facts so far:

- At the desk, one drop showed DiPlay's USB transfers to the iPhone waiting about 2 s each for a
  few seconds, then the session ended without a USB removal. DiPlay reads with short timeouts,
  and each cancelled read gave an xHCI warning (now a debug message, kernel patch `0007`).
- The Bluetooth chip is on a UART, not on USB, so Bluetooth and USB do not share a bus. Possible
  indirect causes: the iPhone's own Bluetooth link to the car (iOS can change audio routes or end
  the session), or a weak supply that resets the hub (then all its devices disconnect).

To find the cause, read the logs (see "Logs" below) and look at the time of the drop for:
`USB disconnect` of the iPhone only, or of the hub and all devices (power); DiPlay's session end
(`xcertplay-*` tags) and USB timeouts; Bluetooth connections of the car stereo (`A2dpService`,
`HeadsetService`) just before it.

## Calls: delay and echo

CarPlay calls work over A2DP with the built-in microphones, but:

- The delay is about 1 s in each direction: DiPlay's call buffer is 4 × Android's minimum for the
  A2DP output (about 1 s), plus the A2DP delay.
- No echo canceller runs on the Car Thing. DiPlay has an experimental echo cancellation option.
- An alternative: DiPlay's setting "CarPlay audio over the car's Bluetooth" (experimental). Then
  the iPhone keeps its own Bluetooth link to the car for audio and calls, with the car's
  microphone and echo canceller.

## Other

- `screencap` gives a distorted image (the landscape frame in a portrait buffer).
- Some Car Things have a different panel (Holitech). The image uses the unit's own device tree,
  but only the ST7701S panel is tested.
- The stock software must be in slot A; `flash.py` refuses a unit whose stock software runs from
  slot B.
- I tested PlayThing only on a Car Thing with the older Spotify U-Boot (`v1.0-57`). Car Things
  with the newer U-Boot (`v1.0-74`, with `fip_a`/`fip_b` in the partition table) have the same
  places for the partitions that PlayThing uses, and the same device-tree nodes; `flash.py`
  supports them since `0.1.0-beta.2`. Version `0.1.0-beta` of the tool stopped on them ("the
  partition table is still the superbird-tool one"). On a unit with the stock software v8
  (board revision 4), PlayThing up to `0.1.0-beta.4` started Android, but the screen showed
  noise: U-Boot applied the stock DRM overlay, so `/dev/graphics/fb0` was missing.
  `0.1.0-beta.5` skips that overlay (`docs/boot.md`, "Device-tree overlays"); with it, PlayThing
  works on that unit (a user's report).

## Logs

The image keeps its logs over a power cut: `logcatd` writes the Android and kernel logs to
`/data/misc/logd/` (16 files of 1 MB; `ro.logd.kernel=true`,
`persist.logd.logpersistd=logcatd`). After a problem, at the desk, as root:

```sh
adb pull /data/misc/logd/
```
