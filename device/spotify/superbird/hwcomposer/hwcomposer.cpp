/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
/*
 * PlayThing HWC2 module for the Car Thing panel (docs/graphics.md, "Open HWC").
 *
 * The OSD of the Amlogic display engine has no IOMMU and cannot rotate, so all layers use
 * client composition: SurfaceFlinger composes (and rotates) with the GPU into the client
 * target, and this module only shows the client target. Thus the module has no layer logic.
 *
 * Display: the frame buffer driver of the Spotify 4.9 kernel (drivers/amlogic/media/osd).
 * Its "sync render" ioctl takes the client target's dma-buf, waits for the acquire fence,
 * scans the buffer out directly (no copy), and gives a fence that signals after the next
 * vsync. That fence is the present fence. SurfaceFlinger uses it to release the previous
 * client target. The client target comes from the ION DMA heap (contiguous), because the
 * gralloc gives GRALLOC_USAGE_HW_FB buffers that heap.
 * Vsync: the driver's wait-for-vsync ioctl gives the CLOCK_MONOTONIC time of each vsync.
 * Panel size and frame rate: /sys/class/display/vinfo (the vout driver).
 *
 * The kernel interface below is a new description of the driver's ABI (osd.h,
 * osd_sync.h, osd_fb.h of that kernel).
 */
#define LOG_TAG "hwc_superbird"

#include <errno.h>
#include <fcntl.h>
#include <linux/fb.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <unistd.h>

#include <condition_variable>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <cutils/native_handle.h>
#include <hardware/gralloc1.h>
#include <hardware/hardware.h>
#include <hardware/hwcomposer2.h>
#include <log/log.h>
#include <system/graphics.h>

namespace {

// Amlogic OSD frame buffer ioctls.
constexpr unsigned long kOsdSyncRenderAdd = 0x4519;
constexpr unsigned long kOsdBlank = 0x451c;
constexpr unsigned long kOsdWaitVsync64 = _IOW('F', 0x21, __u32);  // writes an s64
constexpr int32_t kRenderMagicV1 = 0x55386816;  // single layer, no hardware composition
constexpr uint32_t kDirectCompose = 1;           // scan the buffer out directly

// The request of kOsdSyncRenderAdd (a union in the kernel; this member is the largest).
struct OsdRenderRequest {
    int32_t magic;
    int32_t len;
    uint32_t xoffset;
    uint32_t yoffset;
    int32_t inFenceFd;   // the kernel closes it
    int32_t outFenceFd;  // signals after the vsync that shows this buffer
    int32_t width;
    int32_t height;
    int32_t format;      // HAL pixel format
    int32_t sharedFd;    // dma-buf of the buffer (the kernel does not close it)
    uint32_t op;         // bit 0: disable the OSD layer
    uint32_t type;
    uint32_t dstX;
    uint32_t dstY;
    uint32_t dstW;
    uint32_t dstH;
    int32_t byteStride;
    int32_t pixelStride;
    int32_t afbcFormat;
    uint32_t zorder;
    uint32_t blendMode;
    uint8_t planeAlpha;
    uint8_t dimLayer;
    uint32_t dimColor;
    int32_t fbWidth;
    int32_t fbHeight;
    int32_t reserved;
};
static_assert(sizeof(OsdRenderRequest) == 104, "OSD render request ABI");

constexpr hwc2_display_t kPrimary = 0;
constexpr hwc2_config_t kConfig = 0;
constexpr const char* kFbPath = "/dev/graphics/fb0";
constexpr const char* kVinfoPath = "/sys/class/display/vinfo";

int bytesPerPixel(int32_t format) {
    switch (format) {
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
        case HAL_PIXEL_FORMAT_BGRA_8888:
            return 4;
        case HAL_PIXEL_FORMAT_RGB_888:
            return 3;
        case HAL_PIXEL_FORMAT_RGB_565:
            return 2;
        default:
            return 0;
    }
}

struct Hwc : hwc2_device_t {
    int fbFd = -1;
    int32_t width = 0;
    int32_t height = 0;
    int32_t vsyncPeriodNs = 16666667;
    int32_t dpiX = -1;  // dots per 1000 inches, -1 if unknown
    int32_t dpiY = -1;

    gralloc1_device_t* gralloc = nullptr;
    GRALLOC1_PFN_GET_DIMENSIONS getDimensions = nullptr;
    GRALLOC1_PFN_GET_FORMAT getFormat = nullptr;
    GRALLOC1_PFN_GET_STRIDE getStride = nullptr;

