/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Based on hardware/libhardware/modules/audio/audio_hw.c of AOSP (the stub output timing and
 * the HAL boilerplate); changed for the Car Thing microphones.
 */
/*
 * PlayThing primary audio HAL (docs/bluetooth-audio.md).
 *
 * Output: the Car Thing has no speaker. The primary output is a stub with the timing of the
 * AOSP stub (hardware/libhardware/modules/audio/audio_hw.c). Car audio goes to the a2dp or
 * usb module.
 *
 * Input: the four PDM microphones (ALSA card 0, device 0, "PDM-dummy-alsaPORT-pdm",
 * DT lines PDM_DIN0 and PDM_DIN1). The HAL reads S32_LE, 4 channels, 48 kHz, mixes the
 * selected microphones to mono, applies a gain, and gives 16-bit PCM at 48 kHz, mono or
 * stereo. AudioFlinger resamples to the client rate (Siri over CarPlay: 24 kHz).
 * Measured on the device: all four channels carry the
 * same sound field (correlation 0.87-0.98); a quiet room is about -76 dBFS and normal speech
 * at 50 cm about -55 dBFS in 50 ms blocks, so the default gain is +24 dB; the first 200 ms
 * after the start are a filter transient (up to 0 dBFS), so the HAL discards 250 ms.
 *
 * Properties (read when an input stream opens):
 *   persist.plaything.mic_gain_db   gain in dB, 0 to 48 (default 24)
 *   persist.plaything.mic_mask      microphones to mix, bit 0-3 = channel 0-3 (default 0xf)
 *   debug.plaything.mic_dump        path of an existing writable file: append the HAL
 *                                      output (16-bit PCM) there, for tests
 */
#define LOG_TAG "audio_hw_superbird"

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include <cutils/properties.h>
#include <hardware/audio.h>
#include <hardware/hardware.h>
#include <log/log.h>
#include <system/audio.h>
#include <tinyalsa/asoundlib.h>

#define PCM_CARD 0
#define PCM_DEVICE 0
#define MIC_CHANNELS 4
#define MIC_RATE 48000
#define MIC_PERIOD_FRAMES 480 /* 10 ms */
#define MIC_PERIOD_COUNT 4
#define MIC_DISCARD_MS 250
#define IN_BUFFER_MS 20

#define OUT_DEFAULT_RATE 44100
#define OUT_BUFFER_MS 10

struct cpt_audio_device {
    struct audio_hw_device device;
    pthread_mutex_t lock;
    bool mic_mute;
};

struct cpt_stream_out {
    struct audio_stream_out stream;
    int64_t last_write_time_us;
    uint32_t sample_rate;
    audio_channel_mask_t channel_mask;
    audio_format_t format;
    size_t frame_count;
};

struct cpt_stream_in {
    struct audio_stream_in stream;
    struct cpt_audio_device *dev;
    pthread_mutex_t lock;
    audio_channel_mask_t channel_mask;
    struct pcm *pcm;
    int32_t *raw;
    size_t raw_frames;
    size_t discard_frames;
    int64_t gain_q12; /* linear gain, 4.12 fixed point */
    unsigned int mask;
    unsigned int mask_count;
    int dump_fd;
    int64_t last_read_time_us; /* for the timing when the PCM device fails */
    int64_t next_open_us;
    unsigned int errors;
};

static int64_t now_us(void)
{
    struct timespec t = { .tv_sec = 0, .tv_nsec = 0 };
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (t.tv_sec * 1000000000LL + t.tv_nsec) / 1000;
}

/* ---- Output: stub with simulated timing ---- */

static uint32_t out_get_sample_rate(const struct audio_stream *stream)
{
    return ((const struct cpt_stream_out *)stream)->sample_rate;
}

static int out_set_sample_rate(struct audio_stream *stream, uint32_t rate)
{
    ((struct cpt_stream_out *)stream)->sample_rate = rate;
    return 0;
}

static size_t out_get_buffer_size(const struct audio_stream *stream)
{
    const struct cpt_stream_out *out = (const struct cpt_stream_out *)stream;
    return out->frame_count * audio_stream_out_frame_size(&out->stream);
}

