// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_H
#define CHIAKI_JNI_VIDEO_DECODER_H

#include <jni.h>
#include <stddef.h>

#include <chiaki/thread.h>
#include <chiaki/log.h>

typedef struct AMediaCodec AMediaCodec;
typedef struct ANativeWindow ANativeWindow;

#define ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY 4

typedef struct
{
	uint8_t *data;
	size_t size;
} AndroidChiakiVideoDecoderFrame;

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

	// Applies to every session type. When true, the output thread scales its presentation
	// buffer to recently-observed jitter (instead of a fixed baseline) and proactively drops
	// frames that arrive well ahead of schedule during a burst. See
	// android_chiaki_video_decoder_output_thread_func in video-decoder.c.
	bool adaptive_frame_pacing_enabled;

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
} AndroidChiakiVideoDecoder;

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, int32_t target_fps, ChiakiCodec codec, bool adaptive_frame_pacing_enabled);
void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder);
void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface);
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user);

#endif