    // State of the display. The HAL serializes most calls, but callbacks and vsync come
    // from other threads.
    std::mutex lock;
    std::map<hwc2_layer_t, int32_t> layers;  // layer -> composition type
    hwc2_layer_t nextLayer = 1;
    std::vector<hwc2_layer_t> changed;  // layers that validate changed to CLIENT
    bool validated = false;
    buffer_handle_t clientTarget = nullptr;
    int clientAcquireFence = -1;
    int32_t powerMode = HWC2_POWER_MODE_ON;
    uint64_t frames = 0;
    uint64_t failures = 0;
    int lastError = 0;

    hwc2_callback_data_t hotplugData = nullptr;
    HWC2_PFN_HOTPLUG hotplug = nullptr;
    hwc2_callback_data_t refreshData = nullptr;
    HWC2_PFN_REFRESH refresh = nullptr;

    std::mutex vsyncLock;
    std::condition_variable vsyncCond;
    hwc2_callback_data_t vsyncData = nullptr;
    HWC2_PFN_VSYNC vsync = nullptr;
    bool vsyncEnabled = false;
    bool stopping = false;
    std::thread vsyncThread;
};

Hwc* toHwc(hwc2_device_t* device) {
    return static_cast<Hwc*>(device);
}

void closeFence(int fd) {
    if (fd >= 0) close(fd);
}

void vsyncLoop(Hwc* hwc) {
    // HAL_PRIORITY_URGENT_DISPLAY. The composer service has CAP_SYS_NICE.
    if (setpriority(PRIO_PROCESS, 0, -8) != 0) ALOGW("setpriority: %s", strerror(errno));
    int64_t last = 0;
    for (;;) {
        {
            std::unique_lock<std::mutex> l(hwc->vsyncLock);
            hwc->vsyncCond.wait(l, [hwc] { return hwc->vsyncEnabled || hwc->stopping; });
            if (hwc->stopping) return;
        }
        int64_t timestamp = 0;
        if (ioctl(hwc->fbFd, kOsdWaitVsync64, &timestamp) != 0) {
            if (errno != EINTR) {
                ALOGE("wait for vsync: %s", strerror(errno));
                usleep(hwc->vsyncPeriodNs / 1000);
            }
            continue;
        }
        // After 1 s without a vsync (panel off), the driver gives the old time again.
        if (timestamp == last) continue;
        last = timestamp;
        HWC2_PFN_VSYNC callback;
        hwc2_callback_data_t data;
        {
            std::lock_guard<std::mutex> l(hwc->vsyncLock);
            if (!hwc->vsyncEnabled) continue;
            callback = hwc->vsync;
            data = hwc->vsyncData;
        }
        if (callback) callback(data, kPrimary, timestamp);
    }
}

// Device functions.

void getCapabilities(hwc2_device_t*, uint32_t* outCount, int32_t*) {
    *outCount = 0;
}

int32_t createVirtualDisplay(hwc2_device_t*, uint32_t, uint32_t, int32_t*, hwc2_display_t*) {
    return HWC2_ERROR_NO_RESOURCES;
}

int32_t destroyVirtualDisplay(hwc2_device_t*, hwc2_display_t) {
    return HWC2_ERROR_BAD_DISPLAY;
}

uint32_t getMaxVirtualDisplayCount(hwc2_device_t*) {
    return 0;
}

void dump(hwc2_device_t* device, uint32_t* outSize, char* outBuffer) {
    Hwc* hwc = toHwc(device);
    char text[512];
    {
        std::lock_guard<std::mutex> l(hwc->lock);
        snprintf(text, sizeof(text),
                 "PlayThing HWC: %dx%d, vsync %d ns, dpi %d/%d (x1000), power %d, "
                 "layers %zu, frames %llu, failures %llu (last errno %d)\n",
                 hwc->width, hwc->height, hwc->vsyncPeriodNs, hwc->dpiX, hwc->dpiY,
                 hwc->powerMode, hwc->layers.size(), (unsigned long long)hwc->frames,
                 (unsigned long long)hwc->failures, hwc->lastError);
    }
    uint32_t len = strlen(text);
    if (!outBuffer) {
        *outSize = len;
        return;
    }
    if (len > *outSize) len = *outSize;
    memcpy(outBuffer, text, len);
    *outSize = len;
}

int32_t registerCallback(hwc2_device_t* device, int32_t descriptor,
                         hwc2_callback_data_t data, hwc2_function_pointer_t pointer) {
    Hwc* hwc = toHwc(device);
    switch (descriptor) {
        case HWC2_CALLBACK_HOTPLUG: {
            HWC2_PFN_HOTPLUG hotplug = reinterpret_cast<HWC2_PFN_HOTPLUG>(pointer);
            {
                std::lock_guard<std::mutex> l(hwc->lock);
                hwc->hotplug = hotplug;
                hwc->hotplugData = data;
            }
            // SurfaceFlinger needs the primary display before registerCallback returns.
            if (hotplug) hotplug(data, kPrimary, HWC2_CONNECTION_CONNECTED);
            return HWC2_ERROR_NONE;
        }
        case HWC2_CALLBACK_REFRESH: {
            std::lock_guard<std::mutex> l(hwc->lock);
            hwc->refresh = reinterpret_cast<HWC2_PFN_REFRESH>(pointer);
            hwc->refreshData = data;
            return HWC2_ERROR_NONE;
        }
        case HWC2_CALLBACK_VSYNC: {
            std::lock_guard<std::mutex> l(hwc->vsyncLock);
            hwc->vsync = reinterpret_cast<HWC2_PFN_VSYNC>(pointer);
            hwc->vsyncData = data;
            return HWC2_ERROR_NONE;
        }
        default:
            return HWC2_ERROR_BAD_PARAMETER;
    }
}

// Display functions. There is one display, kPrimary.

#define CHECK_DISPLAY(display) \
    do { if ((display) != kPrimary) return HWC2_ERROR_BAD_DISPLAY; } while (0)

int32_t acceptDisplayChanges(hwc2_device_t* device, hwc2_display_t display) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    std::lock_guard<std::mutex> l(hwc->lock);
    if (!hwc->validated) return HWC2_ERROR_NOT_VALIDATED;
    for (hwc2_layer_t layer : hwc->changed) {
        auto it = hwc->layers.find(layer);
        if (it != hwc->layers.end()) it->second = HWC2_COMPOSITION_CLIENT;
    }
    hwc->changed.clear();
    return HWC2_ERROR_NONE;
}