static audio_channel_mask_t out_get_channels(const struct audio_stream *stream)
{
    return ((const struct cpt_stream_out *)stream)->channel_mask;
}

static audio_format_t out_get_format(const struct audio_stream *stream)
{
    return ((const struct cpt_stream_out *)stream)->format;
}

static int out_set_format(struct audio_stream *stream, audio_format_t format)
{
    ((struct cpt_stream_out *)stream)->format = format;
    return 0;
}

static int out_standby(struct audio_stream *stream)
{
    return 0;
}

static int out_dump(const struct audio_stream *stream, int fd)
{
    return 0;
}

static int out_set_parameters(struct audio_stream *stream, const char *kvpairs)
{
    return 0;
}

static char *out_get_parameters(const struct audio_stream *stream, const char *keys)
{
    return strdup("");
}

static uint32_t out_get_latency(const struct audio_stream_out *stream)
{
    return OUT_BUFFER_MS;
}

static int out_set_volume(struct audio_stream_out *stream, float left, float right)
{
    return 0;
}

static ssize_t out_write(struct audio_stream_out *stream, const void *buffer, size_t bytes)
{
    /* No speaker: discard the data, and sleep for its duration (as the AOSP stub). */
    struct cpt_stream_out *out = (struct cpt_stream_out *)stream;
    const int64_t now = now_us();
    int64_t sleep_time = bytes * 1000000LL / audio_stream_out_frame_size(stream) /
            out->sample_rate - (now - out->last_write_time_us);
    if (sleep_time > 0) {
        usleep(sleep_time);
    } else {
        sleep_time = 0;
    }
    out->last_write_time_us = now + sleep_time;
    return bytes;
}

static int out_get_render_position(const struct audio_stream_out *stream, uint32_t *dsp_frames)
{
    *dsp_frames = 0;
    return -EINVAL;
}

static int out_add_audio_effect(const struct audio_stream *stream, effect_handle_t effect)
{
    return 0;
}

static int out_remove_audio_effect(const struct audio_stream *stream, effect_handle_t effect)
{
    return 0;
}

static int out_get_next_write_timestamp(const struct audio_stream_out *stream, int64_t *timestamp)
{
    *timestamp = 0;
    return -EINVAL;
}

/* ---- Input: PDM microphones ---- */

static size_t in_frame_size(const struct cpt_stream_in *in)
{
    return audio_channel_count_from_in_mask(in->channel_mask) * sizeof(int16_t);
}

static uint32_t in_get_sample_rate(const struct audio_stream *stream)
{
    return MIC_RATE;
}

static int in_set_sample_rate(struct audio_stream *stream, uint32_t rate)
{
    return rate == MIC_RATE ? 0 : -EINVAL;
}

static size_t in_get_buffer_size(const struct audio_stream *stream)
{
    const struct cpt_stream_in *in = (const struct cpt_stream_in *)stream;
    return MIC_RATE * IN_BUFFER_MS / 1000 * in_frame_size(in);
}

static audio_channel_mask_t in_get_channels(const struct audio_stream *stream)
{
    return ((const struct cpt_stream_in *)stream)->channel_mask;
}

static audio_format_t in_get_format(const struct audio_stream *stream)
{
    return AUDIO_FORMAT_PCM_16_BIT;
}

static int in_set_format(struct audio_stream *stream, audio_format_t format)
{
    return format == AUDIO_FORMAT_PCM_16_BIT ? 0 : -EINVAL;
}

/* Called with in->lock held. */
static void in_close_pcm(struct cpt_stream_in *in)
{
    if (in->pcm != NULL) {
        pcm_close(in->pcm);
        in->pcm = NULL;
        ALOGI("microphones closed");
    }
}

