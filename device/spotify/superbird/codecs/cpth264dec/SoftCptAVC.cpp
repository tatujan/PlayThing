/*
 * Copyright 2015 The Android Open Source Project
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Follows the structure of frameworks/av/media/libstagefright/codecs/avcdec/SoftAVCDec.cpp
 * of AOSP; the decoding uses the Amlogic V4L2 hardware decoder.
 */
/*
 * H.264 decoder OMX component on the Amlogic hardware decoder (docs/video.md).
 * See SoftCptAVC.h. The V4L2 calls are the same as in scripts/device/cpt-vdec.c, which
 * measured p50 2.9 ms per frame at 60 fps with amvdec_mh264.fast_output_enable=8.
 *
 * Flow on the component thread (SimpleSoftOMXComponent):
 *   input buffer  -> copy into a V4L2 OUTPUT buffer (H.264), timestamp = OMX timestamp
 *   SOURCE_CHANGE -> set up the V4L2 CAPTURE queue (NV12), report the visible size
 *   CAPTURE done  -> copy NV12 into the OMX output buffer as I420, same timestamp
 * After a frame, the component waits up to kFrameWaitMs for its decoded picture, so that
 * the picture leaves at once (low latency) without a second thread.
 * Codec config (SPS/PPS) is kept and sent again after a flush, which resets the decoder.
 *
 * The decoder writes NV21 (V before U), although V4L2 reports NV12: with the NV12 order,
 * red and blue are swapped.
 *
 * Capture buffers: the driver's own buffers (MMAP) are uncached; reading a frame from them
 * takes about 5 ms (128 MB/s), and the component's copy about 10 ms per frame. So the
 * component allocates cached, contiguous memory from the ION heap codec_mm_ion (ID 5) and
 * gives it to the decoder as V4L2_MEMORY_USERPTR: videobuf2 invalidates the CPU cache at
 * QBUF and DQBUF, and the read takes about 0.5 ms (1.3 GB/s, same picture checksum as MMAP;
 * scripts/device/cpt-vdec.c). ION_FLAG_CACHED_NEEDS_SYNC is necessary: without it this ION
 * maps cached buffers lazily, and the USERPTR import fails. The buffers must be released
 * in this order: STREAMOFF, REQBUFS 0, then unmap and close; buffers still queued in the
 * decoder at process exit stay allocated in codec_mm until a reboot. If the ION allocation
 * fails, the component uses the MMAP buffers (another memory pool).
 *
 * Properties: persist.plaything.hw_avc=0 disables the component (the factory returns
 * NULL, so MediaCodec uses the next H.264 decoder); persist.plaything.vdec_swap_uv=0
 * uses the NV12 chroma order; persist.plaything.vdec_ion=0 uses the MMAP buffers.
 */
//#define LOG_NDEBUG 0
#define LOG_TAG "SoftCptAVC"
#include <utils/Log.h>

#include "SoftCptAVC.h"

#include <cutils/properties.h>
#include <errno.h>
#include <ion/ion.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/foundation/ADebug.h>
#include <OMX_VideoExt.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <utils/Timers.h>

