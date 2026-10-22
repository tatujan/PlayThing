# Apps: Droid-ify and the App manager

The image has an app store (Droid-ify) and an app manager (the PlayThing App manager). On
PlayThing both are in the app drawer (the Apps tile, `docs/home-screen.md`); on superbird-android
they are in the launcher.

## Droid-ify (app store)

[Droid-ify](https://github.com/Droid-ify/client) (GPL-3.0) is an F-Droid client: it shows the
apps of the F-Droid repository and of other repositories that you add, and only the apps that
fit the device (Android 9, 32-bit ARM). It is in the image as `/system/app/Droidify`, with its
developer's signature:

- The build has a placeholder of the same size (`scripts/aosp-prepare.sh`, `scripts/blobs.py`),
  and `scripts/flash.py` downloads the release APK (v0.7.8), checks its SHA-256, and writes it
  into the image on the computer, as it does for DiPlay.
- Its native libraries are stored uncompressed (`extractNativeLibs=false`), so Android uses the
  APK from `/system` as it is. It has no dexpreopt: Android verifies it at the first start.
- An update of Droid-ify (from a repository with the same signature, for example IzzyOnDroid)
  goes to `/data/app`, over the copy of the image.

### Installs without a prompt (trusted installers)

Droid-ify does not request `INSTALL_PACKAGES`, so Android would ask the user for each install
and each removal. An app in the read-only property `ro.plaything.trusted_installers` gets no
prompt: two patches (`frameworks/base/0003`, `packages/apps/PackageInstaller/0001`) treat it as
an app with `INSTALL_PACKAGES` and `DELETE_PACKAGES`. An entry is `package:SHA-256 of the signing
certificate`; the package must belong to the calling app and have that certificate, so another
app with the same name gets the prompt. Commas separate the entries. The property has its own
SELinux type (`cpt_installer_prop`), which `system_server` and the PackageInstaller app can read
(`docs/selinux.md`).

The image has one entry: Droid-ify (`com.looker.droidify`), with its release key
`ed8859c55af311162658b94af982b9f091dcd27628d4de3486d1217ebf3c9935`. Droid-ify changed its key
in v0.5.0 (its release notes: "App needs to be reinstalled"); all releases since then have this
key. Effect: Droid-ify, and each repository that you add in it, installs, updates, and removes
apps without a prompt. Use its "Session" installer (the default).

At an install, Android compiles the app on the device (`pm.dexopt.install=speed-profile`;
without a profile this is `verify` and `quicken`). For a large app this takes some minutes of
CPU and memory, so install apps when the car is parked, not during CarPlay.

## App manager

Source: `device/spotify/superbird/apps/CptManager/` (`com.plaything.manager`). It is signed with
the platform key, as Settings: since Android 8 only a platform app can clear the caches of other
apps (`INTERNAL_DELETE_CACHE_FILES`), and it uses the hidden APIs that Settings uses.

| Part | What it does | API |
|---|---|---|
| Top line | free storage of `/data`, and free RAM | `StatFs`, `ActivityManager.getMemoryInfo` |
| Clean up | clears the caches of all installed apps (also the apps that the list does not show) | `PackageManager.deleteApplicationCacheFiles` |
| List | the apps from outside the image, the updated apps of the image, and the apps of the image with a launcher entry; for each its storage, or its memory when it runs | `StorageStatsManager`, `ActivityManager.getProcessMemoryInfo` (PSS) |
| Open | starts the app | launch intent (also TV apps) |
| Stop | stops the app (second press) | `ActivityManager.forceStopPackage` |
| Clear cache | clears the cache of the app | `PackageManager.deleteApplicationCacheFiles` |
| Clear data | deletes all data of the app (second press) | `ActivityManager.clearApplicationUserData` |
| Remove, Remove update | removes the app, or the update of an app of the image (second press) | `PackageInstaller.uninstall` |

The list does not show the home screen and the App manager itself. The knob moves the focus,
the knob press opens or runs the focused item, and Back goes back; touch also works.

The App manager stops no app by itself. Android's low-memory killer (`lmkd`) frees memory when
it is necessary; an app that a "RAM cleaner" stops starts again, and that costs CPU and memory.
Stop is for an app that does not respond or that you do not use now.