/* Called with in->lock held. */
static int in_open_pcm(struct cpt_stream_in *in)
{
    struct pcm_config config = {
        .channels = MIC_CHANNELS,
        .rate = MIC_RATE,
        .period_size = MIC_PERIOD_FRAMES,
        .period_count = MIC_PERIOD_COUNT,
        .format = PCM_FORMAT_S32_LE,
    };
    struct pcm *pcm = pcm_open(PCM_CARD, PCM_DEVICE, PCM_IN, &config);
    if (pcm == NULL || !pcm_is_ready(pcm)) {
        ALOGE("pcm_open(card %d, device %d) failed: %s", PCM_CARD, PCM_DEVICE,
              pcm != NULL ? pcm_get_error(pcm) : "no memory");
        if (pcm != NULL) {
            pcm_close(pcm);
        }
        return -ENODEV;
    }
    in->pcm = pcm;
    in->discard_frames = MIC_RATE * MIC_DISCARD_MS / 1000;
    ALOGI("microphones open: S32_LE %d ch %d Hz, period %d x %d, mask 0x%x, gain q12 %lld",
          MIC_CHANNELS, MIC_RATE, MIC_PERIOD_FRAMES, MIC_PERIOD_COUNT, in->mask,
          (long long)in->gain_q12);
    return 0;
}

static int in_standby(struct audio_stream *stream)
{
    struct cpt_stream_in *in = (struct cpt_stream_in *)stream;
    pthread_mutex_lock(&in->lock);
    in_close_pcm(in);
    in->last_read_time_us = 0;
    pthread_mutex_unlock(&in->lock);
    return 0;
}

static int in_dump(const struct audio_stream *stream, int fd)
{
    return 0;
}

static int in_set_parameters(struct audio_stream *stream, const char *kvpairs)
{
    return 0;
}

static char *in_get_parameters(const struct audio_stream *stream, const char *keys)
{
    return strdup("");
}

static int in_set_gain(struct audio_stream_in *stream, float gain)
{
    return 0;
}

/* Silence with the real-time duration, when the PCM device is not available. */
static void in_fill_silence(struct cpt_stream_in *in, void *buffer, size_t bytes)
{
    const int64_t now = now_us();
    const int64_t elapsed = in->last_read_time_us == 0 ? 0 : now - in->last_read_time_us;
    int64_t sleep_time = bytes * 1000000LL / in_frame_size(in) / MIC_RATE - elapsed;
    if (sleep_time > 0) {
        usleep(sleep_time);
    } else {
        sleep_time = 0;
    }
    in->last_read_time_us = now + sleep_time;
    memset(buffer, 0, bytes);
}

static ssize_t in_read(struct audio_stream_in *stream, void *buffer, size_t bytes)
{
    struct cpt_stream_in *in = (struct cpt_stream_in *)stream;
    const size_t out_channels = audio_channel_count_from_in_mask(in->channel_mask);
    const size_t frames = bytes / in_frame_size(in);
    int16_t *dst = (int16_t *)buffer;

    pthread_mutex_lock(&in->lock);

    if (in->pcm == NULL) {
        /* After an error, try the device again at most every second. */
        if (now_us() < in->next_open_us || in_open_pcm(in) != 0) {
            if (in->next_open_us <= now_us()) {
                in->next_open_us = now_us() + 1000000;
            }
            in_fill_silence(in, buffer, bytes);
            pthread_mutex_unlock(&in->lock);
            return bytes;
        }
    }

    if (in->raw_frames < frames) {
        int32_t *raw = realloc(in->raw, frames * MIC_CHANNELS * sizeof(int32_t));
        if (raw == NULL) {
            in_fill_silence(in, buffer, bytes);
            pthread_mutex_unlock(&in->lock);
            return bytes;
        }
        in->raw = raw;
        in->raw_frames = frames;
    }

    if (pcm_read(in->pcm, in->raw, pcm_frames_to_bytes(in->pcm, frames)) != 0) {
        if (in->errors++ < 10) {
            ALOGW("pcm_read failed: %s", pcm_get_error(in->pcm));
        }
        in_close_pcm(in);
        in->next_open_us = now_us() + 1000000;
        in_fill_silence(in, buffer, bytes);
        pthread_mutex_unlock(&in->lock);
        return bytes;
    }
    in->last_read_time_us = now_us();

    const bool mute = in->dev->mic_mute;
    for (size_t i = 0; i < frames; i++) {
        int16_t sample = 0;
        if (in->discard_frames > 0) {
            in->discard_frames--;
        } else if (!mute) {
            const int32_t *f = &in->raw[i * MIC_CHANNELS];
            int64_t sum = 0;
            for (int c = 0; c < MIC_CHANNELS; c++) {
                if (in->mask & (1u << c)) {
                    sum += f[c];
                }
            }
            /* Mean of the microphones (S32 full scale), times the gain, to S16. */
            int64_t v = sum * in->gain_q12 / ((int64_t)in->mask_count << (12 + 16));
            if (v > INT16_MAX) {
                v = INT16_MAX;
            } else if (v < INT16_MIN) {
                v = INT16_MIN;
            }
            sample = (int16_t)v;
        }
        for (size_t c = 0; c < out_channels; c++) {
            dst[i * out_channels + c] = sample;
        }
    }

    if (in->dump_fd >= 0 && write(in->dump_fd, buffer, bytes) < 0) {
        close(in->dump_fd);
        in->dump_fd = -1;
    }

    pthread_mutex_unlock(&in->lock);
    return bytes;
}