int32_t createLayer(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t* outLayer) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    std::lock_guard<std::mutex> l(hwc->lock);
    hwc2_layer_t layer = hwc->nextLayer++;
    hwc->layers[layer] = HWC2_COMPOSITION_INVALID;
    hwc->validated = false;
    *outLayer = layer;
    return HWC2_ERROR_NONE;
}

int32_t destroyLayer(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    std::lock_guard<std::mutex> l(hwc->lock);
    if (hwc->layers.erase(layer) == 0) return HWC2_ERROR_BAD_LAYER;
    hwc->validated = false;
    return HWC2_ERROR_NONE;
}

int32_t getActiveConfig(hwc2_device_t*, hwc2_display_t display, hwc2_config_t* outConfig) {
    CHECK_DISPLAY(display);
    *outConfig = kConfig;
    return HWC2_ERROR_NONE;
}

int32_t getChangedCompositionTypes(hwc2_device_t* device, hwc2_display_t display,
                                   uint32_t* outNumElements, hwc2_layer_t* outLayers,
                                   int32_t* outTypes) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    std::lock_guard<std::mutex> l(hwc->lock);
    if (!hwc->validated) return HWC2_ERROR_NOT_VALIDATED;
    uint32_t count = hwc->changed.size();
    if (outLayers && outTypes) {
        if (count > *outNumElements) count = *outNumElements;
        for (uint32_t i = 0; i < count; i++) {
            outLayers[i] = hwc->changed[i];
            outTypes[i] = HWC2_COMPOSITION_CLIENT;
        }
    }
    *outNumElements = count;
    return HWC2_ERROR_NONE;
}

int32_t getClientTargetSupport(hwc2_device_t* device, hwc2_display_t display, uint32_t width,
                               uint32_t height, int32_t format, int32_t) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    if ((int32_t)width != hwc->width || (int32_t)height != hwc->height) {
        return HWC2_ERROR_UNSUPPORTED;
    }
    return bytesPerPixel(format) == 4 ? HWC2_ERROR_NONE : HWC2_ERROR_UNSUPPORTED;
}

int32_t getColorModes(hwc2_device_t*, hwc2_display_t display, uint32_t* outNumModes,
                      int32_t* outModes) {
    CHECK_DISPLAY(display);
    if (outModes && *outNumModes > 0) outModes[0] = HAL_COLOR_MODE_NATIVE;
    *outNumModes = 1;
    return HWC2_ERROR_NONE;
}

