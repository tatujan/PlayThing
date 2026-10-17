# Home screen (CptHome)

Source: `device/spotify/superbird/apps/CptHome/` (`com.plaything.home`). It replaces Launcher3Go
(`LOCAL_OVERRIDES_PACKAGES`) and uses much less memory (about 8 MB native heap in the background;
Launcher3Go: 39 MB PSS). It shows after each start, with three tiles: CarPlay, Bluetooth,
Settings.

## Design and implementation

The design is for 800 × 480 px with 1 px = 1 design px. The display density is 240, so CptHome
works in pixels, not dp.

| Item | Implementation |
|---|---|
| Wallpaper | `res/drawable-nodpi/wallpaper.png`, the window background, never scaled. Decoded as `RGB_565` (750 KB) in `onStart`; in `onStop` the background becomes `#161826`, so the bitmap is freed while an app is in front. |
| `RGB_565` without bands | `scripts/rgb565-dither.py` dithers the PNG to the `RGB_565` levels (4 × 4 ordered dither), so Android's decode by truncation loses nothing. |
| Tiles | 180 × 180, 28 px apart, labels 14 px below, centered. One custom view (`HomeView`) draws tiles, labels, page dots, status, and toast; no layout XML. |
| Focus | fill, border, icon, and label colors change together; scale 1.06 in 180 ms (`ValueAnimator`, ease-out) |
| Glow | accent color at 35 %, 32 px blur, drawn once into an 8-bit mask (`BlurMaskFilter`) |
| Icons | Phosphor Regular (`car-profile`, `bluetooth`, `gear-six`, `bluetooth-slash`), vector drawables, MIT (`CptHome/NOTICE`) |
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
- Back on the home screen does nothing. After a start the focus is on CarPlay; after an app it
  stays on the last tile.

## CarPlay

The CarPlay tile resumes a running CarPlay session, and the car button in CarPlay returns to the
home screen (`docs/diplay.md`).

## Screenshots

`screencap` gives a distorted image on this device; check the panel itself.