static uint32_t in_get_input_frames_lost(struct audio_stream_in *stream)
{
    return 0;
}

static int in_add_audio_effect(const struct audio_stream *stream, effect_handle_t effect)
{
    return 0;
}

static int in_remove_audio_effect(const struct audio_stream *stream, effect_handle_t effect)
{
    return 0;
}

/* ---- Device ---- */

static int adev_open_output_stream(struct audio_hw_device *dev, audio_io_handle_t handle,
                                   audio_devices_t devices, audio_output_flags_t flags,
                                   struct audio_config *config,
                                   struct audio_stream_out **stream_out,
                                   const char *address __unused)
{
    *stream_out = NULL;
    struct cpt_stream_out *out = calloc(1, sizeof(struct cpt_stream_out));
    if (out == NULL) {
        return -ENOMEM;
    }
    out->stream.common.get_sample_rate = out_get_sample_rate;
    out->stream.common.set_sample_rate = out_set_sample_rate;
    out->stream.common.get_buffer_size = out_get_buffer_size;
    out->stream.common.get_channels = out_get_channels;
    out->stream.common.get_format = out_get_format;
    out->stream.common.set_format = out_set_format;
    out->stream.common.standby = out_standby;
    out->stream.common.dump = out_dump;
    out->stream.common.set_parameters = out_set_parameters;
    out->stream.common.get_parameters = out_get_parameters;
    out->stream.common.add_audio_effect = out_add_audio_effect;
    out->stream.common.remove_audio_effect = out_remove_audio_effect;
    out->stream.get_latency = out_get_latency;
    out->stream.set_volume = out_set_volume;
    out->stream.write = out_write;
    out->stream.get_render_position = out_get_render_position;
    out->stream.get_next_write_timestamp = out_get_next_write_timestamp;
    out->sample_rate = config->sample_rate != 0 ? config->sample_rate : OUT_DEFAULT_RATE;
    out->channel_mask = config->channel_mask != AUDIO_CHANNEL_NONE ? config->channel_mask
                                                                   : AUDIO_CHANNEL_OUT_STEREO;
    out->format = config->format != AUDIO_FORMAT_DEFAULT ? config->format
                                                         : AUDIO_FORMAT_PCM_16_BIT;
    out->frame_count = OUT_BUFFER_MS * out->sample_rate / 1000;
    *stream_out = &out->stream;
    return 0;
}

static void adev_close_output_stream(struct audio_hw_device *dev, struct audio_stream_out *stream)
{
    free(stream);
}

static int adev_set_parameters(struct audio_hw_device *dev, const char *kvpairs)
{
    return 0;
}

static char *adev_get_parameters(const struct audio_hw_device *dev, const char *keys)
{
    return strdup("");
}

