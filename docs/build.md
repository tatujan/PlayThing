# Build

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

A full first build takes several hours; later builds are much shorter.

In the containers this repository is read-only at `/plaything`, and its `out/` folder is at
`/out` (read-write). Large trees are in named volumes.

## 1. Containers and volumes (once)

```sh
container system start
container build --arch amd64 -t plaything/aosp-pie:1 -f containers/aosp-pie/Containerfile containers/aosp-pie
container build --arch arm64 -t plaything/kernel-4.9:2 -f containers/kernel-4.9/Containerfile containers/kernel-4.9
container volume create --opt size=300g aosp
container volume create --opt size=50g kernel
```

`scripts/aosp.sh` and `scripts/kernel.sh` start a container with its volume (`/aosp`, `/kernel`);
with no argument they start a shell. `AOSP_IMAGE` and `KERNEL_IMAGE` select other image names.
Never run two containers with the same volume (each volume is one ext4 disk image).

The AOSP image is Ubuntu 18.04 with Python 2.7 and 3.6 and the `repo` launcher. Under Rosetta,
host tools that need memory below 4 GiB fail; the ART patch in `patches/aosp/art/` fixes host
`dex2oat`.

## 2. AOSP tree (once)

```sh
scripts/aosp.sh bash -c 'cd /aosp && repo init -u https://android.googlesource.com/platform/manifest \
    -b android-security-9.0.0_r76 --depth=1 && repo sync -c -j8'
```

## 3. Kernel and modules

In the `kernel` volume: the Spotify kernel at `/kernel/spotify-4.9`
([spsgsb/kernel-common](https://github.com/spsgsb/kernel-common), commit `9a2e9190b35c`) with
`patches/kernel/spotify-4.9/` applied in number order; AOSP's `kernel/configs` at
`/kernel/android-configs`; a copy of the kbase source at `/kernel/build/kbase-r16p0`; and the
media_modules repository at `/kernel/donors/media_modules/src`. Then build as `docs/kernel.md`
shows, and:

```sh
scripts/kernel.sh /plaything/scripts/media-modules.sh     # decoder modules -> out/media-modules
scripts/kernel.sh /plaything/scripts/kernel-export.sh     # Image, DTB, mali_kbase.ko -> out/kernel
```

## 4. Image

Optional: put your ADB public key (`~/.android/adbkey.pub`) at
`device/spotify/superbird/adb_keys.pub` (git ignores it); then ADB over Ethernet needs no
confirmation on the screen.

```sh
scripts/aosp.sh /plaything/scripts/build-image.sh
```

`build-image.sh` runs `aosp-prepare.sh` (the gralloc project from `manifests/plaything.xml`, the
placeholders, this device folder, the kernel files, the decoder modules, and the AOSP patches),
then `m installclean` and the build, and removes `gdbserver`. It checks the output for forbidden
files and writes to `out/aosp/`:

| File | Content |
|---|---|
| `system.raw` | the system file system, with placeholders |
| `boot-area.img` | kernel, ramdisk, and boot variables |
| `blobs.json` | where the placeholders are in `system.raw` |

Edit the device configuration in this repository, not in `/aosp`: `aosp-prepare.sh` copies it
into the tree each time. Changes to AOSP projects are patch files in `patches/aosp/<project>/`;
`scripts/aosp-patches.sh` applies them and skips the patches that are already applied.

## 5. Release folder and install

```sh
python3 scripts/make-release.py --version <version> [--extra <source bundles>]
cd out/release/<name>-<version> && python3 flash.py install
```

`docs/release.md` describes the release folder and the flash tool.

## Device tools for development

`flash.py` is all that an install needs. For development, the `scripts/sbt-*.py` tools talk to
the Car Thing in USB burn mode through superbird-tool's USB module; they expect superbird-tool
at `out/tools/superbird-tool` with its own Python environment:

```sh
git clone https://github.com/thinglabsoss/superbird-tool out/tools/superbird-tool
git -C out/tools/superbird-tool checkout da05fb4
python3 -m venv out/tools/superbird-tool/.venv
out/tools/superbird-tool/.venv/bin/pip install \
    git+https://github.com/pyusb/pyusb@3241c0e6a7debafd7597925e8403d9d3ea02ed93 \
    git+https://github.com/superna9999/pyamlboot@d2857f4371bcdb7a90d885f24987e03b6d647a8a
PY=out/tools/superbird-tool/.venv/bin/python
```

| Tool | What it does |
|---|---|
| `$PY scripts/sbt-read-mpt.py OUT` | Reads the partition table (MPT); writes nothing |
| `$PY scripts/sbt-raw-dump.py OUT START LEN` | Reads an eMMC region by raw offset; writes nothing |
| `$PY scripts/sbt-raw-write.py FILE TARGET` | Writes a file to `system_b` or `boot_b` by raw offset, after an MPT check (`--dry-run` first) |
| `$PY scripts/sbt-ramboot.py --env ENV --kernel IMAGE --initrd UIMAGE --dtb-emmc boot_a` | Starts a kernel and a ramdisk from RAM, with no eMMC write |
| `$PY scripts/sbt-readmem.py OUT`, then `python3 scripts/ramoops-decode.py OUT` | Reads pstore (ramoops, 1 MiB at `0x07400000`) after a crash, and decodes the kernel console |
| `$PY scripts/sbt-env-cpt-boot.py --apply` or `--undo` | Writes or removes the boot command change (`docs/boot.md`); `flash.py` does the same |

Every write is at your own risk: make a full eMMC backup first (`flash.py install` makes one).
