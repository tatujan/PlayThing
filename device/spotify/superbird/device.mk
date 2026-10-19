# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# PlayThing device configuration (docs/android.md).

LOCAL_PATH := device/spotify/superbird
# scripts/aosp-prepare.sh copies the kernel, DTB, and Mali module here.
SUPERBIRD_KERNEL_DIR := device/spotify/superbird-kernel

# There is no vendor partition (BoardConfig.mk). init reads the files in rootdir/
# before /system is mounted, so they go into the ramdisk.

PRODUCT_AAPT_CONFIG := normal
PRODUCT_AAPT_PREF_CONFIG := hdpi

# UI: SystemUI (notification shade, quick settings), Settings (Bluetooth pairing), the
# PlayThing home screen (apps/CptHome, docs/home-screen.md; it replaces Launcher3Go, which
# uses 39 MB PSS), and the PlayThing keyboard (apps/CptKeyboard, docs/keyboard.md: without an
# input method, a car stereo's Bluetooth PIN cannot be typed).
PRODUCT_PACKAGES += \
    SystemUI \
    CptHome \
    CptKeyboard \
    Settings \
    VpnDialogs

# DiPlay, the CarPlay app (docs/diplay.md). The release APK has no HOME activity; after a
# boot the home screen (CptHome) shows. diplay-setup.sh sets DiPlay's app-ops and settings,
# and the car button icon in CarPlay.
PRODUCT_PACKAGES += \
    DiPlay

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/diplay/default-permissions-diplay.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default-permissions/default-permissions-diplay.xml \
    $(LOCAL_PATH)/diplay/diplay-setup.sh:$(TARGET_COPY_OUT_VENDOR)/bin/diplay-setup.sh \
    $(LOCAL_PATH)/diplay/carplay-home-icon.png:$(TARGET_COPY_OUT_VENDOR)/etc/diplay/carplay-home-icon.png

# Graphics: Mali r16p0 blob, Mali gralloc, the HWC of this device folder (docs/graphics.md).
PRODUCT_PACKAGES += \
    libGLES_mali \
    gralloc.aosp_superbird \
    hwcomposer.superbird \
    android.hardware.graphics.allocator@2.0-impl \
    android.hardware.graphics.allocator@2.0-service \
    android.hardware.graphics.mapper@2.0-impl \
    android.hardware.graphics.composer@2.1-impl \
    android.hardware.graphics.composer@2.1-service \
    android.hardware.configstore@1.1-service

# Zygote preloads libcompiler_rt (ZygoteInit.preloadSharedLibraries). core_tiny does not
# install it, and no installed module pulls it in.
PRODUCT_PACKAGES += \
    libcompiler_rt

# PackageManagerService needs exactly one installer and one uninstaller app
# ("There must be exactly one installer"). core_tiny does not install PackageInstaller
# It also has the runtime permission screens.
PRODUCT_PACKAGES += \
    PackageInstaller

# SystemServer starts EthernetService when the device has the USB host feature
# (android.hardware.usb.host, for DiPlay). core_tiny has no ethernet-service. It also gives
# Ethernet through a USB dongle.
PRODUCT_PACKAGES += \
    ethernet-service
PRODUCT_SYSTEM_SERVER_JARS += \
    ethernet-service

# Other HALs for the framework.
PRODUCT_PACKAGES += \
    android.hardware.audio@2.0-service \
    android.hardware.audio@4.0-impl \
    android.hardware.audio.effect@4.0-impl \
    android.hardware.keymaster@3.0-impl \
    android.hardware.keymaster@3.0-service \
    android.hardware.health@2.0-service

# Bluetooth and the audio path: A2DP to the car first, USB audio (AUX) as the
# fallback (docs/bluetooth-audio.md).
PRODUCT_PACKAGES += \
    android.hardware.bluetooth@1.0-impl \
    android.hardware.bluetooth@1.0-service \
    libbt-vendor \
    audio.a2dp.default \
    audio.usb.default

# Primary audio HAL: stub output, PDM microphone input (audio/hal/audio_hw.c, docs/bluetooth-audio.md).
# The tinyalsa tools are for microphone tests.
PRODUCT_PACKAGES += \
    audio.primary.superbird \
    tinycap \
    tinymix \
    tinypcminfo

# Backlight and ambient light sensor (lights/, sensors/, docs/display-brightness.md).
PRODUCT_PACKAGES += \
    android.hardware.light@2.0-impl \
    android.hardware.light@2.0-service \
    lights.superbird \
    android.hardware.sensors@1.0-impl \
    android.hardware.sensors@1.0-service \
    sensors.superbird

DEVICE_PACKAGE_OVERLAYS += $(LOCAL_PATH)/overlay

# persist.plaything.avc_decode_order: decode-order output in the soft AVC decoder
# (patches/aosp/frameworks/av/0002-*), for the software decoder: CarPlay decode latency p50
# 80-95 ms -> 17-23 ms.
# ro.config.knob_sequential_focus: knob LEFT/RIGHT that an app does not handle move the focus
# in focus order, as TAB (patches/aosp/frameworks/base/0001-*, docs/input.md).
# Persistent logs (docs/known-issues.md): the Car Thing has no battery, so a reboot after a
# drive would erase the logs of a USB drop in the car. logcatd (userdebug) writes the Android and
# kernel logs to /data/misc/logd, 16 files of 1 MB. On a low-RAM device logd reads the kernel
# log only with ro.logd.kernel=true.
PRODUCT_PROPERTY_OVERRIDES += \
    ro.logd.kernel=true \
    persist.logd.logpersistd=logcatd \
    persist.logd.logpersistd.size=16 \
    persist.plaything.avc_decode_order=1 \
    ro.config.knob_sequential_focus=true \
    ro.audio.a2dp_in_communication=true \
    ro.hardware.egl=mali \
    ro.hardware.gralloc=aosp_superbird \
    ro.hardware.hwcomposer=superbird \
    ro.opengles.version=196610 \
    ro.sf.lcd_density=240

