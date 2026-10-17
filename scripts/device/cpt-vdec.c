/*
 * Copyright (C) 2026 The PlayThing Authors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
/*
 * Decode an H.264 Annex-B file with the Amlogic V4L2 decoder (media_modules amvdec_ports,
 * /dev/video26) and measure it (docs/video.md). No Android and no display: the decoded
 * NV12 frames stay in the capture buffers.
 *
 * For each frame: the time from queueing its access unit to dequeueing the decoded frame
 * (matched by the buffer timestamp, which the driver copies). At the end: frames, frame
 * rate, latency p50/p90/max, and the CPU load of the whole system (/proc/stat).
 *
 * Build: static aarch64 (scripts/device-tools.sh).
 * Usage: cpt-vdec FILE.h264 [FPS] [DEVICE] [copy]
 *   FPS 0 (default): queue input as fast as the decoder takes it (throughput).
 *   FPS > 0: queue one access unit every 1/FPS s, like a live stream (latency).
 *   copy: also memcpy each decoded frame (both planes) out of the capture buffers and
 *   report the copy time (the OMX component must do this copy).
 *   ion: like copy, but the capture buffers are cached memory from the ION heap
 *   codec_mm_ion (ID 5, contiguous), given to the decoder as V4L2_MEMORY_USERPTR. For
 *   USERPTR buffers, videobuf2 invalidates the CPU cache at QBUF and DQBUF.
 * Both copy modes print a checksum of the Y plane of frame 300, to compare the data.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

/* Legacy ION API of the 4.9 kernel (drivers/staging/android/uapi/ion.h). */
struct ion_allocation_data {
	size_t len;
	size_t align;
	unsigned int heap_id_mask;
	unsigned int flags;
	int handle;
};
struct ion_fd_data {
	int handle;
	int fd;
};
#define ION_IOC_ALLOC	_IOWR('I', 0, struct ion_allocation_data)
#define ION_IOC_SHARE	_IOWR('I', 4, struct ion_fd_data)
struct ion_handle_data {
	int handle;
};
#define ION_IOC_FREE	_IOWR('I', 1, struct ion_handle_data)
#define ION_HEAP_CODEC_MM_MASK	(1u << 5)
#define ION_FLAG_CACHED	1
/* Without NEEDS_SYNC, 4.9 ION maps cached buffers lazily (page faults); then
 * get_vaddr_frames() finds no page table entries and the USERPTR import fails. */
#define ION_FLAG_CACHED_NEEDS_SYNC	2

#define OUT_BUFS	8
#define OUT_SIZE	(1 << 20)
#define MAX_CAP		32
#define MAX_AU		100000

struct au { const uint8_t *data; size_t len; };

static int fd;
static int do_copy;
static int do_ion;
static int ion_fd = -1;
static int ion_handle[MAX_CAP];
static int ion_share[MAX_CAP];
static size_t ion_len[MAX_CAP];
static int n_cap;
static unsigned long y_sum;
static void *cap_mem[MAX_CAP][2];
static size_t cap_len[MAX_CAP][2];
static uint8_t *copy_buf;
static double copy_total;
static int copies;
static void *out_mem[OUT_BUFS];
static double queued_at[MAX_AU];
static double latency[MAX_AU];

static double now(void)
{
	struct timespec ts;

	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec + ts.tv_nsec / 1e9;
}

static void die(const char *what)
{
	fprintf(stderr, "%s: %s\n", what, strerror(errno));
	exit(1);
}

static void xioctl(unsigned long req, void *arg, const char *name)
{
	if (ioctl(fd, req, arg) < 0)
		die(name);
}

/* Split Annex-B data into access units: a new picture starts at an SPS/PPS/SEI/AUD after
 * a slice, or at a slice with first_mb_in_slice 0 (first bit of the slice header 1). */
