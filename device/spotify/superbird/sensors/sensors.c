/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
/*
 * PlayThing sensors HAL: the ambient light sensor only (docs/display-brightness.md).
 *
 * The TMD2772 (ams/TAOS, I2C 2-0039) has the kernel IIO driver tsl2x7x. The sensor sits
 * behind a dark window: the driver's in_illuminance0_input read a lit room as 2-3 lux, in
 * whole lux only. So this HAL computes lux from the raw
 * channel 0 count (in_intensity0_raw), divided by the integration time, times
 * persist.plaything.als_scale (lux per count per second). The default 0.33 estimates
 * real lux, with the lit room of the test as about 100 lux (Assumed): room 34 counts in
 * 0.111 s = 101, hand over the sensor 2 counts = 6; it saturates at about 31000. Android's
 * auto-brightness levels are whole lux, so the driver's own scale (room = 3) is too coarse.
 * The integration time stays at the driver default 0.111 s (the stock value): its store
 * function returns a wrong byte count, so a write does not end (init.superbird.rc).
 * It reads at the rate that Android asks for (at least every 100 ms) and reports a change
 * of more than 1 % or 0.01 lux. Android's automatic brightness smooths it.
 * android.hardware.sensors@1.0-impl loads this module ("sensors.superbird").
 */
#define LOG_TAG "sensors_superbird"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <cutils/properties.h>
#include <hardware/hardware.h>
#include <hardware/sensors.h>
#include <log/log.h>

#define HANDLE_LIGHT 1
#define IIO_DIR "/sys/bus/iio/devices"
#define MIN_PERIOD_NS 100000000LL
#define DEFAULT_PERIOD_NS 250000000LL
#define DEFAULT_SCALE "0.33"

static const struct sensor_t g_sensors[] = {
    {
        .name = "TMD2772 Ambient Light Sensor",
        .vendor = "ams (TAOS)",
        .version = 1,
        .handle = HANDLE_LIGHT,
        .type = SENSOR_TYPE_LIGHT,
        .maxRange = 60000.0f,
        .resolution = 1.0f,
        .power = 0.25f,
        .minDelay = 0,
        .fifoReservedEventCount = 0,
        .fifoMaxEventCount = 0,
        .stringType = SENSOR_STRING_TYPE_LIGHT,
        .requiredPermission = "",
        .maxDelay = 0,
        .flags = SENSOR_FLAG_ON_CHANGE_MODE,
    },
};

struct cpt_sensors {
    sensors_poll_device_1_t device;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    bool enabled;
    bool report_next;
    int flush_pending;
    int64_t period_ns;
    int64_t next_sample_ns; /* CLOCK_MONOTONIC */
    float last_lux;
    float scale;
    char raw_path[128];
    char time_path[128];
    char path[128]; /* the device directory; empty if not found */
};

static int64_t clock_ns(clockid_t clock)
{
    struct timespec t;
    clock_gettime(clock, &t);
    return t.tv_sec * 1000000000LL + t.tv_nsec;
}

/* Finds the light sensor's lux file (IIO device name "tmd2772", or another tsl2x7x part). */
static void find_light_sensor(struct cpt_sensors *ctx)
{
    DIR *dir = opendir(IIO_DIR);
    if (dir == NULL) {
        ALOGE("opendir %s: %s", IIO_DIR, strerror(errno));
        return;
    }
    struct dirent *de;
    while ((de = readdir(dir)) != NULL) {
        if (strncmp(de->d_name, "iio:device", 10) != 0) {
            continue;
        }
        char path[128], name[32] = "";
        snprintf(path, sizeof(path), IIO_DIR "/%s/name", de->d_name);
        FILE *f = fopen(path, "re");
        if (f == NULL) {
            continue;
        }
        if (fgets(name, sizeof(name), f) == NULL) {
            name[0] = '\0';
        }
        fclose(f);
        if (strncmp(name, "tmd", 3) == 0 || strncmp(name, "tsl", 3) == 0) {
            snprintf(ctx->path, sizeof(ctx->path), IIO_DIR "/%s", de->d_name);
            snprintf(ctx->raw_path, sizeof(ctx->raw_path), "%s/in_intensity0_raw", ctx->path);
            snprintf(ctx->time_path, sizeof(ctx->time_path),
                     "%s/in_illuminance0_integration_time", ctx->path);
            ALOGI("light sensor: %s (%s)", ctx->path, name);
            break;
        }
    }
    closedir(dir);
    if (ctx->path[0] == '\0') {
        ALOGE("no light sensor in %s", IIO_DIR);
    }
}