PRODUCT_COPY_FILES += \
    $(SUPERBIRD_KERNEL_DIR)/Image.gz:kernel \
    $(SUPERBIRD_KERNEL_DIR)/mali_kbase.ko:$(TARGET_COPY_OUT_VENDOR)/lib/modules/mali.ko \
    $(LOCAL_PATH)/rootdir/init.superbird.rc:root/init.superbird.rc \
    $(LOCAL_PATH)/rootdir/ueventd.superbird.rc:root/ueventd.superbird.rc \
    $(LOCAL_PATH)/rootdir/fstab.superbird:root/fstab.superbird \
    $(LOCAL_PATH)/rootdir/bt-setup.sh:root/bt-setup.sh \
    $(LOCAL_PATH)/rootdir/data-setup.sh:root/data-setup.sh \
    $(LOCAL_PATH)/public.libraries.txt:system/etc/public.libraries.txt \
    $(LOCAL_PATH)/media_codecs.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs.xml \
    frameworks/av/media/libstagefright/data/media_codecs_google_audio.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_audio.xml \
    frameworks/av/media/libstagefright/data/media_codecs_google_video.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_video.xml


AUDIO_POLICY_DIR := frameworks/av/services/audiopolicy/config
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/audio/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml \
    $(LOCAL_PATH)/audio/primary_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/primary_audio_policy_configuration.xml \
    $(AUDIO_POLICY_DIR)/a2dp_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_audio_policy_configuration.xml \
    $(AUDIO_POLICY_DIR)/usb_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/usb_audio_policy_configuration.xml \
    $(AUDIO_POLICY_DIR)/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    $(AUDIO_POLICY_DIR)/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml

# Bluetooth firmware: not in the image (Broadcom's license). The Bluetooth HAL reads it from
# the unit's stock root file system (init.superbird.rc, bluetooth/vnd_superbird.txt).

# Features. DiPlay needs USB host. The audio path needs Bluetooth.
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
    frameworks/native/data/etc/android.hardware.bluetooth.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth.xml \
    frameworks/native/data/etc/android.hardware.sensor.light.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.light.xml \
    frameworks/native/data/etc/android.hardware.bluetooth_le.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth_le.xml \
    frameworks/native/data/etc/android.hardware.touchscreen.multitouch.distinct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.touchscreen.multitouch.distinct.xml \
    frameworks/native/data/etc/android.hardware.opengles.aep.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.opengles.aep.xml \
    $(LOCAL_PATH)/permissions/plaything-knob.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/plaything-knob.xml

# H.264 on the Amlogic hardware decoder (docs/video.md). The kernel modules come from
# scripts/media-modules.sh (copied by aosp-prepare.sh). The decoder firmware is a placeholder
# here, and scripts/flash.py writes the real file into the image (scripts/blobs.py);
# init.superbird.rc loads them in post-fs. The OMX component is in codecs/cpth264dec.
SUPERBIRD_MEDIA_DIR := device/spotify/superbird-media
PRODUCT_COPY_FILES += \
    $(foreach f,$(wildcard $(SUPERBIRD_MEDIA_DIR)/*.ko),$(f):$(TARGET_COPY_OUT_VENDOR)/lib/modules/$(notdir $(f))) \
    $(foreach f,$(wildcard $(SUPERBIRD_MEDIA_DIR)/video_ucode.bin),$(f):$(TARGET_COPY_OUT_VENDOR)/lib/firmware/video/video_ucode.bin)
PRODUCT_PACKAGES += \
    libstagefright_soft_cpth264dec

# Key layouts. core_tiny has none, so Android ignored the button events. Generic.kl maps the
# knob and button codes that the boot area sets (scripts/mk-systemb-tail.py, KEY_EDITS).
$(call inherit-product, frameworks/base/data/keyboards/keyboards.mk)

# The Car Thing has no Wi-Fi, but SystemUI (WifiTracker, WifiSignalController) crashes when
# WifiManager is null. With this feature, system_server starts its Wi-Fi service, which stays
# disabled without a Wi-Fi HAL.
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml

# Log buffers: 256 KiB each (the low-RAM default of 64 KiB loses boot messages).
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
    ro.logd.size=256K

# ADB over TCP (init.superbird.rc) needs an authorized key. Put your own public key
# (~/.android/adbkey.pub) at device/spotify/superbird/adb_keys.pub before the build (git
# ignores it): it is installed as /adb_keys in the ramdisk, so it works also with /data in RAM.
# Without it, Android asks "Allow USB debugging?" on the panel for each new key.
ifneq ($(wildcard $(LOCAL_PATH)/adb_keys.pub),)
PRODUCT_ADB_KEYS := $(LOCAL_PATH)/adb_keys.pub
PRODUCT_PACKAGES += adb_keys
endif
PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
    ro.adb.secure=1
