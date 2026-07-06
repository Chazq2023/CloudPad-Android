// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder.h"

#include <jni.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>
#include <android/api-level.h>

#include <string.h>
#include <stdlib.h>
#include <sys/resource.h>

#include <time.h>
#include <inttypes.h>
#include <limits.h>

static int64_t now_us()
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ((int64_t)ts.tv_sec * 1000000) + (ts.tv_nsec / 1000);
}

static void *android_chiaki_video_decoder_output_thread_func(void *user);
static void *android_chiaki_video_decoder_input_thread_func(void *user);

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, int32_t target_fps, ChiakiCodec codec)
{
	decoder->log = log;
	decoder->codec = NULL;
	decoder->target_width = target_width;
	decoder->target_height = target_height;
	decoder->target_fps = target_fps;
	decoder->target_codec = codec;
	decoder->shutdown_output = false;
	decoder->output_frames_total = 0;
	decoder->next_render_ns = 0;
	decoder->input_timeouts = 0;

	decoder->frame_queue_head = 0;
	decoder->frame_queue_tail = 0;
	decoder->frame_queue_count = 0;
	decoder->frame_queue_shutdown = true;
	decoder->input_thread_running = false;

	ChiakiErrorCode err = chiaki_mutex_init(&decoder->codec_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;

	err = chiaki_mutex_init(&decoder->frame_queue_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_mutex_fini(&decoder->codec_mutex);
		return err;
	}

	err = chiaki_cond_init(&decoder->frame_queue_cond);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_mutex_fini(&decoder->frame_queue_mutex);
		chiaki_mutex_fini(&decoder->codec_mutex);
		return err;
	}

	return CHIAKI_ERR_SUCCESS;
}

static void stop_input_thread(AndroidChiakiVideoDecoder *decoder)
{
	chiaki_mutex_lock(&decoder->frame_queue_mutex);
	decoder->frame_queue_shutdown = true;
	chiaki_cond_signal(&decoder->frame_queue_cond);
	chiaki_mutex_unlock(&decoder->frame_queue_mutex);
	chiaki_thread_join(&decoder->input_thread, NULL);
	decoder->input_thread_running = false;
}

static void kill_decoder(AndroidChiakiVideoDecoder *decoder)
{
	chiaki_mutex_lock(&decoder->codec_mutex);
	decoder->shutdown_output = true;
	ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, 1000);
	if(codec_buf_index >= 0)
	{
		CHIAKI_LOGI(decoder->log, "Video Decoder sending EOS buffer");
		AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0, now_us(), AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
		chiaki_thread_join(&decoder->output_thread, NULL);
	}
	else
	{
		CHIAKI_LOGE(decoder->log, "Failed to get input buffer for shutting down Video Decoder!");
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
	}
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;
	decoder->shutdown_output = false;
}

void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder)
{
	if(decoder->input_thread_running)
		stop_input_thread(decoder);
	if(decoder->codec)
		kill_decoder(decoder);
	chiaki_cond_fini(&decoder->frame_queue_cond);
	chiaki_mutex_fini(&decoder->frame_queue_mutex);
	chiaki_mutex_fini(&decoder->codec_mutex);
}

void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface)
{
	if(!surface)
	{
		if(decoder->input_thread_running)
			stop_input_thread(decoder);
		chiaki_mutex_lock(&decoder->codec_mutex);
		if(decoder->codec)
		{
			chiaki_mutex_unlock(&decoder->codec_mutex);
			kill_decoder(decoder);
			CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
		}
		else
		{
			chiaki_mutex_unlock(&decoder->codec_mutex);
		}
		return;
	}

	chiaki_mutex_lock(&decoder->codec_mutex);

	if(decoder->codec)
	{
#if __ANDROID_API__ >= 23
		CHIAKI_LOGI(decoder->log, "Video decoder already initialized, swapping surface");
		ANativeWindow *new_window = ANativeWindow_fromSurface(env, surface);
		AMediaCodec_setOutputSurface(decoder->codec, new_window);
		ANativeWindow_release(decoder->window);
		decoder->window = new_window;
#else
		CHIAKI_LOGE(decoder->log, "Video Decoder already initialized");
#endif
		goto beach;
	}

	decoder->window = ANativeWindow_fromSurface(env, surface);

#if __ANDROID_API__ >= 30
	if(android_get_device_api_level() >= 30)
	{
		// ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE (1) = fixed-rate game/stream content
		ANativeWindow_setFrameRate(decoder->window, (float)decoder->target_fps, 1);
		CHIAKI_LOGI(decoder->log, "Set ANativeWindow frame rate to %d fps", decoder->target_fps);
	}
#endif

	const char *mime = chiaki_codec_is_h265(decoder->target_codec) ? "video/hevc" : "video/avc";
	CHIAKI_LOGI(decoder->log, "Initializing decoder with mime %s", mime);

	decoder->codec = AMediaCodec_createDecoderByType(mime);
	if(!decoder->codec)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create AMediaCodec for mime type %s", mime);
		goto error_surface;
	}

	AMediaFormat *format = AMediaFormat_new();
	AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, decoder->target_width);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, decoder->target_height);
	// Realtime decoding hints. low-latency is intentionally omitted — on many devices
	// it causes the hardware decoder to discard frames under load rather than buffer them.
	AMediaFormat_setInt32(format, "priority", 0);
	AMediaFormat_setFloat(format, "operating-rate", (float)decoder->target_fps);

	media_status_t r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
	if(r != AMEDIA_OK)
	{
		CHIAKI_LOGE(decoder->log, "AMediaCodec_configure() failed: %d", (int)r);
		AMediaFormat_delete(format);
		goto error_codec;
	}

	r = AMediaCodec_start(decoder->codec);
	AMediaFormat_delete(format);
	if(r != AMEDIA_OK)
	{
		CHIAKI_LOGE(decoder->log, "AMediaCodec_start() failed: %d", (int)r);
		goto error_codec;
	}

	ChiakiErrorCode err = chiaki_thread_create(&decoder->output_thread, android_chiaki_video_decoder_output_thread_func, decoder);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create output thread for AMediaCodec");
		goto error_codec;
	}

	decoder->frame_queue_head = 0;
	decoder->frame_queue_tail = 0;
	decoder->frame_queue_count = 0;
	decoder->frame_queue_shutdown = false;
	decoder->next_render_ns = 0;

	err = chiaki_thread_create(&decoder->input_thread, android_chiaki_video_decoder_input_thread_func, decoder);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create decoder input thread");
		goto error_codec;
	}
	decoder->input_thread_running = true;

	goto beach;

