# Home screen (CptHome)

Source: `device/spotify/superbird/apps/CptHome/` (`com.plaything.home`). It replaces Launcher3Go
(`LOCAL_OVERRIDES_PACKAGES`) and uses much less memory (about 8 MB native heap in the background;
Launcher3Go: 39 MB PSS). It shows after each start, with four tiles: CarPlay, Bluetooth,
Settings, and Apps (the app drawer).

## Design and implementation

The design is for 800 × 480 px with 1 px = 1 design px. The display density is 240, so CptHome
works in pixels, not dp.

| Item | Implementation |
|---|---|
| Wallpaper | `res/drawable-nodpi/wallpaper.png`, the window background, never scaled. Decoded as `RGB_565` (750 KB) in `onStart`; in `onStop` the background becomes `#161826`, so the bitmap is freed while an app is in front. |
| `RGB_565` without bands | `scripts/rgb565-dither.py` dithers the PNG to the `RGB_565` levels (4 × 4 ordered dither), so Android's decode by truncation loses nothing. |
| Tiles | 160 × 160, 24 px apart, labels 14 px below, centered (the design has 180 px tiles for three; four of them do not fit the 800 px width). One custom view (`HomeView`) draws tiles, labels, page dots, status, and toast; no layout XML. |
| Focus | fill, border, icon, and label colors change together; scale 1.06 in 180 ms (`ValueAnimator`, ease-out) |
| Glow | accent color at 35 %, 32 px blur, drawn once into an 8-bit mask (`BlurMaskFilter`) |
| Icons | Phosphor Regular (`car-profile`, `bluetooth`, `gear-six`, `squares-four`, `bluetooth-slash`), vector drawables, MIT (`CptHome/NOTICE`) |
| Font | Roboto Medium from the image |
| Bluetooth status (top right) | the connected A2DP device (for example the car); "Off" or "Not connected" with `bluetooth-slash` |
| Toast "Opening {App}…" | drawn by `HomeView`; it goes away when the app is in front, or after 1.4 s |

The home screen has no status bar (`Theme.Material.NoActionBar.Fullscreen`).

## Input

- Knob (`DPAD_LEFT`/`DPAD_RIGHT`): clockwise moves the focus right, with no wrap. Events less
  than 180 ms after the previous one are ignored. Scroll events also work.
- Knob press (`ENTER`): the focused tile shows its pressed fill while the knob is down, and opens
  on release.
- Touch: a tap on another tile moves the focus; a tap on the focused tile opens it.
- After the last tile, the knob moves the focus to the rotate button (top right).
- Back on the home screen does nothing. After a start the focus is on CarPlay; after an app it
  stays on the last tile.

## Rotation

The Car Thing has no motion sensor (Android has only the light sensor), so the screen cannot
turn by itself. The rotate button (Phosphor `device-rotate`, left of the Bluetooth status) turns
the screen 90° clockwise at each press: mount the Car Thing in any direction, then press until
the picture is upright. It sets Android's rotation lock (`accelerometer_rotation=0`) with a
fixed `user_rotation` (0 to 3, `Settings.System`, permission `WRITE_SETTINGS`), so Android keeps
it after a restart. SurfaceFlinger composes every frame with the GPU and already turns the
landscape UI onto the portrait panel, so each rotation costs the same. Touch follows the
rotation (the input viewport). Knob clockwise stays `DPAD_RIGHT`.

In portrait (480 × 800) the home screen puts the tiles in a 2 × 2 grid, the wallpaper turns by
90°, and the app drawer has three columns. Apps that lock their orientation (for example to
landscape) keep it: Android then turns the screen back while they are in front.

With an iPhone on USB, the first press only shows "iPhone connected: press again to rotate", and
a second press within 4 s rotates: a new orientation makes DiPlay start CarPlay again with the new
screen size (it switches the iPhone's USB configuration again), and the iPhone then asks "Trust"
again. Choose the orientation before you connect the iPhone.

To set it with ADB: `adb shell settings put system accelerometer_rotation 0` and
`adb shell settings put system user_rotation 1` (0 = landscape, 1 to 3 = 90°, 180°, 270°).

## Apps (app drawer)

The Apps tile opens the app drawer (`AppsActivity`): each app with a launcher entry
(`CATEGORY_LAUNCHER`, or `CATEGORY_LEANBACK_LAUNCHER` for TV apps), from the image, the app
store (Droid-ify, `docs/apps.md`), or ADB. One tile for each package, sorted by name, five
columns of about 137 × 128 px (three in portrait). The knob moves the focus one tile at a time,
the knob press opens the app, and Back goes back to the home screen. The drawer updates when an
app is installed or removed. It loads the icons in `onStart` and releases them in `onStop`, so
they are not in memory while an app is in front. It stays in the home task: Back from an app
returns to it, and a home intent (for example the car button in CarPlay) clears it.

## CarPlay

The CarPlay tile resumes a running CarPlay session, and the car button in CarPlay returns to the
home screen (`docs/diplay.md`).

## Screenshots

`adb shell screencap -p /data/local/tmp/screen.png` gives a correct picture in each orientation
(patch `frameworks/native/0001`, `docs/graphics.md`).