static int adev_init_check(const struct audio_hw_device *dev)
{
    return 0;
}

static int adev_set_voice_volume(struct audio_hw_device *dev, float volume)
{
    return -ENOSYS;
}

static int adev_set_master_volume(struct audio_hw_device *dev, float volume)
{
    return -ENOSYS;
}

static int adev_get_master_volume(struct audio_hw_device *dev, float *volume)
{
    return -ENOSYS;
}

static int adev_set_master_mute(struct audio_hw_device *dev, bool muted)
{
    return -ENOSYS;
}

static int adev_get_master_mute(struct audio_hw_device *dev, bool *muted)
{
    return -ENOSYS;
}

static int adev_set_mode(struct audio_hw_device *dev, audio_mode_t mode)
{
    return 0;
}

static int adev_set_mic_mute(struct audio_hw_device *dev, bool state)
{
    struct cpt_audio_device *adev = (struct cpt_audio_device *)dev;
    adev->mic_mute = state;
    return 0;
}

static int adev_get_mic_mute(const struct audio_hw_device *dev, bool *state)
{
    *state = ((const struct cpt_audio_device *)dev)->mic_mute;
    return 0;
}

static bool in_config_supported(const struct audio_config *config)
{
    const uint32_t channels = audio_channel_count_from_in_mask(config->channel_mask);
    return config->sample_rate == MIC_RATE && config->format == AUDIO_FORMAT_PCM_16_BIT &&
           (channels == 1 || channels == 2);
}

static size_t adev_get_input_buffer_size(const struct audio_hw_device *dev,
                                         const struct audio_config *config)
{
    /* Any PCM rate: AudioFlinger asks with the client rate and resamples from 48 kHz. */
    const uint32_t channels = audio_channel_count_from_in_mask(config->channel_mask);
    if (config->format != AUDIO_FORMAT_PCM_16_BIT || channels < 1 || channels > 2 ||
        config->sample_rate == 0) {
        return 0;
    }
    return config->sample_rate * IN_BUFFER_MS / 1000 * channels * sizeof(int16_t);
}

static int adev_open_input_stream(struct audio_hw_device *dev, audio_io_handle_t handle,
                                  audio_devices_t devices, struct audio_config *config,
                                  struct audio_stream_in **stream_in,
                                  audio_input_flags_t flags __unused,
                                  const char *address __unused,
                                  audio_source_t source)
{
    *stream_in = NULL;
    if (!in_config_supported(config)) {
        /* AudioFlinger opens again with this configuration. */
        const uint32_t channels = audio_channel_count_from_in_mask(config->channel_mask);
        config->sample_rate = MIC_RATE;
        config->format = AUDIO_FORMAT_PCM_16_BIT;
        config->channel_mask = channels == 2 ? AUDIO_CHANNEL_IN_STEREO : AUDIO_CHANNEL_IN_MONO;
        return -EINVAL;
    }

    struct cpt_stream_in *in = calloc(1, sizeof(struct cpt_stream_in));
    if (in == NULL) {
        return -ENOMEM;
    }
    in->stream.common.get_sample_rate = in_get_sample_rate;
    in->stream.common.set_sample_rate = in_set_sample_rate;
    in->stream.common.get_buffer_size = in_get_buffer_size;
    in->stream.common.get_channels = in_get_channels;
    in->stream.common.get_format = in_get_format;
    in->stream.common.set_format = in_set_format;
    in->stream.common.standby = in_standby;
    in->stream.common.dump = in_dump;
    in->stream.common.set_parameters = in_set_parameters;
    in->stream.common.get_parameters = in_get_parameters;
    in->stream.common.add_audio_effect = in_add_audio_effect;
    in->stream.common.remove_audio_effect = in_remove_audio_effect;
    in->stream.set_gain = in_set_gain;
    in->stream.read = in_read;
    in->stream.get_input_frames_lost = in_get_input_frames_lost;
    in->dev = (struct cpt_audio_device *)dev;
    pthread_mutex_init(&in->lock, NULL);
    in->channel_mask = config->channel_mask;

    char value[PROPERTY_VALUE_MAX];
    property_get("persist.plaything.mic_gain_db", value, "24");
    double gain_db = atof(value);
    if (gain_db < 0) {
        gain_db = 0;
    } else if (gain_db > 48) {
        gain_db = 48;
    }
    in->gain_q12 = (int64_t)(pow(10.0, gain_db / 20.0) * 4096.0 + 0.5);
    property_get("persist.plaything.mic_mask", value, "0xf");
    in->mask = (unsigned int)strtoul(value, NULL, 0) & ((1u << MIC_CHANNELS) - 1);
    if (in->mask == 0) {
        in->mask = (1u << MIC_CHANNELS) - 1;
    }
    in->mask_count = __builtin_popcount(in->mask);
    in->dump_fd = -1;
    if (property_get("debug.plaything.mic_dump", value, "") > 0) {
        in->dump_fd = open(value, O_WRONLY | O_APPEND | O_CLOEXEC);
    }

    ALOGI("input stream: source %d, %u ch, gain %.1f dB, mask 0x%x, dump %s", source,
          audio_channel_count_from_in_mask(in->channel_mask), gain_db, in->mask,
          in->dump_fd >= 0 ? value : "off");
    *stream_in = &in->stream;
    return 0;
}

