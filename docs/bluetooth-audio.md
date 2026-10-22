# Bluetooth and audio

The Car Thing has no speaker. Audio goes to the car stereo (or a speaker) over Bluetooth A2DP,
or to a USB audio adapter (for example to the car's AUX input). The four built-in microphones
are the input for Siri and calls.

## Bluetooth

Android uses its own Bluetooth stack through the Bluetooth HAL, which opens the UART directly.
Nothing in the image runs `hciattach` or the kernel Bluetooth stack.

| Part | Setting |
|---|---|
| Chip | BCM20703A2 on `/dev/ttyS1` (`bluetooth:bluetooth`), 3 Mbaud; the firmware download runs at 115200 |
| Vendor library | Broadcom `libbt-vendor` (`BOARD_HAVE_BLUETOOTH_BCM`), settings in `bluetooth/vnd_superbird.txt`. UART sleep is off: the host does not control the chip's wake line. The file must not have empty lines. |
| Firmware | `/mnt/vendor/stock-root/lib/firmware/brcm/BCM20703A2.hcd`: `init` mounts the stock root file system (`system_a`) read-only, with no journal replay, so the image needs no Broadcom file. If the file is missing, the chip runs without its patch. |
| Power | `bt-setup.sh` sets `BT_EN` (GPIO 493) low for 0.1 s, then high, and waits 0.3 s, as the stock software does: after U-Boot the chip is off, and the board has no rfkill. |
| Address | `bt-setup.sh` reads `/sys/class/efuse/mac_bt` and sets `persist.service.bdroid.bdaddr`. |
| Name | "PlayThing" (superbird-android: "Car Thing"), `bluetooth/bdroid_buildcfg.h`. The device class stays the Android default (phone), which car stereos expect. |
| Profiles | A2DP source, AVRCP target, HFP audio gateway (some cars connect only to devices with HFP), GATT, and PAN (internet from a phone). The other profiles are off to save memory (`overlay/packages/apps/Bluetooth/res/values/config.xml`). |

Pairing: Android Settings → Bluetooth. A car stereo that asks for a PIN needs the keyboard
(`docs/keyboard.md`). The pairing keys are in `/data`, so they stay after a power cycle.

### Internet from the phone (PAN)

The Car Thing can use a phone's hotspot over Bluetooth: pair the phone, turn on its Personal
Hotspot, and switch on "Internet access" for it in the Bluetooth device settings. The network
gets an address by DHCP. When Ethernet is connected, Android prefers Ethernet. The zygote patch
`patches/aosp/frameworks/base/0002-*` gives the Bluetooth process `CAP_NET_ADMIN`, which it needs
to create the `bt-pan` interface. In PlayThing this is how DiPlay updates itself
(`docs/diplay.md`).

## Audio routing

`BoardConfig.mk` sets `USE_XML_AUDIO_POLICY_CONF := 1`, so the audio policy reads
`audio/audio_policy_configuration.xml` (Android 9 otherwise uses a built-in default without
A2DP). It has three modules:

| Module | Use |
|---|---|
| `primary` (`audio.primary.superbird`) | The microphones. Its output is a stub (no speaker). |
| `a2dp` (`audio.a2dp.default`) | Audio to the car stereo or a speaker over Bluetooth |
| `usb` (`audio.usb.default`) | A USB audio adapter |

For media, navigation, and Siri, Android selects A2DP before a USB device, and a USB device
before the stub. Calls: CarPlay call audio (DiPlay) uses `USAGE_VOICE_COMMUNICATION` in
`MODE_IN_COMMUNICATION`, which Android 9 does not route to A2DP. The patch
`patches/aosp/frameworks/av/0001-*` allows it when `ro.audio.a2dp_in_communication=true`
(`device.mk` sets it). `MODE_IN_CALL` does not change.

Remove the iPhone from the car's own Bluetooth devices (or turn off its media audio there):
else the iPhone also sends audio to the car directly.

### USB audio (for example to the car's AUX input)

A USB sound card works as the output: when it connects, Android opens the USB output (48 kHz,
stereo) and selects the route "USB" (tested with a USB-C audio adapter, `1620:0b21` "Generic USB
Audio", with headphones). A connected Bluetooth A2DP device comes first.

For CarPlay, the iPhone and the sound card must be on the USB bus at the same time. So use a USB
hub (with a hub chip) and connect the sound card to one of its ports, or use a hub whose sound
card is behind its hub chip. Some USB-C adapters with a 3.5 mm jack, Power Delivery, and a data
port have no hub chip: they switch their one USB connection between their own sound card and the
device in the data port. With the iPhone connected, their sound card is off, and no sound comes
out. In the kernel log, a sound card behind a hub shows as `usb 1-2.x`; the adapter's sound card
and then the iPhone both show as `usb 1-2`.

## Microphones (`audio/hal/audio_hw.c`)

The ALSA card `AML-AUGESOUND` has one PDM capture device (`/dev/snd/pcmC0D0c`, 4 channels, up to
96 kHz). The HAL:

- captures S32_LE, 4 channels, 48 kHz, 10 ms periods;
- mixes the microphones in `persist.plaything.mic_mask` (default `0xf`) to mono, adds
  `persist.plaything.mic_gain_db` (default 24 dB: speech at about -31 dBFS), clamps to 16 bits;
- discards the first 250 ms after a start (the PDM filter settles);
- reports 48 kHz mono or stereo; AudioFlinger resamples (Siri uses 24 kHz);
- `debug.plaything.mic_dump` names an existing file that gets a copy of the HAL output.

The tinyalsa tools (`tinycap`, `tinymix`, `tinypcminfo`) are in the image for tests.

## CarPlay audio formats

The iPhone chooses each format from the list that DiPlay offers. Music: LPCM 48 kHz stereo;
Siri: 24 kHz mono, with the microphone; Apple Maps voice: 24 kHz stereo when DiPlay's
`speech_without_8_16_khz` is on (`diplay-setup.sh` sets it; without it the iPhone sends 8 kHz,
which sounds bad). Calls: 32 kHz mono with the microphone. The Bluetooth stack encodes A2DP as
AAC at 44.1 kHz (about 13 % of one core).

Known limits of calls (delay, no echo canceller): `docs/known-issues.md`.
