# DiPlay (CarPlay)

PlayThing uses [DiPlay](https://github.com/shihabal3amri/DiPlay) (`com.shihab.diplay`), an
Android CarPlay receiver, as its CarPlay app. DiPlay needs Android 9 or later and 32-bit ARM
code, and no Google services.

## In the image

- `/system/app/DiPlay/DiPlay.apk`: DiPlay's release APK 0.2.20, with its own signature
  (`PRESIGNED`). The release APK contains DiPlay's CarPlay accessory identity (source builds do
  not), so the published image has only a placeholder, and `flash.py` downloads the APK from
  DiPlay's GitHub release and writes it in (`docs/release.md`). The image has no precompiled code
  for it: Android verifies it at the first start and uses the JIT.
- `diplay/default-permissions-diplay.xml`: the microphone and location permissions.
- `config_disableUsbPermissionDialogs`: no USB permission dialog for the iPhone.
- The television feature (`docs/android.md`, `docs/input.md`): DiPlay drives the CarPlay knob from
  the DPAD keys.

## Phone link

The iPhone connects with its USB cable to a USB-A port of the hub. DiPlay uses Android's USB host
API, NCM in user space, and `VpnService` (kernel `TUN`). DiPlay's `USB_DEVICE_ATTACHED` filter
starts CarPlay when the iPhone connects.

## `diplay-setup.sh` (after each start, as root)

1. App-ops that otherwise need dialogs: `SYSTEM_ALERT_WINDOW`, `GET_USAGE_STATS`,
   `WRITE_SETTINGS`, `ACTIVATE_VPN`.
2. DiPlay settings, only when the key is missing (a value that you change in DiPlay stays):

   | Setting | Value | Why |
   |---|---|---|
   | `display_width_physical_mm` | 150 | CarPlay chooses its layout from the physical size: 4 × 2 icons |
   | `display_fps` | 60 | DiPlay's default is 30 |
   | `speech_without_8_16_khz` | true | Apple Maps voice at 24 kHz instead of 8 kHz |
   | `oem_label` | Home | the name of the car button in CarPlay |
   | Siri key | preset 4 (`F1`, scan code 59, `gpio-keys`) | DiPlay's assigned Siri key |

3. The car button icon (`files/airplay-icon.png`): a house in the colors of the home screen,
   only when no custom icon is there.

DiPlay does not start by itself after a start: the home screen shows (`docs/home-screen.md`).

## Home screen and CarPlay

- The CarPlay tile of the home screen sends the launcher intent (`ACTION_MAIN`,
  `CATEGORY_LAUNCHER`), so a running CarPlay session comes back; another intent would show
  DiPlay's menu.
- The car button in CarPlay starts the HOME intent: the home screen comes to the front, and the
  CarPlay session keeps running.

## Updates

DiPlay has its own updater (DiPlay → Settings → About → "Check for updates"). It needs internet:
Ethernet on the hub, or the iPhone's hotspot over Bluetooth (`docs/bluetooth-audio.md`). The
update installs into `/data` (the image's copy stays) and Android compiles it on the device:
do it while parked, not during CarPlay.