static float read_float(const char *path)
{
    char buf[32];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return -1.0f;
    }
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) {
        return -1.0f;
    }
    buf[n] = '\0';
    return strtof(buf, NULL);
}

/* Lux from the channel 0 count per second; -1 on a read error. */
static float read_lux(const struct cpt_sensors *ctx)
{
    float count = read_float(ctx->raw_path);
    float seconds = read_float(ctx->time_path);
    if (count < 0.0f || seconds <= 0.0f) {
        return -1.0f;
    }
    return count / seconds * ctx->scale;
}

static int poll_activate(struct sensors_poll_device_t *dev, int handle, int enabled)
{
    struct cpt_sensors *ctx = (struct cpt_sensors *)dev;
    if (handle != HANDLE_LIGHT) {
        return -EINVAL;
    }
    pthread_mutex_lock(&ctx->lock);
    ctx->enabled = enabled != 0;
    if (ctx->enabled) {
        /* An on-change sensor reports its value at once after activation. */
        ctx->report_next = true;
        ctx->next_sample_ns = 0;
        char value[PROPERTY_VALUE_MAX];
        property_get("persist.plaything.als_scale", value, DEFAULT_SCALE);
        ctx->scale = strtof(value, NULL);
        if (ctx->scale <= 0.0f) {
            ctx->scale = strtof(DEFAULT_SCALE, NULL);
        }
    }
    pthread_cond_broadcast(&ctx->cond);
    pthread_mutex_unlock(&ctx->lock);
    ALOGI("light sensor %s", enabled ? "on" : "off");
    return 0;
}

static int poll_batch(struct sensors_poll_device_1 *dev, int handle, int flags,
                      int64_t period_ns, int64_t timeout)
{
    struct cpt_sensors *ctx = (struct cpt_sensors *)dev;
    if (handle != HANDLE_LIGHT) {
        return -EINVAL;
    }
    pthread_mutex_lock(&ctx->lock);
    ctx->period_ns = period_ns < MIN_PERIOD_NS ? MIN_PERIOD_NS : period_ns;
    pthread_cond_broadcast(&ctx->cond);
    pthread_mutex_unlock(&ctx->lock);
    return 0;
}

static int poll_set_delay(struct sensors_poll_device_t *dev, int handle, int64_t ns)
{
    return poll_batch((struct sensors_poll_device_1 *)dev, handle, 0, ns, 0);
}

static int poll_flush(struct sensors_poll_device_1 *dev, int handle)
{
    struct cpt_sensors *ctx = (struct cpt_sensors *)dev;
    if (handle != HANDLE_LIGHT) {
        return -EINVAL;
    }
    pthread_mutex_lock(&ctx->lock);
    ctx->flush_pending++;
    pthread_cond_broadcast(&ctx->cond);
    pthread_mutex_unlock(&ctx->lock);
    return 0;
}

