# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# PlayThing: Android 9 Go (512 MB) for the Spotify Car Thing, 32-bit userspace.
# core_tiny selects the 32-bit zygote. go_defaults_512 sets the low-RAM configuration.

$(call inherit-product, $(SRC_TARGET_DIR)/product/core_tiny.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/go_defaults_512.mk)
$(call inherit-product, device/spotify/superbird/device.mk)

PRODUCT_NAME := aosp_superbird
PRODUCT_DEVICE := superbird
PRODUCT_BRAND := plaything
PRODUCT_MODEL := superbird
PRODUCT_MANUFACTURER := plaything