int32_t getDisplayAttribute(hwc2_device_t* device, hwc2_display_t display,
                            hwc2_config_t config, int32_t attribute, int32_t* outValue) {
    CHECK_DISPLAY(display);
    if (config != kConfig) return HWC2_ERROR_BAD_CONFIG;
    Hwc* hwc = toHwc(device);
    switch (attribute) {
        case HWC2_ATTRIBUTE_WIDTH: *outValue = hwc->width; break;
        case HWC2_ATTRIBUTE_HEIGHT: *outValue = hwc->height; break;
        case HWC2_ATTRIBUTE_VSYNC_PERIOD: *outValue = hwc->vsyncPeriodNs; break;
        case HWC2_ATTRIBUTE_DPI_X: *outValue = hwc->dpiX; break;
        case HWC2_ATTRIBUTE_DPI_Y: *outValue = hwc->dpiY; break;
        default: *outValue = -1; break;
    }
    return HWC2_ERROR_NONE;
}

int32_t getDisplayConfigs(hwc2_device_t*, hwc2_display_t display, uint32_t* outNumConfigs,
                          hwc2_config_t* outConfigs) {
    CHECK_DISPLAY(display);
    if (outConfigs && *outNumConfigs > 0) outConfigs[0] = kConfig;
    *outNumConfigs = 1;
    return HWC2_ERROR_NONE;
}

int32_t getDisplayName(hwc2_device_t*, hwc2_display_t display, uint32_t* outSize,
                       char* outName) {
    CHECK_DISPLAY(display);
    static const char kName[] = "Car Thing panel";
    uint32_t len = sizeof(kName) - 1;
    if (outName) {
        if (len > *outSize) len = *outSize;
        memcpy(outName, kName, len);
    }
    *outSize = len;
    return HWC2_ERROR_NONE;
}

int32_t getDisplayRequests(hwc2_device_t* device, hwc2_display_t display,
                           int32_t* outDisplayRequests, uint32_t* outNumElements,
                           hwc2_layer_t*, int32_t*) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    std::lock_guard<std::mutex> l(hwc->lock);
    if (!hwc->validated) return HWC2_ERROR_NOT_VALIDATED;
    *outDisplayRequests = 0;
    *outNumElements = 0;
    return HWC2_ERROR_NONE;
}

int32_t getDisplayType(hwc2_device_t*, hwc2_display_t display, int32_t* outType) {
    CHECK_DISPLAY(display);
    *outType = HWC2_DISPLAY_TYPE_PHYSICAL;
    return HWC2_ERROR_NONE;
}

int32_t getDozeSupport(hwc2_device_t*, hwc2_display_t display, int32_t* outSupport) {
    CHECK_DISPLAY(display);
    *outSupport = 0;
    return HWC2_ERROR_NONE;
}

int32_t getHdrCapabilities(hwc2_device_t*, hwc2_display_t display, uint32_t* outNumTypes,
                           int32_t*, float*, float*, float*) {
    CHECK_DISPLAY(display);
    *outNumTypes = 0;
    return HWC2_ERROR_NONE;
}

int32_t getReleaseFences(hwc2_device_t*, hwc2_display_t display, uint32_t* outNumElements,
                         hwc2_layer_t*, int32_t*) {
    CHECK_DISPLAY(display);
    // No layer uses device composition, so no layer buffer has a release fence. The
    // previous client target is released with the present fence.
    *outNumElements = 0;
    return HWC2_ERROR_NONE;
}