namespace android {

static const char *kDevice = "/dev/video26";
// ION heap codec_mm_ion (drivers/amlogic/media/common/ion_dev/dev_ion.c: id
// ION_HEAP_TYPE_CUSTOM = 5): contiguous memory from codec_mm, which the decoder can use.
static const unsigned int kIonCodecMmHeapMask = 1u << 5;

// Hardware failures in this codec process (out of decoder memory, too few capture buffers,
// device errors). After kMaxHardwareFailures, the factory returns NULL, so MediaCodec uses
// OMX.google.h264.decoder until the codec service restarts. Without this, DiPlay recreates
// the failing decoder again and again, and the screen stays black.
static int gHardwareFailures = 0;
static const int kMaxHardwareFailures = 2;
static const unsigned int kIonFlagCached = 1;
static const unsigned int kIonFlagCachedNeedsSync = 2;

static const CodecProfileLevel kProfileLevels[] = {
    { OMX_VIDEO_AVCProfileConstrainedBaseline, OMX_VIDEO_AVCLevel51 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel51 },
    { OMX_VIDEO_AVCProfileMain, OMX_VIDEO_AVCLevel51 },
    { OMX_VIDEO_AVCProfileConstrainedHigh, OMX_VIDEO_AVCLevel51 },
    { OMX_VIDEO_AVCProfileHigh, OMX_VIDEO_AVCLevel51 },
};

SoftCptAVC::SoftCptAVC(
        const char *name, const OMX_CALLBACKTYPE *callbacks, OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SoftVideoDecoderOMXComponent(
            name, "video_decoder.avc", OMX_VIDEO_CodingAVC,
            kProfileLevels, ARRAY_SIZE(kProfileLevels),
            320 /* width */, 240 /* height */, callbacks, appData, component),
      mFd(-1),
      mSignalledError(false),
      mCaptureOn(false),
      mStopSent(false),
      mSwapUV(property_get_bool("persist.plaything.vdec_swap_uv", true)),
      mUseIon(false),
      mIonFd(-1),
      mNumCapture(0),
      mMinCapture(0),
      mCaptureStride(0),
      mCaptureHeight(0),
      mVisibleWidth(0),
      mVisibleHeight(0),
      mPendingCapture(-1),
      mPendingTimeUs(0),
      mPendingLast(false),
      mCodecConfigClosed(false) {
    memset(mInputMem, 0, sizeof(mInputMem));
    memset(mInputLength, 0, sizeof(mInputLength));
    memset(mInputFree, 0, sizeof(mInputFree));
    memset(mCapture, 0, sizeof(mCapture));
    for (int i = 0; i < kMaxCaptureBuffers; i++) {
        mCapture[i].dmaFd = -1;
    }
    initPorts(1 /* numMinInputBuffers */, kNumInputBuffers, kInputBufferSize,
              1 /* numMinOutputBuffers */, kNumOutputBuffers, MEDIA_MIMETYPE_VIDEO_AVC);
}

SoftCptAVC::~SoftCptAVC() {
    closeDevice();
}

// static
bool SoftCptAVC::deviceAvailable() {
    if (!property_get_bool("persist.plaything.hw_avc", true)) {
        return false;
    }
    if (gHardwareFailures >= kMaxHardwareFailures) {
        return false;
    }
    return access(kDevice, R_OK | W_OK) == 0;
}

void SoftCptAVC::signalError(const char *what) {
    gHardwareFailures++;
    ALOGE("%s: %s (hardware failure %d of %d)", what, strerror(errno), gHardwareFailures,
          kMaxHardwareFailures);
    notify(OMX_EventError, OMX_ErrorUndefined, 0, NULL);
    mSignalledError = true;
}

bool SoftCptAVC::openDevice() {
    mFd = open(kDevice, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (mFd < 0) {
        ALOGE("open %s: %s", kDevice, strerror(errno));
        return false;
    }

    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
    fmt.fmt.pix_mp.pixelformat = V4L2_PIX_FMT_H264;
    fmt.fmt.pix_mp.width = mWidth;
    fmt.fmt.pix_mp.height = mHeight;
    fmt.fmt.pix_mp.num_planes = 1;
    fmt.fmt.pix_mp.plane_fmt[0].sizeimage = kInputBufferSize;
    if (ioctl(mFd, VIDIOC_S_FMT, &fmt) < 0) {
        ALOGE("S_FMT output: %s", strerror(errno));
        closeDevice();
        return false;
    }

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
    req.memory = V4L2_MEMORY_MMAP;
    req.count = kV4l2InputBuffers;
    if (ioctl(mFd, VIDIOC_REQBUFS, &req) < 0 || req.count == 0) {
        ALOGE("REQBUFS output: %s", strerror(errno));
        closeDevice();
        return false;
    }
    for (uint32_t i = 0; i < req.count && i < kV4l2InputBuffers; i++) {
        struct v4l2_plane plane;
        struct v4l2_buffer buf;
        memset(&plane, 0, sizeof(plane));
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        buf.m.planes = &plane;
        buf.length = 1;
        if (ioctl(mFd, VIDIOC_QUERYBUF, &buf) < 0) {
            ALOGE("QUERYBUF output: %s", strerror(errno));
            closeDevice();
            return false;
        }
        mInputMem[i] = mmap(NULL, plane.length, PROT_READ | PROT_WRITE, MAP_SHARED, mFd,
                            plane.m.mem_offset);
        if (mInputMem[i] == MAP_FAILED) {
            mInputMem[i] = NULL;
            ALOGE("mmap output: %s", strerror(errno));
            closeDevice();
            return false;
        }
        mInputLength[i] = plane.length;
        mInputFree[i] = true;
    }

    struct v4l2_event_subscription sub;
    memset(&sub, 0, sizeof(sub));
    sub.type = V4L2_EVENT_SOURCE_CHANGE;
    int type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
    if (ioctl(mFd, VIDIOC_SUBSCRIBE_EVENT, &sub) < 0 || ioctl(mFd, VIDIOC_STREAMON, &type) < 0) {
        ALOGE("SUBSCRIBE_EVENT/STREAMON output: %s", strerror(errno));
        closeDevice();
        return false;
    }
    mCaptureOn = false;
    mStopSent = false;
    mPendingCapture = -1;
    ALOGI("opened %s for %ux%u", kDevice, mWidth, mHeight);
    return true;
}

void SoftCptAVC::releaseCapture() {
    // Order matters (see the top of this file): the decoder must give the buffers back
    // before their memory is unmapped and freed.
    if (mFd >= 0 && mCaptureOn) {
        int type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
        ioctl(mFd, VIDIOC_STREAMOFF, &type);
    }
    if (mFd >= 0 && mNumCapture > 0) {
        struct v4l2_requestbuffers req;
        memset(&req, 0, sizeof(req));
        req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
        req.memory = mUseIon ? V4L2_MEMORY_USERPTR : V4L2_MEMORY_MMAP;
        req.count = 0;
        ioctl(mFd, VIDIOC_REQBUFS, &req);
    }
    for (int i = 0; i < kMaxCaptureBuffers; i++) {
        CaptureBuffer &c = mCapture[i];
        if (c.base != NULL) {
            munmap(c.base, c.baseLength);
        } else {
            for (int p = 0; p < 2; p++) {
                if (c.plane[p] != NULL) {
                    munmap(c.plane[p], c.length[p]);
                }
            }
        }
        if (c.dmaFd >= 0) {
            close(c.dmaFd);
        }
        memset(&c, 0, sizeof(c));
        c.dmaFd = -1;
    }
    mNumCapture = 0;
    mCaptureOn = false;
    mPendingCapture = -1;
}

void SoftCptAVC::closeDevice() {
    if (mFd < 0) {
        return;
    }
    releaseCapture();
    int type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
    ioctl(mFd, VIDIOC_STREAMOFF, &type);
    for (int i = 0; i < kV4l2InputBuffers; i++) {
        if (mInputMem[i] != NULL) {
            munmap(mInputMem[i], mInputLength[i]);
        }
        mInputMem[i] = NULL;
        mInputLength[i] = 0;
        mInputFree[i] = false;
    }
    close(mFd);
    mFd = -1;
    mStopSent = false;
    if (mIonFd >= 0) {
        ion_close(mIonFd);
        mIonFd = -1;
    }
}

bool SoftCptAVC::queueCaptureBuffer(int index) {
    struct v4l2_plane planes[VIDEO_MAX_PLANES];
    struct v4l2_buffer buf;
    memset(planes, 0, sizeof(planes));
    memset(&buf, 0, sizeof(buf));
    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
    buf.index = index;
    buf.m.planes = planes;
    if (mUseIon) {
        buf.memory = V4L2_MEMORY_USERPTR;
        buf.length = 2;
        for (int p = 0; p < 2; p++) {
            planes[p].m.userptr = (unsigned long)mCapture[index].plane[p];
            planes[p].length = mCapture[index].length[p];
        }
    } else {
        buf.memory = V4L2_MEMORY_MMAP;
        buf.length = VIDEO_MAX_PLANES;
    }
    return ioctl(mFd, VIDIOC_QBUF, &buf) == 0;
}

// Cached ION buffers as USERPTR. Returns false (and frees all) if anything fails.
bool SoftCptAVC::setupIonCapture(const struct v4l2_format &fmt, int count) {
    const size_t size0 = fmt.fmt.pix_mp.plane_fmt[0].sizeimage;
    const size_t size1 = fmt.fmt.pix_mp.plane_fmt[1].sizeimage;
    const size_t size0Aligned = (size0 + 4095) & ~(size_t)4095;
    if (fmt.fmt.pix_mp.num_planes != 2 || size0 == 0 || size1 == 0) {
        return false;
    }
    if (mIonFd < 0) {
        mIonFd = ion_open();
        if (mIonFd < 0) {
            ALOGW("ion_open failed; using MMAP capture buffers");
            return false;
        }
    }
    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
    req.memory = V4L2_MEMORY_USERPTR;
    req.count = count;
    if (ioctl(mFd, VIDIOC_REQBUFS, &req) < 0 || req.count == 0) {
        ALOGW("REQBUFS USERPTR: %s; using MMAP capture buffers", strerror(errno));
        return false;
    }
    mNumCapture = req.count < (uint32_t)kMaxCaptureBuffers ? req.count : kMaxCaptureBuffers;
    mUseIon = true;  // for queueCaptureBuffer() and releaseCapture()
    for (int i = 0; i < mNumCapture; i++) {
        CaptureBuffer &c = mCapture[i];
        int dmaFd = -1;
        size_t length = size0Aligned + size1;
        if (ion_alloc_fd(mIonFd, length, 4096, kIonCodecMmHeapMask,
                         kIonFlagCached | kIonFlagCachedNeedsSync, &dmaFd) < 0) {
            ALOGW("ION allocation of capture buffer %d failed; using MMAP capture buffers", i);
            releaseCapture();
            mUseIon = false;
            return false;
        }
        void *base = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, dmaFd, 0);
        if (base == MAP_FAILED) {
            close(dmaFd);
            ALOGW("mmap of ION buffer %d failed; using MMAP capture buffers", i);
            releaseCapture();
            mUseIon = false;
            return false;
        }
        c.dmaFd = dmaFd;
        c.base = base;
        c.baseLength = length;
        c.plane[0] = base;
        c.length[0] = size0;
        c.plane[1] = static_cast<uint8_t *>(base) + size0Aligned;
        c.length[1] = size1;
        if (!queueCaptureBuffer(i)) {
            ALOGW("QBUF USERPTR %d: %s; using MMAP capture buffers", i, strerror(errno));
            releaseCapture();
            mUseIon = false;
            return false;
        }
    }
    return true;
}

bool SoftCptAVC::setupMmapCapture(int count) {
    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
    req.memory = V4L2_MEMORY_MMAP;
    req.count = count;
    if (ioctl(mFd, VIDIOC_REQBUFS, &req) < 0 || req.count == 0) {
        signalError("REQBUFS capture");
        return false;
    }
    mNumCapture = req.count < (uint32_t)kMaxCaptureBuffers ? req.count : kMaxCaptureBuffers;
    for (int i = 0; i < mNumCapture; i++) {
        struct v4l2_plane planes[VIDEO_MAX_PLANES];
        struct v4l2_buffer buf;
        memset(planes, 0, sizeof(planes));
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        buf.m.planes = planes;
        buf.length = VIDEO_MAX_PLANES;
        if (ioctl(mFd, VIDIOC_QUERYBUF, &buf) < 0) {
            signalError("QUERYBUF capture");
            return false;
        }
        for (uint32_t p = 0; p < buf.length && p < 2; p++) {
            void *mem = mmap(NULL, planes[p].length, PROT_READ, MAP_SHARED, mFd,
                             planes[p].m.mem_offset);
            if (mem == MAP_FAILED) {
                signalError("mmap capture");
                return false;
            }
            mCapture[i].plane[p] = mem;
            mCapture[i].length[p] = planes[p].length;
        }
        if (!queueCaptureBuffer(i)) {
            signalError("QBUF capture");
            return false;
        }
    }
    return true;
}

bool SoftCptAVC::setupCapture() {
    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
    if (ioctl(mFd, VIDIOC_G_FMT, &fmt) < 0) {
        signalError("G_FMT capture");
        return false;
    }
    mCaptureStride = fmt.fmt.pix_mp.plane_fmt[0].bytesperline;
    if (mCaptureStride == 0) {
        mCaptureStride = fmt.fmt.pix_mp.width;
    }
    mCaptureHeight = fmt.fmt.pix_mp.height;

    struct v4l2_selection sel;
    memset(&sel, 0, sizeof(sel));
    sel.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    sel.target = V4L2_SEL_TGT_COMPOSE_DEFAULT;
    if (ioctl(mFd, VIDIOC_G_SELECTION, &sel) == 0 && sel.r.width > 0 && sel.r.height > 0) {
        mVisibleWidth = sel.r.width;
        mVisibleHeight = sel.r.height;
    } else {
        mVisibleWidth = fmt.fmt.pix_mp.width;
        mVisibleHeight = fmt.fmt.pix_mp.height;
    }

    struct v4l2_control ctrl;
    memset(&ctrl, 0, sizeof(ctrl));
    ctrl.id = V4L2_CID_MIN_BUFFERS_FOR_CAPTURE;
    mMinCapture = ioctl(mFd, VIDIOC_G_CTRL, &ctrl) == 0 ? ctrl.value : 6;
    int count = mMinCapture + 2;
    if (count > kMaxCaptureBuffers) {
        count = kMaxCaptureBuffers;
    }

    mUseIon = false;
    if (property_get_bool("persist.plaything.vdec_ion", true) && setupIonCapture(fmt, count)) {
        mUseIon = true;
    } else if (!setupMmapCapture(count)) {
        return false;
    }
    if (mNumCapture < mMinCapture) {
        // The decoder does not start with fewer buffers ("the decoder has not ready").
        errno = ENOMEM;
        signalError("too few capture buffers");
        return false;
    }
    int type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
    if (ioctl(mFd, VIDIOC_STREAMON, &type) < 0) {
        signalError("STREAMON capture");
        return false;
    }
    mCaptureOn = true;
    ALOGI("capture %ux%u (stride %u), visible %ux%u, %d %s buffers", fmt.fmt.pix_mp.width,
          mCaptureHeight, mCaptureStride, mVisibleWidth, mVisibleHeight, mNumCapture,
          mUseIon ? "cached ION (USERPTR)" : "uncached MMAP");
    return true;
}

void SoftCptAVC::reclaimInputBuffers() {
    for (;;) {
        struct v4l2_plane plane;
        struct v4l2_buffer buf;
        memset(&plane, 0, sizeof(plane));
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.m.planes = &plane;
        buf.length = 1;
        if (ioctl(mFd, VIDIOC_DQBUF, &buf) < 0) {
            return;
        }
        if (buf.index < (uint32_t)kV4l2InputBuffers) {
            mInputFree[buf.index] = true;
        }
    }
}

int SoftCptAVC::takeInputBuffer(int waitMs) {
    for (int attempt = 0; attempt < 2; attempt++) {
        reclaimInputBuffers();
        for (int i = 0; i < kV4l2InputBuffers; i++) {
            if (mInputFree[i] && mInputMem[i] != NULL) {
                return i;
            }
        }
        if (attempt == 0 && waitMs > 0) {
            struct pollfd p = { mFd, POLLOUT, 0 };
            poll(&p, 1, waitMs);
        }
    }
    return -1;
}

bool SoftCptAVC::queueInput(const uint8_t *data, size_t size, int64_t timeUs) {
    int index = takeInputBuffer(20);
    if (index < 0) {
        return false;
    }
    if (size > mInputLength[index]) {
        ALOGE("input of %zu bytes is larger than the decoder buffer (%zu)", size,
              mInputLength[index]);
        errno = EINVAL;
        signalError("queueInput");
        return false;
    }
    memcpy(mInputMem[index], data, size);

    struct v4l2_plane plane;
    struct v4l2_buffer buf;
    memset(&plane, 0, sizeof(plane));
    memset(&buf, 0, sizeof(buf));
    buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
    buf.memory = V4L2_MEMORY_MMAP;
    buf.index = index;
    buf.m.planes = &plane;
    buf.length = 1;
    plane.bytesused = size;
    if (timeUs < 0) {
        timeUs = 0;
    }
    buf.timestamp.tv_sec = timeUs / 1000000;
    buf.timestamp.tv_usec = timeUs % 1000000;
    if (ioctl(mFd, VIDIOC_QBUF, &buf) < 0) {
        signalError("QBUF output");
        return false;
    }
    mInputFree[index] = false;
    return true;
}

void SoftCptAVC::sendCodecConfig() {
    if (!mCodecConfig.empty()) {
        queueInput(mCodecConfig.data(), mCodecConfig.size(), 0);
    }
}

// Returns false when the output port must reconfigure first.
bool SoftCptAVC::handleEvent() {
    struct v4l2_event ev;
    memset(&ev, 0, sizeof(ev));
    if (ioctl(mFd, VIDIOC_DQEVENT, &ev) < 0 || ev.type != V4L2_EVENT_SOURCE_CHANGE) {
        return true;
    }
    if (mCaptureOn) {
        releaseCapture();
    }
    if (!setupCapture()) {
        return false;
    }
    bool portWillReset = false;
    handlePortSettingsChange(&portWillReset, mVisibleWidth, mVisibleHeight);
    return !portWillReset;
}

void SoftCptAVC::copyFrame(int index, uint8_t *dst) {
    const uint8_t *srcY = static_cast<const uint8_t *>(mCapture[index].plane[0]);
    const uint8_t *srcUV = mCapture[index].plane[1] != NULL
            ? static_cast<const uint8_t *>(mCapture[index].plane[1])
            : srcY + (size_t)mCaptureStride * mCaptureHeight;
    const uint32_t dstStride = outputBufferWidth();
    const uint32_t dstHeight = outputBufferHeight();
    uint32_t width = mWidth < mCaptureStride ? mWidth : mCaptureStride;
    uint32_t height = mHeight < mCaptureHeight ? mHeight : mCaptureHeight;
    if (width > dstStride) {
        width = dstStride;
    }
    if (height > dstHeight) {
        height = dstHeight;
    }

    uint8_t *dstY = dst;
    uint8_t *dstU = dst + (size_t)dstStride * dstHeight;
    uint8_t *dstV = dstU + (size_t)(dstStride / 2) * (dstHeight / 2);
    if (mSwapUV) {
        uint8_t *t = dstU;
        dstU = dstV;
        dstV = t;
    }
    for (uint32_t y = 0; y < height; y++) {
        memcpy(dstY + (size_t)y * dstStride, srcY + (size_t)y * mCaptureStride, width);
    }
    if (mRow.size() < width) {
        mRow.resize(width);
    }
    for (uint32_t y = 0; y < height / 2; y++) {
        memcpy(mRow.data(), srcUV + (size_t)y * mCaptureStride, width);
        const uint8_t *s = mRow.data();
        uint8_t *u = dstU + (size_t)y * (dstStride / 2);
        uint8_t *v = dstV + (size_t)y * (dstStride / 2);
        for (uint32_t x = 0; x < width / 2; x++) {
            u[x] = s[2 * x];
            v[x] = s[2 * x + 1];
        }
    }
}

// Moves one decoded frame (or the end of stream) to an OMX output buffer.
bool SoftCptAVC::deliverFrame() {
    if (mFd < 0 || !mCaptureOn) {
        return false;
    }
    List<BufferInfo *> &outQueue = getPortQueue(kOutputPortIndex);
    size_t bytesUsed = 1;
    if (mPendingCapture < 0) {
        struct v4l2_plane planes[VIDEO_MAX_PLANES];
        struct v4l2_buffer buf;
        memset(planes, 0, sizeof(planes));
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
        buf.memory = mUseIon ? V4L2_MEMORY_USERPTR : V4L2_MEMORY_MMAP;
        buf.m.planes = planes;
        buf.length = VIDEO_MAX_PLANES;
        if (ioctl(mFd, VIDIOC_DQBUF, &buf) < 0) {
            return false;
        }
        bytesUsed = planes[0].bytesused;
        mPendingLast = (buf.flags & V4L2_BUF_FLAG_LAST) != 0;
        if (bytesUsed == 0 && !mPendingLast) {
            queueCaptureBuffer(buf.index);
            return true;
        }
        mPendingCapture = buf.index;
        mPendingTimeUs = buf.timestamp.tv_sec * 1000000LL + buf.timestamp.tv_usec;
        if (bytesUsed == 0) {
            mPendingTimeUs = -1;  // only the end of stream, no picture
        }
    }
    if (outQueue.empty()) {
        return false;
    }

    BufferInfo *outInfo = *outQueue.begin();
    OMX_BUFFERHEADERTYPE *outHeader = outInfo->mHeader;
    const size_t frameSize = (size_t)outputBufferWidth() * outputBufferHeight() * 3 / 2;
    outHeader->nOffset = 0;
    outHeader->nFlags = 0;
    outHeader->nFilledLen = 0;
    if (mPendingTimeUs >= 0 && outHeader->nAllocLen >= frameSize) {
        copyFrame(mPendingCapture, outHeader->pBuffer);
        outHeader->nFilledLen = frameSize;
        outHeader->nTimeStamp = mPendingTimeUs;
    }
    if (mPendingLast) {
        outHeader->nFlags |= OMX_BUFFERFLAG_EOS;
    }
    outQueue.erase(outQueue.begin());
    outInfo->mOwnedByUs = false;
    notifyFillBufferDone(outHeader);

    if (!mPendingLast) {
        queueCaptureBuffer(mPendingCapture);
    }
    mPendingCapture = -1;
    mPendingLast = false;
    return true;
}

void SoftCptAVC::drain(int waitMs) {
    const nsecs_t deadline = systemTime() + ms2ns(waitMs);
    for (;;) {
        if (mFd < 0 || mSignalledError || mOutputPortSettingsChange != NONE) {
            return;
        }
        if (mPendingCapture >= 0 && getPortQueue(kOutputPortIndex).empty()) {
            // A frame waits for an output buffer; the client's next buffer calls this component again.
            return;
        }
        nsecs_t left = deadline - systemTime();
        int timeout = left > 0 ? (int)((left + 999999) / 1000000) : 0;
        struct pollfd p = { mFd, POLLIN | POLLPRI, 0 };
        int r = poll(&p, 1, timeout);
        if (r < 0 && errno != EINTR) {
            signalError("poll");
            return;
        }
        if (r > 0 && (p.revents & POLLPRI)) {
            if (!handleEvent()) {
                return;
            }
        }
        reclaimInputBuffers();
        int delivered = 0;
        while (deliverFrame()) {
            delivered++;
        }
        if (delivered > 0 || timeout == 0 || r == 0) {
            return;
        }
        if (!(p.revents & (POLLIN | POLLPRI))) {
            // POLLERR before the capture queue runs: do not spin.
            usleep(1000);
        }
    }
}

void SoftCptAVC::onQueueFilled(OMX_U32 /* portIndex */) {
    if (mSignalledError || mOutputPortSettingsChange != NONE) {
        return;
    }
    if (mFd < 0) {
        if (!openDevice()) {
            errno = ENODEV;
            signalError("openDevice");
            return;
        }
        sendCodecConfig();
    }

    List<BufferInfo *> &inQueue = getPortQueue(kInputPortIndex);
    List<BufferInfo *> &outQueue = getPortQueue(kOutputPortIndex);
    drain(0);

    while (!inQueue.empty() && !mSignalledError && mOutputPortSettingsChange == NONE) {
        BufferInfo *inInfo = *inQueue.begin();
        OMX_BUFFERHEADERTYPE *inHeader = inInfo->mHeader;
        const bool config = (inHeader->nFlags & OMX_BUFFERFLAG_CODECCONFIG) != 0;
        const bool eos = (inHeader->nFlags & OMX_BUFFERFLAG_EOS) != 0;
        const uint8_t *data = inHeader->pBuffer + inHeader->nOffset;
        const size_t size = inHeader->nFilledLen;

        if (size > 0) {
            if (!queueInput(data, size, inHeader->nTimeStamp)) {
                // No free decoder input buffer now; the next call tries again.
                return;
            }
            if (config) {
                if (mCodecConfigClosed) {
                    mCodecConfig.clear();
                    mCodecConfigClosed = false;
                }
                mCodecConfig.insert(mCodecConfig.end(), data, data + size);
            } else {
                mCodecConfigClosed = true;
            }
        }
        inQueue.erase(inQueue.begin());
        inInfo->mOwnedByUs = false;
        notifyEmptyBufferDone(inHeader);

        if (eos && !mStopSent) {
            if (mCaptureOn) {
                struct v4l2_decoder_cmd cmd;
                memset(&cmd, 0, sizeof(cmd));
                cmd.cmd = V4L2_DEC_CMD_STOP;
                ioctl(mFd, VIDIOC_DECODER_CMD, &cmd);
            }
            mStopSent = true;
        }
        drain((size > 0 && !config) ? kFrameWaitMs : 0);
    }

    if (mStopSent) {
        if (mCaptureOn) {
            drain(kFrameWaitMs);
        } else if (!outQueue.empty()) {
            // End of stream before any picture: no LAST buffer comes from the decoder.
            BufferInfo *outInfo = *outQueue.begin();
            OMX_BUFFERHEADERTYPE *outHeader = outInfo->mHeader;
            outHeader->nOffset = 0;
            outHeader->nFilledLen = 0;
            outHeader->nFlags = OMX_BUFFERFLAG_EOS;
            outQueue.erase(outQueue.begin());
            outInfo->mOwnedByUs = false;
            notifyFillBufferDone(outHeader);
            mStopSent = false;
        }
    }
}

void SoftCptAVC::onPortFlushCompleted(OMX_U32 /* portIndex */) {
    // A flush drops all frames in the decoder. The simplest complete reset is a new
    // decoder instance; the next onQueueFilled() opens it and sends the codec config again.
    closeDevice();
}

void SoftCptAVC::onReset() {
    SoftVideoDecoderOMXComponent::onReset();
    closeDevice();
    mSignalledError = false;
    mCodecConfig.clear();
    mCodecConfigClosed = false;
}

}  // namespace android

android::SoftOMXComponent *createSoftOMXComponent(
        const char *name, const OMX_CALLBACKTYPE *callbacks, OMX_PTR appData,
        OMX_COMPONENTTYPE **component) {
    if (!android::SoftCptAVC::deviceAvailable()) {
        // MediaCodec then uses the next H.264 decoder (OMX.google.h264.decoder).
        ALOGW("hardware H.264 decoder not available");
        return NULL;
    }
    return new android::SoftCptAVC(name, callbacks, appData, component);
}
