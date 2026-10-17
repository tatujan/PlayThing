# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# PlayThing keyboard (docs/keyboard.md): a minimal input method, so that text and PIN fields
# work, for example the Bluetooth PIN of a car stereo. Android 9 Go has no other input method
# in this image. A number row for number fields, QWERTY for text; touch and knob.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CptKeyboard
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_SDK_VERSION := current
include $(BUILD_PACKAGE)