int32_t presentDisplay(hwc2_device_t* device, hwc2_display_t display,
                       int32_t* outPresentFence) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    std::lock_guard<std::mutex> l(hwc->lock);
    *outPresentFence = -1;
    if (!hwc->validated) return HWC2_ERROR_NOT_VALIDATED;
    hwc->validated = false;

    int acquireFence = hwc->clientAcquireFence;
    hwc->clientAcquireFence = -1;
    buffer_handle_t target = hwc->clientTarget;
    if (!target || hwc->powerMode == HWC2_POWER_MODE_OFF) {
        closeFence(acquireFence);
        return HWC2_ERROR_NONE;
    }

    uint32_t width = 0, height = 0, stride = 0;
    int32_t format = 0;
    if (target->numFds < 1 ||
        hwc->getDimensions(hwc->gralloc, target, &width, &height) != GRALLOC1_ERROR_NONE ||
        hwc->getFormat(hwc->gralloc, target, &format) != GRALLOC1_ERROR_NONE ||
        hwc->getStride(hwc->gralloc, target, &stride) != GRALLOC1_ERROR_NONE ||
        bytesPerPixel(format) == 0) {
        ALOGE("client target: bad buffer (format %d)", format);
        closeFence(acquireFence);
        hwc->failures++;
        return HWC2_ERROR_NONE;
    }

    OsdRenderRequest request;
    memset(&request, 0, sizeof(request));
    request.magic = kRenderMagicV1;
    request.len = sizeof(request);
    // The kernel closes the fence fd number that it gets. Give it a copy, so that this
    // module never closes a number that the kernel closed.
    request.inFenceFd = acquireFence >= 0 ? dup(acquireFence) : -1;
    closeFence(acquireFence);
    request.outFenceFd = -1;
    request.width = width;
    request.height = height;
    request.format = format;
    request.sharedFd = target->data[0];  // the gralloc's dma-buf (first fd of its handle)
    request.op = 0;                      // OSD layer on
    request.type = kDirectCompose;
    request.dstW = width;
    request.dstH = height;
    request.byteStride = stride * bytesPerPixel(format);
    request.pixelStride = stride;
    request.planeAlpha = 0xff;
    request.fbWidth = hwc->width;
    request.fbHeight = hwc->height;
    if (ioctl(hwc->fbFd, kOsdSyncRenderAdd, &request) != 0 || request.outFenceFd < 0) {
        hwc->lastError = errno;
        if (hwc->failures++ % 600 == 0) {
            ALOGE("sync render: %s (fence %d)", strerror(errno), request.outFenceFd);
        }
        return HWC2_ERROR_NONE;
    }
    hwc->frames++;
    *outPresentFence = request.outFenceFd;
    return HWC2_ERROR_NONE;
}

int32_t setActiveConfig(hwc2_device_t*, hwc2_display_t display, hwc2_config_t config) {
    CHECK_DISPLAY(display);
    return config == kConfig ? HWC2_ERROR_NONE : HWC2_ERROR_BAD_CONFIG;
}

int32_t setClientTarget(hwc2_device_t* device, hwc2_display_t display, buffer_handle_t target,
                        int32_t acquireFence, int32_t, hwc_region_t) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    std::lock_guard<std::mutex> l(hwc->lock);
    closeFence(hwc->clientAcquireFence);
    hwc->clientTarget = target;
    hwc->clientAcquireFence = acquireFence;
    return HWC2_ERROR_NONE;
}

int32_t setColorMode(hwc2_device_t*, hwc2_display_t display, int32_t mode) {
    CHECK_DISPLAY(display);
    return mode == HAL_COLOR_MODE_NATIVE ? HWC2_ERROR_NONE : HWC2_ERROR_UNSUPPORTED;
}

int32_t setColorTransform(hwc2_device_t*, hwc2_display_t display, const float*, int32_t) {
    CHECK_DISPLAY(display);
    // All layers use client composition, so SurfaceFlinger applies the transform itself.
    return HWC2_ERROR_NONE;
}

int32_t setOutputBuffer(hwc2_device_t*, hwc2_display_t display, buffer_handle_t,
                        int32_t releaseFence) {
    CHECK_DISPLAY(display);
    closeFence(releaseFence);
    return HWC2_ERROR_UNSUPPORTED;  // only for virtual displays
}

int32_t setPowerMode(hwc2_device_t* device, hwc2_display_t display, int32_t mode) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    if (mode != HWC2_POWER_MODE_OFF && mode != HWC2_POWER_MODE_ON) {
        return mode == HWC2_POWER_MODE_DOZE || mode == HWC2_POWER_MODE_DOZE_SUSPEND
                       ? HWC2_ERROR_UNSUPPORTED
                       : HWC2_ERROR_BAD_PARAMETER;
    }
    std::lock_guard<std::mutex> l(hwc->lock);
    uint32_t blank = mode == HWC2_POWER_MODE_OFF ? 1 : 0;
    if (ioctl(hwc->fbFd, kOsdBlank, &blank) != 0) ALOGE("blank %u: %s", blank, strerror(errno));
    hwc->powerMode = mode;
    return HWC2_ERROR_NONE;
}

int32_t setVsyncEnabled(hwc2_device_t* device, hwc2_display_t display, int32_t enabled) {
    CHECK_DISPLAY(display);
    if (enabled != HWC2_VSYNC_ENABLE && enabled != HWC2_VSYNC_DISABLE) {
        return HWC2_ERROR_BAD_PARAMETER;
    }
    Hwc* hwc = toHwc(device);
    {
        std::lock_guard<std::mutex> l(hwc->vsyncLock);
        hwc->vsyncEnabled = enabled == HWC2_VSYNC_ENABLE;
    }
    hwc->vsyncCond.notify_all();
    return HWC2_ERROR_NONE;
}