static void adev_close_input_stream(struct audio_hw_device *dev, struct audio_stream_in *stream)
{
    struct cpt_stream_in *in = (struct cpt_stream_in *)stream;
    pthread_mutex_lock(&in->lock);
    in_close_pcm(in);
    pthread_mutex_unlock(&in->lock);
    if (in->dump_fd >= 0) {
        close(in->dump_fd);
    }
    free(in->raw);
    pthread_mutex_destroy(&in->lock);
    free(in);
}

static int adev_dump(const audio_hw_device_t *device, int fd)
{
    return 0;
}

static int adev_close(hw_device_t *device)
{
    free(device);
    return 0;
}

static int adev_open(const hw_module_t *module, const char *name, hw_device_t **device)
{
    if (strcmp(name, AUDIO_HARDWARE_INTERFACE) != 0) {
        return -EINVAL;
    }
    struct cpt_audio_device *adev = calloc(1, sizeof(struct cpt_audio_device));
    if (adev == NULL) {
        return -ENOMEM;
    }
    pthread_mutex_init(&adev->lock, NULL);
    adev->device.common.tag = HARDWARE_DEVICE_TAG;
    adev->device.common.version = AUDIO_DEVICE_API_VERSION_2_0;
    adev->device.common.module = (struct hw_module_t *)module;
    adev->device.common.close = adev_close;
    adev->device.init_check = adev_init_check;
    adev->device.set_voice_volume = adev_set_voice_volume;
    adev->device.set_master_volume = adev_set_master_volume;
    adev->device.get_master_volume = adev_get_master_volume;
    adev->device.set_master_mute = adev_set_master_mute;
    adev->device.get_master_mute = adev_get_master_mute;
    adev->device.set_mode = adev_set_mode;
    adev->device.set_mic_mute = adev_set_mic_mute;
    adev->device.get_mic_mute = adev_get_mic_mute;
    adev->device.set_parameters = adev_set_parameters;
    adev->device.get_parameters = adev_get_parameters;
    adev->device.get_input_buffer_size = adev_get_input_buffer_size;
    adev->device.open_output_stream = adev_open_output_stream;
    adev->device.close_output_stream = adev_close_output_stream;
    adev->device.open_input_stream = adev_open_input_stream;
    adev->device.close_input_stream = adev_close_input_stream;
    adev->device.dump = adev_dump;
    *device = &adev->device.common;
    return 0;
}

static struct hw_module_methods_t hal_module_methods = {
    .open = adev_open,
};

struct audio_module HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
        .hal_api_version = HARDWARE_HAL_API_VERSION,
        .id = AUDIO_HARDWARE_MODULE_ID,
        .name = "PlayThing primary audio HAL",
        .author = "PlayThing",
        .methods = &hal_module_methods,
    },
};
