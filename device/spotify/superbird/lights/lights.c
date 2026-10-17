/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
/*
 * PlayThing lights HAL: the panel backlight only (docs/display-brightness.md).
 *
 * The backlight is the Amlogic PWM backlight /sys/class/backlight/aml-bl (1-255). Its PWM
 * has negative polarity (DT bl_pwm_attr method 0), so a higher value gives less light: the
 * stock daemon sp-als-backlight set 235 in the dark and 51 in bright light.
 * Android's level 1-255 maps linearly to persist.plaything.bl_dark (default 235, the stock
 * floor: darker values flicker) ..
 * persist.plaything.bl_bright (default 51); level 0 (screen off) gives bl_dark.
 * android.hardware.light@2.0-impl loads this module ("lights.superbird", ro.hardware is
 * "superbird"); the service runs as user system, and init.superbird.rc gives system the
 * brightness file. Android gives the brightness as the color of LIGHT_ID_BACKLIGHT.
 */
#define LOG_TAG "lights_superbird"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <cutils/properties.h>
#include <hardware/hardware.h>
#include <hardware/lights.h>
#include <log/log.h>

#define BACKLIGHT_FILE "/sys/class/backlight/aml-bl/brightness"
#define DEFAULT_DARK 235
#define DEFAULT_BRIGHT 51

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static int write_int(const char *path, int value)
{
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        ALOGE("open %s: %s", path, strerror(errno));
        return -errno;
    }
    char buf[16];
    int len = snprintf(buf, sizeof(buf), "%d\n", value);
    int ret = write(fd, buf, len) == len ? 0 : -errno;
    if (ret != 0) {
        ALOGE("write %s: %s", path, strerror(-ret));
    }
    close(fd);
    return ret;
}

static int rgb_to_brightness(const struct light_state_t *state)
{
    int color = state->color & 0x00ffffff;
    return ((77 * ((color >> 16) & 0xff)) + (150 * ((color >> 8) & 0xff)) +
            (29 * (color & 0xff))) >> 8;
}

static int clamp_level(int v)
{
    return v < 1 ? 1 : v > 255 ? 255 : v;
}

static int set_light_backlight(struct light_device_t *dev, const struct light_state_t *state)
{
    int brightness = rgb_to_brightness(state);
    int dark = clamp_level(property_get_int32("persist.plaything.bl_dark", DEFAULT_DARK));
    int bright = clamp_level(property_get_int32("persist.plaything.bl_bright", DEFAULT_BRIGHT));
    int level = brightness <= 0 ? dark : dark + (bright - dark) * (brightness - 1) / 254;
    pthread_mutex_lock(&g_lock);
    int ret = write_int(BACKLIGHT_FILE, level);
    pthread_mutex_unlock(&g_lock);
    return ret;
}

static int close_lights(struct hw_device_t *dev)
{
    free(dev);
    return 0;
}

static int open_lights(const struct hw_module_t *module, const char *name,
                       struct hw_device_t **device)
{
    if (strcmp(LIGHT_ID_BACKLIGHT, name) != 0) {
        return -EINVAL;
    }
    struct light_device_t *dev = calloc(1, sizeof(struct light_device_t));
    if (dev == NULL) {
        return -ENOMEM;
    }
    dev->common.tag = HARDWARE_DEVICE_TAG;
    dev->common.version = 0;
    dev->common.module = (struct hw_module_t *)module;
    dev->common.close = close_lights;
    dev->set_light = set_light_backlight;
    *device = (struct hw_device_t *)dev;
    return 0;
}

static struct hw_module_methods_t lights_module_methods = {
    .open = open_lights,
};

struct hw_module_t HAL_MODULE_INFO_SYM = {
    .tag = HARDWARE_MODULE_TAG,
    .version_major = 1,
    .version_minor = 0,
    .id = LIGHTS_HARDWARE_MODULE_ID,
    .name = "PlayThing lights HAL",
    .author = "PlayThing",
    .methods = &lights_module_methods,
};
