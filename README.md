# PlayThing

**Status: beta** (version 0.2.0-beta; changes in `CHANGELOG.md`). It is tested on two Car Things (an older and a newer board revision), and it has known
issues (`docs/known-issues.md`).

Android 9 on the Spotify Car Thing, as a wired Apple CarPlay receiver. An iPhone connects to the
Car Thing through a USB hub, and CarPlay shows on the panel. The CarPlay app is
[DiPlay](https://github.com/shihabal3amri/DiPlay) (its release APK, downloaded by the build).
The car stereo gets the audio over Bluetooth A2DP.

Features: the knob and the buttons control CarPlay; a home screen (CptHome) with CarPlay,
Bluetooth, Settings, and an app drawer, in landscape or portrait (rotate button); Droid-ify as
the app store (installs without a prompt) and an App manager; ThingPod, an iPod-style music
player; the hardware H.264 decoder; persistent `/data` on the stock data partition; automatic
brightness; the built-in microphones for Siri; Bluetooth tethering from the iPhone for DiPlay
updates; SELinux enforcing; a minimal keyboard.

A version without DiPlay and the home screen, as a base for other Android apps:
**superbird-android** (a separate repository).

> **Warning: use at your own risk.** This software replaces the software on your Car Thing.
> It comes "as is", with no warranty of any kind (see `LICENSE`). The authors are not
> responsible for any damage: a Car Thing that does not start ("bricked"), lost data, damage to
> other equipment, or any other loss. I tested it on my Car Thing (S905D2, 512 MiB RAM, ST7701S
> panel), and one user on a newer unit (board revision 4); your unit can be different. Read the safety rules below, make the backup that
> the flash tool offers, and keep it. Do not use the Car Thing in a way that takes your
> attention from the road while you drive. This project is not affiliated with Spotify, Apple,
> or Google.

## Hardware

- Spotify Car Thing whose stock software runs from slot A.
- A USB-C hub with Power Delivery input and USB-A ports (tested: Anker 6-in-1), and a PD
  charger. The Car Thing runs its single USB-C port in host mode, powered through the hub.
- For the install: a USB-C cable, and a Mac or Linux computer with Python 3.8 or later, git,
  libusb (macOS: `brew install libusb`), about 5 GB of free disk space, and internet.
  Flash tool (`flash.py`): macOS and Linux. I tested it on macOS with an Apple Silicon Mac; an
  Intel Mac and Linux should work (Linux needs the udev rule below), but I did not test them yet.
  Windows is not supported: the tool stops at the start. Use a Mac or a Linux computer.
  On Linux, give your user access to the Car Thing's USB modes once:
  `echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="1b8e", ATTR{idProduct}=="c003", MODE="0666"' | sudo tee /etc/udev/rules.d/70-car-thing.rules`
- For the build: a Mac with Apple Silicon (the scripts use Apple `container`; the AOSP build
  runs under Rosetta). Other x86_64 Linux hosts need small script changes.

## Buttons: what to hold

The Car Thing has no battery: it starts when you connect it. Hold the buttons before you connect
it, and for about 3 seconds after. The presets are the four buttons on the top edge: preset 1 at
the left end, preset 4 at the right end.

| Hold while you connect | What starts | Use |
|---|---|---|
| nothing | PlayThing | Normal use, through the hub with the charger |
| preset 1 | The stock software | At any time, also when PlayThing does not start |
| preset 4 | USB burn mode | Updates and removal (after the first install) |
| presets 1 and 4 | Mask-ROM USB mode | The first install |

For a USB mode, connect the Car Thing directly to the computer, not through the hub.

## First install

1. Download `plaything-0.2.0-beta.zip` from the [Releases page](https://github.com/tatujan/PlayThing/releases) of this repository,
   unzip it, and open a terminal in the folder `plaything-0.2.0-beta`. (Or make a release folder
   yourself, see "Build".)
2. Disconnect the Car Thing. Hold presets 1 and 4, connect it directly to the computer, and
   release after about 3 seconds.
3. Run `python3 flash.py install`, and push Enter at each question. The tool downloads the
   files that the image does not contain
   (the Mali GPU driver, the video decoder firmware, and DiPlay),
   finds the Car Thing, shows what it found, and asks before it does something.
4. The tool backs up the full eMMC to `backups/` in the folder (about 6 minutes). Keep that
   folder.
5. When the tool tells you: disconnect the Car Thing, hold preset 4, connect it again, and
   release after about 3 seconds. The tool writes the image to the stock B slot, which the stock
   software does not use (about 3 minutes).
6. When the tool says DONE: disconnect the Car Thing, and connect it to the hub with the
   charger. PlayThing starts in about 1 minute (the first start takes longer).

## Update

1. Disconnect the Car Thing. Hold preset 4, connect it directly to the computer, and release
   after about 3 seconds.
2. Download and unzip the zip of the new release from the [Releases page](https://github.com/tatujan/PlayThing/releases).
   In its folder, run `python3 flash.py install`, and push Enter at the question. The tool
   writes only the blocks that changed (about 3 minutes). Your data stays. On a Car Thing with
   the newer Spotify U-Boot, the tool first asks you to connect it once more with preset 4, to
   check its partition table.
3. Disconnect the Car Thing, and connect it to the hub.

## DiPlay updates (over the air)

DiPlay can update itself on the Car Thing. It needs internet; the iPhone gives it over
Bluetooth:

1. Pair the iPhone with the Car Thing: on the Car Thing, Settings → Bluetooth (or the Bluetooth
   tile of the home screen) → Pair new device.
2. On the iPhone, turn on Settings → Personal Hotspot → Allow Others to Join.
3. On the Car Thing, open Settings → Bluetooth, tap the gear icon of the connected iPhone, and
   turn on "Internet access".
4. In DiPlay, open Settings → About → "Check for updates", and install the update.

Do this while the car is parked: Android compiles the update on the Car Thing, and that takes
some time. The update goes into `/data`; the copy in the image stays. Ethernet through the hub
also gives internet.

## Back to the stock software

Hold preset 1 while you connect the Car Thing: the stock software starts, at any time. To remove
PlayThing: put the Car Thing in USB burn mode (preset 4, directly to the computer), and run
`python3 flash.py restore backups/<folder of the first install>`.

## Coming from another community firmware

| Your Car Thing has | What to do |
|---|---|
| The stock software | First install, as above. |
| A firmware based on the stock software (for example the Thing Labs / DeskThing image, GlanceThing) | First install, as above. Spotify's bootloader and partition table stay; the tool installs into slot B, and preset 1 still starts that firmware. If the firmware changed the boot command or runs from slot B, the tool stops before it writes. |
| A firmware with its own bootloader and partition table (for example BridgeThing; Nocturne 4 and Mira, which the Terbium web flasher installs: they write a new bootloader and a GPT disk image) | Do not run the tool on it. First restore the stock software with that project's instructions, then do the first install. (In mask-ROM mode, the tool's USB U-Boot can damage such a firmware; the tool asks before that step, and it stops if it finds a GPT.) |
| PlayThing or superbird-android | Update, as above. The two share `/data`; Android deletes the data of the apps that the new image does not have. |
| A companion app (for example MacThing, Carthios) | What counts is the firmware below it: see the rows above. |

`python3 flash.py check` shows the state of the Car Thing and writes nothing. To report a problem,
run `python3 flash.py diag` (preset 4): it writes nothing and makes a folder `diag/<time>/`. Post
its `summary.txt`; send its `data/` folder (Android logs with device data) only privately. The
README of the release folder, `docs/release.md`, and `docs/boot.md` have more details.

## What I changed to make this possible

The Car Thing was made for one app on Spotify's own Linux. These are the main changes (details
in `docs/`):

- **Boot without a new bootloader.** The stock boot chain stays (BL2, BL31, Spotify's U-Boot).
  One U-Boot env change starts Android from the unused stock B slot: the system image at the
  start of `system_b`, and a 32 MiB boot area (kernel, ramdisk, boot script) at its end. Preset 1
  at power-on still starts the stock software (`docs/boot.md`).
- **Kernel.** Spotify's Linux 4.9 with the options that Android needs (binder, ashmem, and more)
  and my patches: USB host mode with power in through a hub, touch axes for the landscape
  screen, one key for each knob step, a VPU fix, a fence fix (`sw_sync`), and fewer xHCI
  warnings (`patches/kernel/`, `docs/kernel.md`).
- **Android 9 Go in 512 MiB.** A 32-bit userspace (`core_tiny`, `go_defaults_512`), zram swap,
  and lmkd with memory cgroups (`docs/android.md`).
- **Graphics.** The Mali-G31 GPU with Arm's r16p0 driver and kbase kernel module; the Mali
  gralloc without Amlogic's extension; my own hardware composer (HWC2), which shows the frame
  that the GPU composed, with no copy. SurfaceFlinger turns the portrait panel to landscape
  (`docs/graphics.md`).
- **Hardware H.264 decoder.** My OMX component on Amlogic's V4L2 decoder: CarPlay video at up to 60 fps, with about 5 ms to decode a frame
  (`docs/video.md`).
- **Persistent `/data` without a new partition table.** A 1 GiB image file on the stock data
  partition, mounted through a loop device, with a RAM fallback (`docs/persistent-data.md`).
- **Bluetooth.** Android's own Bluetooth stack on the BCM20703A2 (UART), with the firmware read
  from the stock software; A2DP to the car stereo, tethering (PAN) for DiPlay updates, and an audio policy patch so that calls can use A2DP (`docs/bluetooth-audio.md`).
- **Microphones and sensors.** An audio HAL for the four microphones; lights and sensors HALs
  for automatic brightness (`docs/display-brightness.md`).
- **Knob and buttons.** A framework patch makes the knob move the focus in apps; the buttons
  send media keys and the Siri key (`docs/input.md`).
- **The CarPlay appliance.** DiPlay with its settings at each boot; a home screen (CptHome)
  with an app drawer and a rotate button (landscape or portrait); a minimal keyboard
  (CptKeyboard); Settings patches for a screen without phone features (`docs/home-screen.md`,
  `docs/keyboard.md`).
- **More apps.** Droid-ify as the app store, with installs and removals without a prompt; an
  App manager (storage, memory, clean-up); ThingPod, an iPod-style music player for the knob
  (`docs/apps.md`, `docs/thingpod.md`).
- **Security.** SELinux enforcing, with my own policy for each script and HAL
  (`docs/selinux.md`).
- **A publishable image.** No Arm, Amlogic, Broadcom, Droid-ify, or DiPlay files in the image: placeholders
  that `flash.py` fills on your computer, and the Bluetooth firmware from your own Car Thing
  (`docs/release.md`).
- **A build on a Mac.** The AOSP Pie build runs in a Linux container under Rosetta, with an ART
  patch for its host tools (`patches/aosp/art/`).

## How it boots

The stock boot chain stays (BL2, BL31, Amlogic U-Boot). One U-Boot environment change boots
Android from the stock B slot (`system_b`): the system image at the start of `system_b`, and a
32 MiB boot area at its end (kernel, ramdisk, and a boot script). Holding preset button 1 at
power-on still boots the stock software, and preset button 4 enters USB Burn Mode. Details:
`docs/boot.md`.

## Safety rules

- Make a full eMMC dump with SHA-256 checksums before any write (`flash.py install` does it).
- Write only with raw offsets (`scripts/flash.py`, `scripts/sbt-raw-write.py`; both check the
  partition table first). Do not write partitions by name with superbird-tool: its burn-mode
  partition table does not match the real eMMC layout after `boot_a` (`docs/boot.md`).
- Do not run `fastboot flashing unlock`, do not write eFuses, and do not write the eMMC boot
  partitions.
- Keep the stock slot unchanged. Prefer a RAM boot (`scripts/sbt-ramboot.py`) for tests.

## Build

Why Linux: Android 9 (AOSP Pie) builds on an x86_64 Linux computer. Its build system uses
prebuilt x86_64 host tools (compilers, `dex2oat`, and others) and Python 2. Its macOS support
accepts only the macOS SDKs 10.10 to 10.13 (`build/soong/cc/config/x86_darwin_host.go`), so it
does not build on a current Mac. The kernel build also needs a Linux system, with GCC 6: newer
GCC versions stop the Amlogic drivers, which build with `-Werror`.

Why containers on a Mac: I build on a Mac with Apple Silicon, so both builds run in Linux
containers (Apple `container`). The AOSP build runs in an x86_64 Ubuntu 18.04 container under
Rosetta; an ART patch (`patches/aosp/art/`) makes host `dex2oat` work there. The kernel build
runs in a native arm64 container. The source trees are in container volumes (ext4,
case-sensitive), not in a Mac folder: the Mac file system is case-insensitive by default, and a
shared folder is slow. On an x86_64 Linux computer, the same steps can run with Docker or
Podman after small script changes (not tested).

`docs/build.md` has the details. In short:

1. Containers (once):
   `container build --arch amd64 -t plaything/aosp-pie:1 -f containers/aosp-pie/Containerfile containers/aosp-pie`
   and `container build --arch arm64 -t plaything/kernel-4.9:2 -f containers/kernel-4.9/Containerfile containers/kernel-4.9`;
   volumes `aosp` (300 GB) and `kernel` (50 GB). The AOSP tree: `android-security-9.0.0_r76`.
2. Kernel: build the Spotify kernel with the patches in `patches/kernel/` and
   `kernel/configs/superbird-android.cfg` (`docs/kernel.md`), the Mali kbase module
   (`docs/graphics.md`), and the decoder modules (`scripts/media-modules.sh`). Then
   `scripts/kernel.sh /plaything/scripts/kernel-export.sh`.
3. Optional: put your ADB public key (`~/.android/adbkey.pub`) at
   `device/spotify/superbird/adb_keys.pub` (git ignores it).
4. Image: `scripts/aosp.sh /plaything/scripts/build-image.sh`. Output in `out/aosp/`: `system.raw`
   (with placeholders), `boot-area.img`, `blobs.json`.
5. Release folder: `python3 scripts/make-release.py --version <version>`, then
   `python3 flash.py install` in `out/release/<name>-<version>/`.

## Documents

`docs/README.md` lists the documents: how each part works (boot, kernel, graphics, video,
Bluetooth and audio, input, `/data`, SELinux), the build, the release and the flash tool, and
the known issues (`docs/known-issues.md`).

## License

The project's own files: GNU GPL version 3 or later (`LICENSE`). Kernel patches: GPL-2.0-only.
AOSP and gralloc patches and the files based on AOSP code: Apache-2.0. Icons: MIT. Details and
the licenses of downloaded components: `THIRD_PARTY_NOTICES.md`.

Spotify and Car Thing are trademarks of Spotify AB; Apple, CarPlay, and iPhone are trademarks of
Apple Inc.; Android is a trademark of Google LLC. This project is not affiliated with them.

## Acknowledgments

PlayThing is built around [DiPlay](https://github.com/shihabal3amri/DiPlay) by
[shihabal3amri](https://github.com/shihabal3amri), an Android CarPlay receiver. DiPlay does the
CarPlay work: the wired link to the iPhone, the video, the audio, and the knob input. Without
DiPlay, PlayThing would not exist. Thank you!

DiPlay is a separate project with its own license (GPL-3.0, with AGPL-3.0 parts). PlayThing
downloads its release APK and does not change it.

This project also builds on the work of others:

- [thinglabsoss/superbird-tool](https://github.com/thinglabsoss/superbird-tool): `flash.py` uses
  its USB code and its USB burn-mode U-Boot.
- [Hardkernel's ODROID-C4 Android 9 port](https://github.com/codewalkerster?tab=repositories)
  (codewalkerster): the Mali r16p0 driver and kbase, the Mali gralloc, and the video decoder
  firmware and modules.
- [Spotify's source code releases](https://github.com/spsgsb): the Car Thing's Linux kernel and
  U-Boot.
- [thinglabsoss/peek-corpus](https://github.com/thinglabsoss/peek-corpus): hardware captures of
  other Car Things.

Thank you!
