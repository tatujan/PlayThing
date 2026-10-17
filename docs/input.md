# Input: knob, buttons, touch

## Key codes

The boot area sets the key codes in the device tree (`scripts/mk-systemb-tail.py`, `KEY_EDITS`),
and Android's `Generic.kl` maps them:

| Control | Linux key | Android key | In CarPlay (DiPlay) |
|---|---|---|---|
| Knob, counterclockwise step | `KEY_LEFT` | `DPAD_LEFT` | knob left |
| Knob, clockwise step | `KEY_RIGHT` | `DPAD_RIGHT` | knob right |
| Knob press | `KEY_ENTER` | `ENTER` | knob select |
| Back (front button) | `KEY_BACK` | `BACK` | back |
| Preset 1 | `KEY_PREVIOUSSONG` | `MEDIA_PREVIOUS` | previous track |
| Preset 2 | `KEY_PLAYPAUSE` | `MEDIA_PLAY_PAUSE` | play/pause |
| Preset 3 | `KEY_NEXTSONG` | `MEDIA_NEXT` | next track |
| Preset 4 | `KEY_F1` | `F1` | Siri (DiPlay's assigned Siri key; Android 9 would take `VOICE_ASSIST` for itself) |
| Mute | `KEY_MUTE` | `VOLUME_MUTE` | |

U-Boot reads presets 1 and 4 directly at power-on (`docs/boot.md`), so these codes do not
change the boot functions.

The knob is a rotary encoder. The kernel patch `0005` (`docs/kernel.md`) makes the
`rotary-encoder` driver send one key press for each step (`linux,key-codes`), because Android
does not turn wheel events into keys.

## Knob in Android apps

The framework patch `patches/aosp/frameworks/base/0001-*` (`ro.config.knob_sequential_focus=true`):
a `DPAD_LEFT` or `DPAD_RIGHT` that the app does not handle moves the focus as SHIFT+TAB or TAB
does, in focus order. So one knob reaches every focusable view, also in lists and in Settings.
Apps that handle the keys themselves (DiPlay's CarPlay screen, CptHome, CptKeyboard) do not
change.

PlayThing also declares the television feature (`docs/android.md`): DiPlay drives the CarPlay
knob from the DPAD keys only on a TV device, and it then tells the iPhone that the knob is the
primary input.

## Touch

The `tlsc6x` controller (multi-touch type B) reports portrait coordinates. Kernel patch `0002`
adds the standard axis properties, and the boot area sets `touchscreen-inverted-x` and
`touchscreen-swapped-x-y`, so the touch reports 800 × 480 landscape coordinates that match the
rotated display.