error_codec:
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;

error_surface:
	ANativeWindow_release(decoder->window);
	decoder->window = NULL;

beach:
	chiaki_mutex_unlock(&decoder->codec_mutex);
}

// Called on the stream thread. Copies the frame into the ring buffer and signals
// the input thread, then returns immediately — the stream thread is never blocked
// waiting for MediaCodec input buffers.
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;
	(void)frames_lost;
	(void)frame_recovered;

	chiaki_mutex_lock(&decoder->frame_queue_mutex);

	if(decoder->frame_queue_shutdown)
	{
		chiaki_mutex_unlock(&decoder->frame_queue_mutex);
		return false;
	}

	uint8_t *data = malloc(buf_size);
	if(!data)
	{
		chiaki_mutex_unlock(&decoder->frame_queue_mutex);
		return false;
	}
	memcpy(data, buf, buf_size);

	if(decoder->frame_queue_count == ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY)
	{
		// Queue full: evict the oldest frame so the stream thread never stalls
		free(decoder->frame_queue[decoder->frame_queue_head].data);
		decoder->frame_queue_head = (decoder->frame_queue_head + 1) % ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY;
		decoder->frame_queue_count--;
	}

	decoder->frame_queue[decoder->frame_queue_tail].data = data;
	decoder->frame_queue[decoder->frame_queue_tail].size = buf_size;
	decoder->frame_queue_tail = (decoder->frame_queue_tail + 1) % ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY;
	decoder->frame_queue_count++;

	chiaki_cond_signal(&decoder->frame_queue_cond);
	chiaki_mutex_unlock(&decoder->frame_queue_mutex);

	return true;
}

// Dedicated thread: pops frames from the ring buffer and submits them to MediaCodec.
// Codec back-pressure is absorbed here with a 5ms timeout per buffer, without ever
// stalling the stream thread.
static void *android_chiaki_video_decoder_input_thread_func(void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;

	// Raise above default so codec back-pressure is resolved promptly,
	// reducing input timing jitter that causes output bunching.
	setpriority(PRIO_PROCESS, 0, -4);

	while(1)
	{
		chiaki_mutex_lock(&decoder->frame_queue_mutex);
		while(decoder->frame_queue_count == 0 && !decoder->frame_queue_shutdown)
			chiaki_cond_wait(&decoder->frame_queue_cond, &decoder->frame_queue_mutex);

		if(decoder->frame_queue_shutdown)
		{
			while(decoder->frame_queue_count > 0)
			{
				free(decoder->frame_queue[decoder->frame_queue_head].data);
				decoder->frame_queue_head = (decoder->frame_queue_head + 1) % ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY;
				decoder->frame_queue_count--;
			}
			chiaki_mutex_unlock(&decoder->frame_queue_mutex);
			break;
		}

		AndroidChiakiVideoDecoderFrame frame = decoder->frame_queue[decoder->frame_queue_head];
		decoder->frame_queue_head = (decoder->frame_queue_head + 1) % ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY;
		decoder->frame_queue_count--;
		chiaki_mutex_unlock(&decoder->frame_queue_mutex);

		chiaki_mutex_lock(&decoder->codec_mutex);
		if(decoder->codec)
		{
			uint8_t *buf = frame.data;
			size_t buf_size = frame.size;
			while(buf_size > 0)
			{
				ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, 10000);
				if(codec_buf_index < 0)
				{
					CHIAKI_LOGW(decoder->log, "Decoder input thread: no codec buffer after 10ms, dropping frame remainder");
					decoder->input_timeouts++;
					break;
				}
				size_t codec_buf_size;
				uint8_t *codec_buf = AMediaCodec_getInputBuffer(decoder->codec, (size_t)codec_buf_index, &codec_buf_size);
				size_t chunk = buf_size < codec_buf_size ? buf_size : codec_buf_size;
				memcpy(codec_buf, buf, chunk);
				AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, chunk, now_us(), 0);
				buf += chunk;
				buf_size -= chunk;
			}
		}
		chiaki_mutex_unlock(&decoder->codec_mutex);

		free(frame.data);
	}

	CHIAKI_LOGI(decoder->log, "Video Decoder Input Thread exiting");
	return NULL;
}

