# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# PlayThing board configuration (docs/android.md).

# 32-bit ARM userspace on the 64-bit Cortex-A53 kernel.
TARGET_ARCH := arm
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := armeabi-v7a
TARGET_CPU_ABI2 := armeabi
TARGET_CPU_VARIANT := cortex-a53

TARGET_BOARD_PLATFORM := superbird
TARGET_BOOTLOADER_BOARD_NAME := superbird
TARGET_NO_BOOTLOADER := true
TARGET_NO_RECOVERY := true

# Kernel. The device does not use boot.img: the boot area at the end of system_b has the
# kernel, the ramdisk, and the command line (scripts/mk-systemb-tail.py, docs/boot.md). These
# values only let the build make a boot.img.
BOARD_KERNEL_BASE := 0x0
BOARD_KERNEL_OFFSET := 0x1080000
BOARD_KERNEL_PAGESIZE := 2048
BOARD_KERNEL_CMDLINE := androidboot.hardware=superbird androidboot.selinux=permissive \
    console=ttyS0,115200 no_console_suspend otg_device=1 zram.num_devices=2
BOARD_MKBOOTIMG_ARGS := --kernel_offset $(BOARD_KERNEL_OFFSET) \
    --second device/spotify/superbird-kernel/superbird.dtb

# 32-bit userspace on the 64-bit kernel: the kernel binder uses the 64-bit protocol
# (CONFIG_ANDROID_BINDER_IPC_32BIT off). Without this setting, libbinder uses the old
# 32-bit protocol: servicemanager exits with status 255,
# vdc reports "Binder driver could not be opened", and init reboots to the bootloader.
TARGET_USES_64_BIT_BINDER := true

# No vendor partition and no full Treble: vendor files go to /system/vendor.
PRODUCT_FULL_TREBLE_OVERRIDE := false
TARGET_COPY_OUT_VENDOR := system/vendor

# Partitions (partition_superbird_ab.dtsi). Android uses the inactive slot.
BOARD_FLASH_BLOCK_SIZE := 4096
BOARD_BOOTIMAGE_PARTITION_SIZE := 16777216
# system_b is 516.04 MiB (0x2040b000). The file system uses all but the last 32 MiB.
# The last 32 MiB hold the U-Boot boot files for the eMMC boot (scripts/mk-systemb-tail.py):
# boot variables, kernel Image, ramdisk, and a debug-log area.
BOARD_SYSTEMIMAGE_PARTITION_SIZE := 507555840
# /data is an image file on the stock data partition (docs/persistent-data.md). This size is
# only for the build.
BOARD_USERDATAIMAGE_PARTITION_SIZE := 268435456
TARGET_USERIMAGES_USE_EXT4 := true

# Graphics.
USE_OPENGL_RENDERER := true
TARGET_USES_HWC2 := true
NUM_FRAMEBUFFER_SURFACE_BUFFERS := 3

# Panel rotation. The panel is portrait-native (fb0 480 x 800, virtual 480 x 1600)
# in a landscape device (docs/hardware.md): the fb0 origin is
# at the bottom-left of the screen, so a landscape point (X, Y) is at fb0
# (479 - Y, X). That is SurfaceFlinger ROT_90. Android 9 reads this value from the
# configstore service (android.hardware.configstore@1.1-service), not ro.sf.hwrotation.
SF_PRIMARY_DISPLAY_ORIENTATION := 90

# HWC: our hwcomposer.superbird (hwcomposer/). App buffers come from the ION system heap
# (not contiguous), and the OSD has no IOMMU and cannot rotate, so SurfaceFlinger composes
# all layers with the GPU into the frame buffer target (CMA), and the HWC shows that target.

# Bluetooth: BCM20703A2 on /dev/ttyS1, Broadcom libbt-vendor (docs/bluetooth-audio.md).
BOARD_HAVE_BLUETOOTH := true
BOARD_HAVE_BLUETOOTH_BCM := true
BOARD_CUSTOM_BT_CONFIG := device/spotify/superbird/bluetooth/vnd_superbird.txt
BOARD_BLUETOOTH_BDROID_BUILDCFG_INCLUDE_DIR := device/spotify/superbird/bluetooth

# Audio policy from /vendor/etc/audio_policy_configuration.xml (modules primary, a2dp, usb).
# Without this flag, Android 9 reads only the legacy audio_policy.conf. If that file is
# missing, the audio server uses a built-in default with one primary module, and A2DP
# audio has no output.
USE_XML_AUDIO_POLICY_CONF := 1

DEVICE_MANIFEST_FILE := device/spotify/superbird/manifest.xml

# Device SELinux policy (docs/selinux.md).
BOARD_SEPOLICY_DIRS += device/spotify/superbird/sepolicy