static int split_au(const uint8_t *d, size_t n, struct au *aus)
{
	size_t i = 0, au_start = 0;
	int count = 0, have_vcl = 0;

	while (i + 3 < n) {
		if (!(d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1)) {
			i++;
			continue;
		}
		size_t sc = (i > 0 && d[i - 1] == 0) ? i - 1 : i;
		uint8_t type = d[i + 3] & 0x1f;
		int vcl = type == 1 || type == 5;
		int first_slice = vcl && i + 4 < n && (d[i + 4] & 0x80);
		int starts_new = have_vcl && ((!vcl && (type == 6 || type == 7 || type == 8 || type == 9)) || first_slice);

		if (starts_new) {
			if (count == MAX_AU)
				break;
			aus[count].data = d + au_start;
			aus[count].len = sc - au_start;
			count++;
			au_start = sc;
			have_vcl = 0;
		}
		if (vcl)
			have_vcl = 1;
		i += 3;
	}
	if (have_vcl && count < MAX_AU) {
		aus[count].data = d + au_start;
		aus[count].len = n - au_start;
		count++;
	}
	return count;
}

static void cpu_ticks(unsigned long long *busy, unsigned long long *total)
{
	unsigned long long v[10] = {0};
	FILE *f = fopen("/proc/stat", "r");

	if (!f || fscanf(f, "cpu %llu %llu %llu %llu %llu %llu %llu %llu",
			 &v[0], &v[1], &v[2], &v[3], &v[4], &v[5], &v[6], &v[7]) != 8)
		die("/proc/stat");
	fclose(f);
	*total = v[0] + v[1] + v[2] + v[3] + v[4] + v[5] + v[6] + v[7];
	*busy = *total - v[3] - v[4];
}

static int cmp_double(const void *a, const void *b)
{
	double x = *(const double *)a, y = *(const double *)b;

	return x < y ? -1 : x > y;
}

static void queue_au(int index, const struct au *a, int au_index)
{
	struct v4l2_plane plane = {0};
	struct v4l2_buffer buf = {0};

	if (a->len > OUT_SIZE) {
		fprintf(stderr, "access unit %d too large: %zu\n", au_index, a->len);
		exit(1);
	}
	memcpy(out_mem[index], a->data, a->len);
	buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
	buf.memory = V4L2_MEMORY_MMAP;
	buf.index = index;
	buf.m.planes = &plane;
	buf.length = 1;
	plane.bytesused = a->len;
	buf.timestamp.tv_sec = au_index;
	queued_at[au_index] = now();
	xioctl(VIDIOC_QBUF, &buf, "QBUF output");
}

