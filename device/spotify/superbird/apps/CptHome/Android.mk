# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# PlayThing home screen (docs/home-screen.md): replaces Launcher3Go.
# Tiles for CarPlay (DiPlay), Bluetooth settings, and Settings, and the Bluetooth status.
# Android.mk, because Soong in Android 9 has no "overrides" for apps.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CptHome
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_SDK_VERSION := current
LOCAL_OVERRIDES_PACKAGES := Launcher3Go Launcher3 Launcher3QuickStep Launcher2
include $(BUILD_PACKAGE)
