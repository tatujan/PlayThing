# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# ThingPod (docs/thingpod.md): an iPod classic style music player; the knob is the click wheel.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := ThingPod
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_SDK_VERSION := current
include $(BUILD_PACKAGE)
