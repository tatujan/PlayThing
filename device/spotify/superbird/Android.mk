# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_DEVICE),superbird)

# Mali r16p0 userspace driver. Arm's license does not allow it in this repository or in the
# image: the build has a placeholder of the same size (scripts/aosp-prepare.sh), and
# scripts/flash.py writes the real file into the image (scripts/blobs.py).
include $(CLEAR_VARS)
LOCAL_MODULE := libGLES_mali
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE_SUFFIX := .so
LOCAL_MODULE_RELATIVE_PATH := egl
LOCAL_MULTILIB := 32
LOCAL_PROPRIETARY_MODULE := true
LOCAL_STRIP_MODULE := false
LOCAL_PREBUILT_MODULE_FILE := vendor/plaything/mali/libGLES_mali.so
include $(BUILD_PREBUILT)

# DiPlay release APK, with its own signature. The image has a placeholder of the same size
# (scripts/aosp-prepare.sh), and scripts/flash.py writes the release APK into it
# (scripts/blobs.py): the APK contains DiPlay's CarPlay accessory identity, so the published
# image does not. The build installs the file without changes
# (LOCAL_REPLACE_PREBUILT_APK_INSTALLED: no align step, which would fail on a placeholder) and
# without dexpreopt: Android verifies DiPlay at the first start and uses the JIT, as it did with
# DiPlay's own update in /data. Its native libraries and dex files are
# stored uncompressed, so Android uses them from the APK.
include $(CLEAR_VARS)
LOCAL_MODULE := DiPlay
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_MULTILIB := 32
LOCAL_DEX_PREOPT := false
LOCAL_REPLACE_PREBUILT_APK_INSTALLED := vendor/plaything/diplay/DiPlay.apk
include $(BUILD_PREBUILT)

# PlayThing home screen and keyboard. Make does not look below a folder that has
# its own Android.mk, so include them here. Each sets LOCAL_PATH to its own folder, so keep this one.
SUPERBIRD_DEVICE_DIR := $(LOCAL_PATH)
include $(SUPERBIRD_DEVICE_DIR)/apps/CptHome/Android.mk
include $(SUPERBIRD_DEVICE_DIR)/apps/CptKeyboard/Android.mk

endif