int32_t validateDisplay(hwc2_device_t* device, hwc2_display_t display, uint32_t* outNumTypes,
                        uint32_t* outNumRequests) {
    CHECK_DISPLAY(display);
    Hwc* hwc = toHwc(device);
    std::lock_guard<std::mutex> l(hwc->lock);
    hwc->changed.clear();
    for (const auto& layer : hwc->layers) {
        if (layer.second != HWC2_COMPOSITION_CLIENT) hwc->changed.push_back(layer.first);
    }
    hwc->validated = true;
    *outNumTypes = hwc->changed.size();
    *outNumRequests = 0;
    return hwc->changed.empty() ? HWC2_ERROR_NONE : HWC2_ERROR_HAS_CHANGES;
}

// Layer functions. Only the composition type matters; the rest is accepted and ignored.

#define CHECK_LAYER(hwc, display, layer)                                           \
    do {                                                                            \
        if ((display) != kPrimary) return HWC2_ERROR_BAD_DISPLAY;                  \
        std::lock_guard<std::mutex> l((hwc)->lock);                                 \
        if ((hwc)->layers.find(layer) == (hwc)->layers.end()) return HWC2_ERROR_BAD_LAYER; \
    } while (0)

int32_t setCursorPosition(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer,
                          int32_t, int32_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerBuffer(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer,
                       buffer_handle_t, int32_t acquireFence) {
    closeFence(acquireFence);  // the GPU composes this layer; SurfaceFlinger waits for it
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerSurfaceDamage(hwc2_device_t* device, hwc2_display_t display,
                              hwc2_layer_t layer, hwc_region_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerBlendMode(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer,
                          int32_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerColor(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer,
                      hwc_color_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerCompositionType(hwc2_device_t* device, hwc2_display_t display,
                                hwc2_layer_t layer, int32_t type) {
    if (display != kPrimary) return HWC2_ERROR_BAD_DISPLAY;
    Hwc* hwc = toHwc(device);
    std::lock_guard<std::mutex> l(hwc->lock);
    auto it = hwc->layers.find(layer);
    if (it == hwc->layers.end()) return HWC2_ERROR_BAD_LAYER;
    if (it->second != type) {
        it->second = type;
        hwc->validated = false;
    }
    return HWC2_ERROR_NONE;
}

int32_t setLayerDataspace(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer,
                          int32_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerDisplayFrame(hwc2_device_t* device, hwc2_display_t display,
                             hwc2_layer_t layer, hwc_rect_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerPlaneAlpha(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer,
                           float) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerSidebandStream(hwc2_device_t* device, hwc2_display_t display,
                               hwc2_layer_t layer, const native_handle_t*) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerSourceCrop(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer,
                           hwc_frect_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerTransform(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer,
                          int32_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerVisibleRegion(hwc2_device_t* device, hwc2_display_t display,
                              hwc2_layer_t layer, hwc_region_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

int32_t setLayerZOrder(hwc2_device_t* device, hwc2_display_t display, hwc2_layer_t layer,
                       uint32_t) {
    CHECK_LAYER(toHwc(device), display, layer);
    return HWC2_ERROR_NONE;
}

template <typename T>
hwc2_function_pointer_t fn(T function) {
    return reinterpret_cast<hwc2_function_pointer_t>(function);
}

hwc2_function_pointer_t getFunction(hwc2_device_t*, int32_t descriptor) {
    switch (descriptor) {
        case HWC2_FUNCTION_ACCEPT_DISPLAY_CHANGES: return fn(acceptDisplayChanges);
        case HWC2_FUNCTION_CREATE_LAYER: return fn(createLayer);
        case HWC2_FUNCTION_CREATE_VIRTUAL_DISPLAY: return fn(createVirtualDisplay);
        case HWC2_FUNCTION_DESTROY_LAYER: return fn(destroyLayer);
        case HWC2_FUNCTION_DESTROY_VIRTUAL_DISPLAY: return fn(destroyVirtualDisplay);
        case HWC2_FUNCTION_DUMP: return fn(dump);
        case HWC2_FUNCTION_GET_ACTIVE_CONFIG: return fn(getActiveConfig);
        case HWC2_FUNCTION_GET_CHANGED_COMPOSITION_TYPES: return fn(getChangedCompositionTypes);
        case HWC2_FUNCTION_GET_CLIENT_TARGET_SUPPORT: return fn(getClientTargetSupport);
        case HWC2_FUNCTION_GET_COLOR_MODES: return fn(getColorModes);
        case HWC2_FUNCTION_GET_DISPLAY_ATTRIBUTE: return fn(getDisplayAttribute);
        case HWC2_FUNCTION_GET_DISPLAY_CONFIGS: return fn(getDisplayConfigs);
        case HWC2_FUNCTION_GET_DISPLAY_NAME: return fn(getDisplayName);
        case HWC2_FUNCTION_GET_DISPLAY_REQUESTS: return fn(getDisplayRequests);
        case HWC2_FUNCTION_GET_DISPLAY_TYPE: return fn(getDisplayType);
        case HWC2_FUNCTION_GET_DOZE_SUPPORT: return fn(getDozeSupport);
        case HWC2_FUNCTION_GET_HDR_CAPABILITIES: return fn(getHdrCapabilities);
        case HWC2_FUNCTION_GET_MAX_VIRTUAL_DISPLAY_COUNT: return fn(getMaxVirtualDisplayCount);
        case HWC2_FUNCTION_GET_RELEASE_FENCES: return fn(getReleaseFences);
        case HWC2_FUNCTION_PRESENT_DISPLAY: return fn(presentDisplay);
        case HWC2_FUNCTION_REGISTER_CALLBACK: return fn(registerCallback);
        case HWC2_FUNCTION_SET_ACTIVE_CONFIG: return fn(setActiveConfig);
        case HWC2_FUNCTION_SET_CLIENT_TARGET: return fn(setClientTarget);
        case HWC2_FUNCTION_SET_COLOR_MODE: return fn(setColorMode);
        case HWC2_FUNCTION_SET_COLOR_TRANSFORM: return fn(setColorTransform);
        case HWC2_FUNCTION_SET_CURSOR_POSITION: return fn(setCursorPosition);
        case HWC2_FUNCTION_SET_LAYER_BLEND_MODE: return fn(setLayerBlendMode);
        case HWC2_FUNCTION_SET_LAYER_BUFFER: return fn(setLayerBuffer);
        case HWC2_FUNCTION_SET_LAYER_COLOR: return fn(setLayerColor);
        case HWC2_FUNCTION_SET_LAYER_COMPOSITION_TYPE: return fn(setLayerCompositionType);
        case HWC2_FUNCTION_SET_LAYER_DATASPACE: return fn(setLayerDataspace);
        case HWC2_FUNCTION_SET_LAYER_DISPLAY_FRAME: return fn(setLayerDisplayFrame);
        case HWC2_FUNCTION_SET_LAYER_PLANE_ALPHA: return fn(setLayerPlaneAlpha);
        case HWC2_FUNCTION_SET_LAYER_SIDEBAND_STREAM: return fn(setLayerSidebandStream);
        case HWC2_FUNCTION_SET_LAYER_SOURCE_CROP: return fn(setLayerSourceCrop);
        case HWC2_FUNCTION_SET_LAYER_SURFACE_DAMAGE: return fn(setLayerSurfaceDamage);
        case HWC2_FUNCTION_SET_LAYER_TRANSFORM: return fn(setLayerTransform);
        case HWC2_FUNCTION_SET_LAYER_VISIBLE_REGION: return fn(setLayerVisibleRegion);
        case HWC2_FUNCTION_SET_LAYER_Z_ORDER: return fn(setLayerZOrder);
        case HWC2_FUNCTION_SET_OUTPUT_BUFFER: return fn(setOutputBuffer);
        case HWC2_FUNCTION_SET_POWER_MODE: return fn(setPowerMode);
        case HWC2_FUNCTION_SET_VSYNC_ENABLED: return fn(setVsyncEnabled);
        case HWC2_FUNCTION_VALIDATE_DISPLAY: return fn(validateDisplay);
        default: return nullptr;
    }
}

// Module.

void destroy(Hwc* hwc) {
    if (hwc->vsyncThread.joinable()) {
        {
            std::lock_guard<std::mutex> l(hwc->vsyncLock);
            hwc->stopping = true;
        }
        hwc->vsyncCond.notify_all();
        hwc->vsyncThread.join();
    }
    closeFence(hwc->clientAcquireFence);
    if (hwc->gralloc) gralloc1_close(hwc->gralloc);
    if (hwc->fbFd >= 0) close(hwc->fbFd);
    delete hwc;
}

int closeDevice(hw_device_t* device) {
    destroy(toHwc(reinterpret_cast<hwc2_device_t*>(device)));
    return 0;
}

bool openGralloc(Hwc* hwc) {
    const hw_module_t* module = nullptr;
    if (hw_get_module(GRALLOC_HARDWARE_MODULE_ID, &module) != 0 ||
        gralloc1_open(module, &hwc->gralloc) != 0) {
        ALOGE("cannot open the gralloc1 device");
        hwc->gralloc = nullptr;
        return false;
    }
    gralloc1_device_t* g = hwc->gralloc;
    hwc->getDimensions = reinterpret_cast<GRALLOC1_PFN_GET_DIMENSIONS>(
            g->getFunction(g, GRALLOC1_FUNCTION_GET_DIMENSIONS));
    hwc->getFormat = reinterpret_cast<GRALLOC1_PFN_GET_FORMAT>(
            g->getFunction(g, GRALLOC1_FUNCTION_GET_FORMAT));
    hwc->getStride = reinterpret_cast<GRALLOC1_PFN_GET_STRIDE>(
            g->getFunction(g, GRALLOC1_FUNCTION_GET_STRIDE));
    if (!hwc->getDimensions || !hwc->getFormat || !hwc->getStride) {
        ALOGE("the gralloc1 device has no dimension, format, or stride function");
        return false;
    }
    return true;
}

// Reads "key: value" lines of the vout information (/sys/class/display/vinfo).
std::map<std::string, long> readVinfo() {
    std::map<std::string, long> values;
    FILE* f = fopen(kVinfoPath, "re");
    if (!f) {
        ALOGW("open %s: %s", kVinfoPath, strerror(errno));
        return values;
    }
    char line[128];
    while (fgets(line, sizeof(line), f)) {
        char key[64];
        long value;
        if (sscanf(line, " %63[a-z_]: %ld", key, &value) == 2) values.emplace(key, value);
    }
    fclose(f);
    return values;
}

bool openDisplay(Hwc* hwc) {
    hwc->fbFd = open(kFbPath, O_RDWR | O_CLOEXEC);
    if (hwc->fbFd < 0) {
        ALOGE("open %s: %s", kFbPath, strerror(errno));
        return false;
    }
    fb_var_screeninfo var;
    if (ioctl(hwc->fbFd, FBIOGET_VSCREENINFO, &var) != 0) {
        ALOGE("FBIOGET_VSCREENINFO: %s", strerror(errno));
        return false;
    }
    hwc->width = var.xres;
    hwc->height = var.yres;
    // The frame buffer has no physical size (0 mm); the vout information has the panel's
    // (DT screen_width, screen_height: 52 x 86 mm) and its frame rate (num/den Hz).
    std::map<std::string, long> vinfo = readVinfo();
    long widthMm = vinfo["screen_real_width"];
    long heightMm = vinfo["screen_real_height"];
    if (widthMm > 0 && heightMm > 0) {
        hwc->dpiX = (int32_t)((int64_t)var.xres * 25400000 / widthMm);
        hwc->dpiY = (int32_t)((int64_t)var.yres * 25400000 / heightMm);
    }
    long num = vinfo["sync_duration_num"];
    long den = vinfo["sync_duration_den"];
    if (num > 0 && den > 0) {
        int64_t period = (int64_t)1000000000 * den / num;
        if (period >= 8000000 && period <= 50000000) hwc->vsyncPeriodNs = period;
    }
    ALOGI("panel %dx%d, %ldx%ld mm, vsync period %d ns", hwc->width, hwc->height, widthMm,
          heightMm, hwc->vsyncPeriodNs);
    return hwc->width > 0 && hwc->height > 0;
}

int openDevice(const hw_module_t* module, const char* name, hw_device_t** device) {
    if (strcmp(name, HWC_HARDWARE_COMPOSER) != 0) return -EINVAL;
    Hwc* hwc = new Hwc();
    hwc->common.tag = HARDWARE_DEVICE_TAG;
    hwc->common.version = HWC_DEVICE_API_VERSION_2_0;
    hwc->common.module = const_cast<hw_module_t*>(module);
    hwc->common.close = closeDevice;
    hwc->getCapabilities = getCapabilities;
    hwc->getFunction = getFunction;
    if (!openDisplay(hwc) || !openGralloc(hwc)) {
        destroy(hwc);
        return -ENODEV;
    }
    hwc->vsyncThread = std::thread(vsyncLoop, hwc);
    *device = &hwc->common;
    return 0;
}

hw_module_methods_t methods = {
    .open = openDevice,
};

}  // namespace

hw_module_t HAL_MODULE_INFO_SYM = {
    .tag = HARDWARE_MODULE_TAG,
    .module_api_version = HARDWARE_MODULE_API_VERSION(2, 0),
    .hal_api_version = HARDWARE_HAL_API_VERSION,
    .id = HWC_HARDWARE_MODULE_ID,
    .name = "Car Thing HWC2",
    .author = "PlayThing",
    .methods = &methods,
    .dso = nullptr,
    .reserved = {0},
};
