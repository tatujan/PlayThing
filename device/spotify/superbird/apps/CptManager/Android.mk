# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# PlayThing App manager (docs/apps.md): the installed apps with their size and memory, and Open,
# Stop, Clear cache, Clear data, Remove, and Clean up (the caches of all apps). Signed with the
# platform key, as Settings: since Android 8 only a platform app can clear the caches of other
# apps (INTERNAL_DELETE_CACHE_FILES), and it uses the hidden APIs that Settings uses.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := CptManager
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform
include $(BUILD_PACKAGE)