static int poll_poll(struct sensors_poll_device_t *dev, sensors_event_t *data, int count)
{
    struct cpt_sensors *ctx = (struct cpt_sensors *)dev;
    if (count <= 0) {
        return -EINVAL;
    }
    pthread_mutex_lock(&ctx->lock);
    for (;;) {
        if (ctx->flush_pending > 0) {
            int n = 0;
            while (ctx->flush_pending > 0 && n < count) {
                memset(&data[n], 0, sizeof(data[n]));
                data[n].version = META_DATA_VERSION;
                data[n].type = SENSOR_TYPE_META_DATA;
                data[n].meta_data.what = META_DATA_FLUSH_COMPLETE;
                data[n].meta_data.sensor = HANDLE_LIGHT;
                ctx->flush_pending--;
                n++;
            }
            pthread_mutex_unlock(&ctx->lock);
            return n;
        }
        if (!ctx->enabled || ctx->path[0] == '\0') {
            pthread_cond_wait(&ctx->cond, &ctx->lock);
            continue;
        }
        int64_t now = clock_ns(CLOCK_MONOTONIC);
        if (now >= ctx->next_sample_ns) {
            ctx->next_sample_ns = now + ctx->period_ns;
            pthread_mutex_unlock(&ctx->lock);
            float lux = read_lux(ctx);
            pthread_mutex_lock(&ctx->lock);
            if (lux >= 0.0f && ctx->enabled) {
                float delta = fabsf(lux - ctx->last_lux);
                if (ctx->report_next || (delta > 0.01f && delta > 0.01f * ctx->last_lux)) {
                    ctx->report_next = false;
                    ctx->last_lux = lux;
                    memset(&data[0], 0, sizeof(data[0]));
                    data[0].version = sizeof(sensors_event_t);
                    data[0].sensor = HANDLE_LIGHT;
                    data[0].type = SENSOR_TYPE_LIGHT;
                    data[0].timestamp = clock_ns(CLOCK_BOOTTIME);
                    data[0].light = lux;
                    pthread_mutex_unlock(&ctx->lock);
                    return 1;
                }
            }
            continue;
        }
        struct timespec deadline = {
            .tv_sec = ctx->next_sample_ns / 1000000000LL,
            .tv_nsec = ctx->next_sample_ns % 1000000000LL,
        };
        pthread_cond_timedwait(&ctx->cond, &ctx->lock, &deadline);
    }
}

static int poll_close(struct hw_device_t *dev)
{
    struct cpt_sensors *ctx = (struct cpt_sensors *)dev;
    pthread_cond_destroy(&ctx->cond);
    pthread_mutex_destroy(&ctx->lock);
    free(ctx);
    return 0;
}

static int open_sensors(const struct hw_module_t *module, const char *id,
                        struct hw_device_t **device)
{
    struct cpt_sensors *ctx = calloc(1, sizeof(struct cpt_sensors));
    if (ctx == NULL) {
        return -ENOMEM;
    }
    pthread_mutex_init(&ctx->lock, NULL);
    pthread_condattr_t attr;
    pthread_condattr_init(&attr);
    pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
    pthread_cond_init(&ctx->cond, &attr);
    pthread_condattr_destroy(&attr);
    ctx->period_ns = DEFAULT_PERIOD_NS;
    find_light_sensor(ctx);

    ctx->device.common.tag = HARDWARE_DEVICE_TAG;
    ctx->device.common.version = SENSORS_DEVICE_API_VERSION_1_3;
    ctx->device.common.module = (struct hw_module_t *)module;
    ctx->device.common.close = poll_close;
    ctx->device.activate = poll_activate;
    ctx->device.setDelay = poll_set_delay;
    ctx->device.poll = poll_poll;
    ctx->device.batch = poll_batch;
    ctx->device.flush = poll_flush;
    *device = &ctx->device.common;
    return 0;
}

static int get_sensors_list(struct sensors_module_t *module, struct sensor_t const **list)
{
    *list = g_sensors;
    return sizeof(g_sensors) / sizeof(g_sensors[0]);
}

static int set_operation_mode(unsigned int mode)
{
    return mode == SENSOR_HAL_NORMAL_MODE ? 0 : -EINVAL;
}

static struct hw_module_methods_t sensors_module_methods = {
    .open = open_sensors,
};

struct sensors_module_t HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .version_major = 1,
        .version_minor = 0,
        .id = SENSORS_HARDWARE_MODULE_ID,
        .name = "PlayThing sensors HAL",
        .author = "PlayThing",
        .methods = &sensors_module_methods,
    },
    .get_sensors_list = get_sensors_list,
    .set_operation_mode = set_operation_mode,
};