static int setup_capture(void)
{
	struct v4l2_format fmt = {0};
	struct v4l2_requestbuffers req = {0};
	struct v4l2_control ctrl = {0};
	int i, count;

	fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
	xioctl(VIDIOC_G_FMT, &fmt, "G_FMT capture");
	ctrl.id = V4L2_CID_MIN_BUFFERS_FOR_CAPTURE;
	count = ioctl(fd, VIDIOC_G_CTRL, &ctrl) == 0 ? ctrl.value + 2 : 8;
	if (count > MAX_CAP)
		count = MAX_CAP;
	printf("capture: %ux%u, %d planes, %d buffers\n", fmt.fmt.pix_mp.width,
	       fmt.fmt.pix_mp.height, fmt.fmt.pix_mp.num_planes, count);
	req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
	req.memory = do_ion ? V4L2_MEMORY_USERPTR : V4L2_MEMORY_MMAP;
	req.count = count;
	xioctl(VIDIOC_REQBUFS, &req, "REQBUFS capture");
	if (do_ion) {
		size_t s0 = fmt.fmt.pix_mp.plane_fmt[0].sizeimage;
		size_t s1 = fmt.fmt.pix_mp.plane_fmt[1].sizeimage;
		size_t s0a = (s0 + 4095) & ~(size_t)4095;

		ion_fd = open("/dev/ion", O_RDONLY | O_CLOEXEC);
		if (ion_fd < 0)
			die("/dev/ion");
		for (i = 0; i < (int)req.count; i++) {
			struct ion_allocation_data alloc = {0};
			struct ion_fd_data share = {0};
			struct v4l2_plane planes[VIDEO_MAX_PLANES] = {{0}};
			struct v4l2_buffer buf = {0};
			uint8_t *base;

			alloc.len = s0a + s1;
			alloc.align = 4096;
			alloc.heap_id_mask = ION_HEAP_CODEC_MM_MASK;
			alloc.flags = ION_FLAG_CACHED | ION_FLAG_CACHED_NEEDS_SYNC;
			if (ioctl(ion_fd, ION_IOC_ALLOC, &alloc) < 0)
				die("ION_IOC_ALLOC");
			share.handle = alloc.handle;
			if (ioctl(ion_fd, ION_IOC_SHARE, &share) < 0)
				die("ION_IOC_SHARE");
			ion_handle[i] = alloc.handle;
			ion_share[i] = share.fd;
			ion_len[i] = alloc.len;
			n_cap = i + 1;
			base = mmap(NULL, alloc.len, PROT_READ | PROT_WRITE, MAP_SHARED, share.fd, 0);
			if (base == MAP_FAILED)
				die("mmap ion");
			cap_mem[i][0] = base;
			cap_mem[i][1] = base + s0a;
			cap_len[i][0] = s0;
			cap_len[i][1] = s1;
			buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
			buf.memory = V4L2_MEMORY_USERPTR;
			buf.index = i;
			buf.m.planes = planes;
			buf.length = 2;
			planes[0].m.userptr = (unsigned long)base;
			planes[0].length = s0;
			planes[1].m.userptr = (unsigned long)(base + s0a);
			planes[1].length = s1;
			xioctl(VIDIOC_QBUF, &buf, "QBUF capture userptr");
		}
		copy_buf = malloc(s0 + s1);
		int type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
		xioctl(VIDIOC_STREAMON, &type, "STREAMON capture");
		printf("capture: ION codec_mm_ion, cached, USERPTR, %u buffers of %zu bytes\n",
		       req.count, s0a + s1);
		return req.count;
	}
	for (i = 0; i < (int)req.count; i++) {
		struct v4l2_plane planes[VIDEO_MAX_PLANES] = {{0}};
		struct v4l2_buffer buf = {0};

		buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
		buf.memory = V4L2_MEMORY_MMAP;
		buf.index = i;
		buf.m.planes = planes;
		buf.length = VIDEO_MAX_PLANES;
		if (do_copy) {
			xioctl(VIDIOC_QUERYBUF, &buf, "QUERYBUF capture");
			for (unsigned p = 0; p < buf.length && p < 2; p++) {
				cap_mem[i][p] = mmap(NULL, planes[p].length, PROT_READ, MAP_SHARED, fd,
						     planes[p].m.mem_offset);
				if (cap_mem[i][p] == MAP_FAILED)
					die("mmap capture");
				cap_len[i][p] = planes[p].length;
			}
			memset(planes, 0, sizeof(planes));
			buf.length = VIDEO_MAX_PLANES;
		}
		xioctl(VIDIOC_QBUF, &buf, "QBUF capture");
	}
	if (do_copy)
		copy_buf = malloc(cap_len[0][0] + cap_len[0][1]);
	int type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
	xioctl(VIDIOC_STREAMON, &type, "STREAMON capture");
	return req.count;
}

