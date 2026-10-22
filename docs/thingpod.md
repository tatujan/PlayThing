# ThingPod (music player)

Source: `device/spotify/superbird/apps/ThingPod/` (`com.plaything.pod`). ThingPod is a music player
in the style of the iPod classic. The screen shows only the iPod screen: the Car Thing's knob is
the click wheel.

## Look

Brushed aluminium fills the display (fine horizontal lines, a broad sheen, lighter at the top;
made once in code into an `RGB_565` bitmap). The iPod screen is a window in it: 26 px of
aluminium, then a 9 px dark glass border with a beveled cut (a shadow at the top edge, a light
line at the bottom edge), then the screen with rounded corners, an inner shadow at its top, and a
faint gloss over its upper part. The screen has a gray title bar (26 px text), white lists with
28 px text and 56 px rows (6 rows in landscape, as on the iPod), and a blue selection bar.
`PodView` draws everything; the drawing uses screen coordinates inside the frame.

## Controls

| Control | Menus | Now Playing |
|---|---|---|
| Knob | moves the selection | volume (a volume bar replaces the progress bar for 1.5 s) |
| Knob press | opens the selected item, or plays the song | play or pause |
| Knob press and hold (0.5 s) | — | the skip buttons ⏮ and ⏭ replace the progress bar: the knob selects, a press skips (the buttons stay for more skips), Back or 4 s without input hides them |
| Back | back (the Menu button); in the main menu: leave ThingPod, the music plays on | back to the menu |
| Preset 1, 2, 3 | previous, play/pause, next (through the media session) | the same |
| Touch | a tap on a row opens it | — |

## Menus

- **Music**: Artists (each with All Songs and its albums), Albums, and Songs. A song starts the
  list that it is in.
- **Shuffle Songs**: all songs in a random order.
- **Now Playing** (when there is a song): the cover, the song, its position in the list
  ("3 of 12"), and the progress bar with the time played and the time left.
- **Settings**: Shuffle (On, Off) and Repeat (Off, One, All), and the number of songs.

The main menus show the cover of the current song (else of the first album): in landscape in
the right half, in portrait as a square below the list. The other lists use the full width. Now
Playing puts the cover at the left (landscape) or above the song (portrait).

## Music

ThingPod reads Android's media library (`MediaStore`, the MediaProvider app, which the image has
for this): the music on the device and on USB sticks
(Android's media scanner reads a stick when it is connected; ThingPod loads the library again
when the scanner is done). It sorts names without case and without a leading "The ", as the iPod.
The covers come from the media library, decoded at most 440 px and in `RGB_565`, with a cache of
about 800 KB. The storage permission is granted on a new `/data`
(`permissions/default-permissions-thingpod.xml`); else ThingPod asks once.

## Playback

`PlayerService` plays with `MediaPlayer` and has a media session: the car stereo shows the title
and the cover over Bluetooth (AVRCP), and its buttons and the Car Thing's media buttons control
the player. ThingPod pauses when another app takes the audio (for example CarPlay), and when the
Bluetooth audio device disconnects. While the music plays, the service runs in the foreground
with a notification, so Android does not stop it. "Previous" in the first 3 s of a song goes to
the previous song; later it starts the song again.
