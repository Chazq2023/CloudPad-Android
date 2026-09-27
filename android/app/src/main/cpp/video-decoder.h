// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_H
#define CHIAKI_JNI_VIDEO_DECODER_H

#include <jni.h>
#include <stddef.h>

#include <chiaki/thread.h>
#include <chiaki/log.h>
#include <chiaki/videoreceiver.h>

typedef struct AMediaCodec AMediaCodec;
typedef struct ANativeWindow ANativeWindow;

#define ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY 4

typedef struct
{
	uint8_t *data;
	size_t size;
	ChiakiVideoFrameTiming timing; // network-side stall tracing, timing.flush_us == 0 if none
	int64_t enqueue_us;
} AndroidChiakiVideoDecoderFrame;

// Stall tracing: one entry per frame submitted to MediaCodec, keyed by the presentation time it
// was queued with (which MediaCodec hands back on output and in the frame-rendered callback), so
// every stage of a frame's trip — network, each app thread, decoder, display — can be lined up.
#define ANDROID_CHIAKI_VIDEO_TRACE_SIZE 64

typedef struct
{
	bool used;
	int64_t pts_us;
	ChiakiVideoFrameTiming net;
	int64_t enqueue_us; // handed to the decoder queue (stream thread)
	int64_t in_pop_us; // taken off the queue by the input thread
	int64_t queued_us; // accepted by MediaCodec (after waiting for an input buffer)
	int64_t out_us; // decoded frame came out of MediaCodec
	int64_t render_target_ns; // time the frame was scheduled to be shown (0 = not rendered)
} AndroidChiakiVideoFrameTrace;

typedef struct android_chiaki_video_decoder_t
{
	ChiakiLog *log;
	ChiakiMutex codec_mutex;
	AMediaCodec *codec;
	ANativeWindow *window;
	ChiakiThread output_thread;
	bool shutdown_output;
	int32_t target_width;
	int32_t target_height;
	int32_t target_fps;
	ChiakiCodec target_codec;
	volatile uint64_t output_frames_total;
	int64_t next_render_ns;
	volatile int32_t input_timeouts;

	// Video Pacing mode; applies to every session type and can change mid-stream (see
	// android_chiaki_video_decoder_set_smooth_pacing). True = Smooth: the output thread scales its
	// presentation buffer to recently-observed jitter (up to a capped ceiling, instead of a fixed
	// baseline) and proactively drops frames that arrive well ahead of schedule during a burst.
	// False = Standard: fixed baseline. See android_chiaki_video_decoder_output_thread_func in
	// video-decoder.c. (Name kept from when this was the Adaptive Frame Pacing toggle.)
	volatile bool adaptive_frame_pacing_enabled;

	// Producer-consumer frame queue: stream thread enqueues, input thread submits to codec
	AndroidChiakiVideoDecoderFrame frame_queue[ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY];
	size_t frame_queue_head;
	size_t frame_queue_tail;
	size_t frame_queue_count;
	ChiakiMutex frame_queue_mutex;
	ChiakiCond frame_queue_cond;
	bool frame_queue_shutdown;
	ChiakiThread input_thread;
	bool input_thread_running;

	// Stall tracing (see AndroidChiakiVideoFrameTrace). trace_mutex guards everything below; the
	// rendered_* fields are fed by MediaCodec's frame-rendered callback (Android 13+), i.e. what
	// actually reached the screen rather than what was scheduled.
	ChiakiMutex trace_mutex;
	AndroidChiakiVideoFrameTrace trace[ANDROID_CHIAKI_VIDEO_TRACE_SIZE];
	size_t trace_next;
	int32_t queue_evictions;
	bool render_callback_enabled;
	int64_t rendered_last_ns;
	int32_t rendered_count;
	int64_t rendered_max_gap_ns;
	int32_t display_gap_logs;
	int64_t display_gap_log_window_ns;
} AndroidChiakiVideoDecoder;

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, int32_t target_fps, ChiakiCodec codec, bool adaptive_frame_pacing_enabled);
void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder);
void android_chiaki_video_decoder_set_smooth_pacing(AndroidChiakiVideoDecoder *decoder, bool smooth);
void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface);
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user);
/** As android_chiaki_video_decoder_video_sample, with the frame's network-side stall-trace timing
 *  (may be NULL). */
bool android_chiaki_video_decoder_video_sample_timed(uint8_t *buf, size_t buf_size, const ChiakiVideoFrameTiming *timing, AndroidChiakiVideoDecoder *decoder);

#endif