int main(int argc, char **argv)
{
	const char *dev = argc > 3 ? argv[3] : "/dev/video26";

	do_copy = argc > 4 && (strcmp(argv[4], "copy") == 0 || strcmp(argv[4], "ion") == 0);
	do_ion = argc > 4 && strcmp(argv[4], "ion") == 0;
	double fps = argc > 2 ? atof(argv[2]) : 0;
	struct au *aus = calloc(MAX_AU, sizeof(*aus));
	unsigned long long busy0, total0, busy1, total1;
	int n_au, next_au = 0, decoded = 0, capture_on = 0, free_out[OUT_BUFS], n_free = 0;
	int i, type, stop_sent = 0, done = 0;
	double t_start, t_end;
	struct stat_s { size_t size; } st;
	uint8_t *data;
	FILE *f;

	if (argc < 2) {
		fprintf(stderr, "usage: %s FILE.h264 [FPS] [DEVICE]\n", argv[0]);
		return 2;
	}
	f = fopen(argv[1], "rb");
	if (!f)
		die(argv[1]);
	fseek(f, 0, SEEK_END);
	st.size = ftell(f);
	fseek(f, 0, SEEK_SET);
	data = malloc(st.size);
	if (fread(data, 1, st.size, f) != st.size)
		die("read");
	fclose(f);
	n_au = split_au(data, st.size, aus);
	printf("%s: %zu bytes, %d access units\n", argv[1], st.size, n_au);

	fd = open(dev, O_RDWR | O_NONBLOCK);
	if (fd < 0)
		die(dev);
	struct v4l2_capability cap = {0};
	xioctl(VIDIOC_QUERYCAP, &cap, "QUERYCAP");
	printf("device: %s (%s)\n", cap.card, cap.driver);

	struct v4l2_format fmt = {0};
	fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
	fmt.fmt.pix_mp.pixelformat = V4L2_PIX_FMT_H264;
	fmt.fmt.pix_mp.width = 800;
	fmt.fmt.pix_mp.height = 480;
	fmt.fmt.pix_mp.num_planes = 1;
	fmt.fmt.pix_mp.plane_fmt[0].sizeimage = OUT_SIZE;
	xioctl(VIDIOC_S_FMT, &fmt, "S_FMT output");

	struct v4l2_requestbuffers req = {0};
	req.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
	req.memory = V4L2_MEMORY_MMAP;
	req.count = OUT_BUFS;
	xioctl(VIDIOC_REQBUFS, &req, "REQBUFS output");
	for (i = 0; i < (int)req.count && i < OUT_BUFS; i++) {
		struct v4l2_plane plane = {0};
		struct v4l2_buffer buf = {0};

		buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
		buf.memory = V4L2_MEMORY_MMAP;
		buf.index = i;
		buf.m.planes = &plane;
		buf.length = 1;
		xioctl(VIDIOC_QUERYBUF, &buf, "QUERYBUF output");
		out_mem[i] = mmap(NULL, plane.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
				  plane.m.mem_offset);
		if (out_mem[i] == MAP_FAILED)
			die("mmap output");
		free_out[n_free++] = i;
	}

	struct v4l2_event_subscription sub = {0};
	sub.type = V4L2_EVENT_SOURCE_CHANGE;
	xioctl(VIDIOC_SUBSCRIBE_EVENT, &sub, "SUBSCRIBE_EVENT");
	type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
	xioctl(VIDIOC_STREAMON, &type, "STREAMON output");

	cpu_ticks(&busy0, &total0);
	t_start = now();
	while (!done) {
		/* Queue input: every free buffer, or paced at FPS. */
		while (n_free > 0 && next_au < n_au &&
		       (fps <= 0 || now() >= t_start + next_au / fps)) {
			queue_au(free_out[--n_free], &aus[next_au], next_au);
			next_au++;
		}
		if (next_au == n_au && !stop_sent && capture_on) {
			struct v4l2_decoder_cmd cmd = {0};

			cmd.cmd = V4L2_DEC_CMD_STOP;
			ioctl(fd, VIDIOC_DECODER_CMD, &cmd);
			stop_sent = 1;
		}

		struct pollfd p = { .fd = fd, .events = POLLIN | POLLOUT | POLLPRI };
		int timeout = fps > 0 ? 2 : 50;
		int r = poll(&p, 1, timeout);

		if (r < 0)
			die("poll");
		if (p.revents & POLLPRI) {
			struct v4l2_event ev = {0};

			if (ioctl(fd, VIDIOC_DQEVENT, &ev) == 0 && ev.type == V4L2_EVENT_SOURCE_CHANGE &&
			    !capture_on) {
				setup_capture();
				capture_on = 1;
			}
		}
		for (;;) {
			struct v4l2_plane plane = {0};
			struct v4l2_buffer buf = {0};

			buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
			buf.memory = V4L2_MEMORY_MMAP;
			buf.m.planes = &plane;
			buf.length = 1;
			if (ioctl(fd, VIDIOC_DQBUF, &buf) < 0)
				break;
			free_out[n_free++] = buf.index;
		}
		while (capture_on) {
			struct v4l2_plane planes[VIDEO_MAX_PLANES] = {{0}};
			struct v4l2_buffer buf = {0};

			buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
			buf.memory = do_ion ? V4L2_MEMORY_USERPTR : V4L2_MEMORY_MMAP;
			buf.m.planes = planes;
			buf.length = VIDEO_MAX_PLANES;
			if (ioctl(fd, VIDIOC_DQBUF, &buf) < 0)
				break;
			long idx = buf.timestamp.tv_sec;
			if (planes[0].bytesused > 0 && idx >= 0 && idx < n_au && decoded < MAX_AU)
				latency[decoded++] = now() - queued_at[idx];
			if (do_copy && planes[0].bytesused > 0) {
				double t0 = now();

				memcpy(copy_buf, cap_mem[buf.index][0], cap_len[buf.index][0]);
				if (cap_mem[buf.index][1])
					memcpy(copy_buf + cap_len[buf.index][0], cap_mem[buf.index][1],
					       cap_len[buf.index][1]);
				copy_total += now() - t0;
				copies++;
				if (idx == 300) {
					/* Visible 800 x 480 only: the decoder does not write the padding. */
					for (size_t row = 0; row < 480; row++)
						for (size_t col = 0; col < 800; col++)
							y_sum += copy_buf[row * 832 + col];
				}
			}
			if (buf.flags & V4L2_BUF_FLAG_LAST) {
				done = 1;
				break;
			}
			xioctl(VIDIOC_QBUF, &buf, "QBUF capture again");
		}
		if (now() - t_start > 120) {
			fprintf(stderr, "timeout after 120 s\n");
			done = 1;
		}
		if (stop_sent && decoded >= n_au)
			done = 1;
	}
	t_end = now();
	cpu_ticks(&busy1, &total1);

	double wall = t_end - t_start;
	printf("decoded %d of %d frames in %.2f s: %.1f fps\n", decoded, n_au, wall,
	       decoded / wall);
	if (decoded > 0) {
		qsort(latency, decoded, sizeof(double), cmp_double);
		printf("latency (queue to decoded frame): p50 %.1f ms, p90 %.1f ms, max %.1f ms\n",
		       latency[decoded / 2] * 1e3, latency[decoded * 9 / 10] * 1e3,
		       latency[decoded - 1] * 1e3);
	}
	if (copies > 0)
		printf("Y checksum of frame 300 (visible 800 x 480): %lu\n", y_sum);
	if (copies > 0)
		printf("copy out of the capture buffers: %.2f ms per frame, %.0f MB/s\n",
		       copy_total / copies * 1e3,
		       (cap_len[0][0] + cap_len[0][1]) / (copy_total / copies) / 1e6);
	if (do_ion) {
		/* Clean teardown: the decoder gives the buffers back, then they are freed. */
		int t = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
		struct v4l2_requestbuffers rq = {0};

		ioctl(fd, VIDIOC_STREAMOFF, &t);
		t = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE;
		ioctl(fd, VIDIOC_STREAMOFF, &t);
		rq.type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
		rq.memory = V4L2_MEMORY_USERPTR;
		rq.count = 0;
		if (ioctl(fd, VIDIOC_REQBUFS, &rq) < 0)
			perror("REQBUFS 0 capture");
		close(fd);
		for (i = 0; i < n_cap; i++) {
			struct ion_handle_data h = { ion_handle[i] };

			munmap(cap_mem[i][0], ion_len[i]);
			close(ion_share[i]);
			if (ioctl(ion_fd, ION_IOC_FREE, &h) < 0)
				perror("ION_IOC_FREE");
		}
		close(ion_fd);
		printf("ION buffers released\n");
	}
	printf("system CPU busy: %.1f%% of 4 cores\n",
	       100.0 * (busy1 - busy0) / (double)(total1 - total0));
	return 0;
}
