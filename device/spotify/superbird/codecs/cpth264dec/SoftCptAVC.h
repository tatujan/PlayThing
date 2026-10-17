/*
 * Copyright 2015 The Android Open Source Project
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Follows the structure of frameworks/av/media/libstagefright/codecs/avcdec/SoftAVCDec.h of AOSP.
 */
/*
 * H.264 decoder OMX component on the Amlogic hardware decoder (docs/video.md).
 *
 * The decoding runs in the S905D2 video decoder block through the V4L2 memory-to-memory
 * device of media_modules (amvdec_ports, /dev/video26). The component copies each decoded
 * NV12 frame into the OMX output buffer as I420, the same output as OMX.google.h264.decoder.
 * Its name starts with "OMX.google." so that ACodec and MediaCodec keep the software
 * renderer path that DiPlay uses today; only the decode moves to the hardware.
 */
#ifndef SOFT_CPT_AVC_H_
#define SOFT_CPT_AVC_H_

#include <media/stagefright/omx/SoftVideoDecoderOMXComponent.h>
#include <vector>

struct v4l2_format;

namespace android {

struct SoftCptAVC : public SoftVideoDecoderOMXComponent {
    SoftCptAVC(const char *name, const OMX_CALLBACKTYPE *callbacks, OMX_PTR appData,
               OMX_COMPONENTTYPE **component);

    static bool deviceAvailable();

protected:
    virtual ~SoftCptAVC();

    virtual void onQueueFilled(OMX_U32 portIndex);
    virtual void onPortFlushCompleted(OMX_U32 portIndex);
    virtual void onReset();

private:
    enum {
        kNumInputBuffers = 8,
        kNumOutputBuffers = 8,
        kInputBufferSize = 1 << 20,
        kV4l2InputBuffers = 8,
        kMaxCaptureBuffers = 32,
        // Upper limit for the wait after a frame; the hardware returns a frame in about 3 ms
        // (fast output), so a normal frame does not wait this long.
        kFrameWaitMs = 40,
    };

    struct CaptureBuffer {
        void *plane[2];
        size_t length[2];
        // ION buffer (USERPTR mode): dma-buf fd and the cached mapping of both planes.
        int dmaFd;
        void *base;
        size_t baseLength;
    };

    int mFd;
    bool mSignalledError;
    bool mCaptureOn;
    bool mStopSent;
    bool mSwapUV;
    // Capture buffers: cached ION memory given as USERPTR (fast CPU reads), or the driver's
    // uncached MMAP buffers as the fallback.
    bool mUseIon;
    int mIonFd;
    void *mInputMem[kV4l2InputBuffers];
    size_t mInputLength[kV4l2InputBuffers];
    bool mInputFree[kV4l2InputBuffers];
    CaptureBuffer mCapture[kMaxCaptureBuffers];
    int mNumCapture;
    int mMinCapture;
    uint32_t mCaptureStride;
    uint32_t mCaptureHeight;
    uint32_t mVisibleWidth;
    uint32_t mVisibleHeight;
    // A decoded frame that waits for a free OMX output buffer (capture buffer index), or -1.
    int mPendingCapture;
    int64_t mPendingTimeUs;
    bool mPendingLast;
    // Codec config (SPS/PPS) of the stream, sent again after a decoder reset.
    std::vector<uint8_t> mCodecConfig;
    bool mCodecConfigClosed;
    // Cached copy of one chroma row (the capture buffers are uncached).
    std::vector<uint8_t> mRow;

    bool openDevice();
    void closeDevice();
    bool setupCapture();
    bool setupIonCapture(const struct v4l2_format &fmt, int count);
    bool setupMmapCapture(int count);
    void releaseCapture();
    bool queueCaptureBuffer(int index);
    int takeInputBuffer(int waitMs);
    bool queueInput(const uint8_t *data, size_t size, int64_t timeUs);
    void sendCodecConfig();
    void reclaimInputBuffers();
    bool handleEvent();
    void drain(int waitMs);
    bool deliverFrame();
    void copyFrame(int index, uint8_t *dst);
    void signalError(const char *what);

    DISALLOW_EVIL_CONSTRUCTORS(SoftCptAVC);
};

}  // namespace android

#endif  // SOFT_CPT_AVC_H_