static void *android_chiaki_video_decoder_output_thread_func(void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;

	// Raise to display-class priority so the OS schedules us promptly when a
	// decoded frame is ready — at 60fps there is only 16ms per vsync window.
	setpriority(PRIO_PROCESS, 0, -8);

	// Vsync grid period in nanoseconds (e.g. 16666667 ns at 60fps).
	const int64_t vsync_period_ns = 1000000000LL / decoder->target_fps;

	// Per-second diagnostics
	int64_t bucket_start_ns   = 0;
	int     bucket_frames     = 0;
	int     short_intervals   = 0; // < 10ms — frame bunching (vsync grid absorbs these)
	int     long_intervals    = 0; // > 25ms — frame stalls or drops (visible stutters)
	int64_t last_frame_ns     = 0;
	int32_t last_input_timeouts = 0;
	int64_t min_headroom_ns   = INT64_MAX; // minimum raw grid headroom per bucket

	decoder->next_render_ns = 0;

	while(1)
	{
		AMediaCodecBufferInfo info;
		ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, -1);
		if(status >= 0)
		{
			if(info.size != 0)
			{
				int64_t now_ns = now_us() * 1000LL;

				// Track wall-clock interval between consecutive output frames,
				// distinguishing bunching (short) from stalls/drops (long).
				if(last_frame_ns > 0)
				{
					int64_t delta_us = (now_ns - last_frame_ns) / 1000LL;
					if(delta_us < 10000)
						short_intervals++;
					else if(delta_us > 25000)
						long_intervals++;
				}
				last_frame_ns = now_ns;

				// Per-second summary log
				if(bucket_start_ns == 0) bucket_start_ns = now_ns;
				bucket_frames++;
				int64_t elapsed_ns = now_ns - bucket_start_ns;
				if(elapsed_ns >= 1000000000LL)
				{
					int32_t cur_timeouts = decoder->input_timeouts;
					int32_t new_timeouts = cur_timeouts - last_input_timeouts;
					last_input_timeouts = cur_timeouts;
					int min_hdm_ms = (min_headroom_ns == INT64_MAX) ? 0 : (int)(min_headroom_ns / 1000000LL);
					CHIAKI_LOGI(decoder->log, "VIDEO_FRAME_TIMING fps=%.1f short=%d long=%d in_tout=%d min_hdm=%d",
						bucket_frames * 1e9f / (float)elapsed_ns,
						short_intervals, long_intervals, new_timeouts, min_hdm_ms);
					bucket_start_ns   = now_ns;
					bucket_frames     = 0;
					short_intervals   = 0;
					long_intervals    = 0;
					min_headroom_ns   = INT64_MAX;
				}

				// Vsync-grid presentation: schedule each frame for a distinct vsync boundary
				// (one period after the previous frame) so SurfaceFlinger never receives two
				// frames in the same window.
				//
				// 4x vsync (67ms) headroom absorbs longs up to 84ms without missing a
				// display vsync. Cap at 8x vsync (133ms) to bound latency drift during
				// bursts. Both underflow and overflow reset to the same baseline so the
				// post-burst period (when longs are most common) always starts with buffer.
				const int64_t baseline_ns = 4 * vsync_period_ns;
				const int64_t cap_ns      = 8 * vsync_period_ns;
				int64_t render_ns = decoder->next_render_ns;
				int64_t headroom_ns = render_ns - now_ns;
				// Skip headroom recording on the very first frame (next_render_ns==0
				// gives a boot-time-sized negative that pollutes the min_hdm log).
				if(decoder->next_render_ns > 0 && headroom_ns < min_headroom_ns)
					min_headroom_ns = headroom_ns;
				if(headroom_ns <= 1000000LL || headroom_ns > cap_ns)
					render_ns = now_ns + baseline_ns;

				AMediaCodec_releaseOutputBufferAtTime(decoder->codec, (size_t)status, render_ns);
				decoder->next_render_ns = render_ns + vsync_period_ns;
				decoder->output_frames_total++;
			}
			else
			{
				AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status, false);
			}

			if(info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)
			{
				CHIAKI_LOGI(decoder->log, "AMediaCodec reported EOS");
				break;
			}
		}
		else
		{
			chiaki_mutex_lock(&decoder->codec_mutex);
			bool shutdown = decoder->shutdown_output;
			chiaki_mutex_unlock(&decoder->codec_mutex);
			if(shutdown)
			{
				CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread detected shutdown after reported error");
				break;
			}
		}
	}

	CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread exiting");
	return NULL;
}
