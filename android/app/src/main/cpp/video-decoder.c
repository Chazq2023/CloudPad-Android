// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder.h"

#include <jni.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window_jni.h>

#include <string.h>
#include <stdlib.h>

#include <time.h>
#include <inttypes.h>


static int64_t now_ms()
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ((int64_t)ts.tv_sec * 1000) + (ts.tv_nsec / 1000000);
}

static void *android_chiaki_video_decoder_output_thread_func(void *user);
static void *android_chiaki_video_decoder_input_thread_func(void *user);

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, ChiakiCodec codec)
{
	decoder->log = log;
	decoder->codec = NULL;
	decoder->timestamp_cur = 0;
	decoder->target_width = target_width;
	decoder->target_height = target_height;
	decoder->target_codec = codec;
	decoder->shutdown_output = false;

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
		AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0, decoder->timestamp_cur++, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
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
		// Release codec_mutex is not held here, so we can safely stop input thread then kill decoder.
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

	err = chiaki_thread_create(&decoder->input_thread, android_chiaki_video_decoder_input_thread_func, decoder);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(decoder->log, "Failed to create decoder input thread");
		// output thread already running; kill_decoder will clean up
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

// Called on the stream thread. Copies the frame into the queue and returns immediately
// so the stream thread is never blocked waiting for MediaCodec input buffers.
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;
	(void)frames_lost;
	(void)frame_recovered;

	static int64_t last_log_ms = 0;
	static int64_t samples_in = 0;
	static int64_t queue_drops = 0;
	static int64_t bytes_in = 0;

	samples_in++;
	bytes_in += buf_size;

	chiaki_mutex_lock(&decoder->frame_queue_mutex);

	if(decoder->frame_queue_shutdown)
	{
		chiaki_mutex_unlock(&decoder->frame_queue_mutex);
		return false;
	}

	uint8_t *data = malloc(buf_size);
	if(!data)
	{
		queue_drops++;
		chiaki_mutex_unlock(&decoder->frame_queue_mutex);
		return false;
	}
	memcpy(data, buf, buf_size);

	if(decoder->frame_queue_count == ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY)
	{
		// Queue full: evict oldest frame so the stream thread never stalls
		free(decoder->frame_queue[decoder->frame_queue_head].data);
		decoder->frame_queue_head = (decoder->frame_queue_head + 1) % ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY;
		decoder->frame_queue_count--;
		queue_drops++;
	}

	decoder->frame_queue[decoder->frame_queue_tail].data = data;
	decoder->frame_queue[decoder->frame_queue_tail].size = buf_size;
	decoder->frame_queue_tail = (decoder->frame_queue_tail + 1) % ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY;
	decoder->frame_queue_count++;

	chiaki_cond_signal(&decoder->frame_queue_cond);
	chiaki_mutex_unlock(&decoder->frame_queue_mutex);

	int64_t now = now_ms();
	if(last_log_ms == 0)
		last_log_ms = now;
	if(now - last_log_ms >= 1000)
	{
		CHIAKI_LOGI(
			decoder->log,
			"VIDEO_DIAG in=%" PRId64 " queue_drops=%" PRId64 " bytes=%" PRId64,
			samples_in, queue_drops, bytes_in
		);
		samples_in = 0;
		queue_drops = 0;
		bytes_in = 0;
		last_log_ms = now;
	}

	return true;
}

// Dedicated thread that dequeues frames from the ring buffer and submits them to
// MediaCodec. Runs independently of the stream thread so codec back-pressure never
// stalls network packet reception.
static void *android_chiaki_video_decoder_input_thread_func(void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;

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
				// 5ms timeout: long enough to absorb brief codec back-pressure without
				// dropping, short enough not to impede the shutdown path.
				ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, 5000);
				if(codec_buf_index < 0)
				{
					CHIAKI_LOGW(decoder->log, "Decoder input thread: no codec buffer after 5ms, dropping frame remainder");
					break;
				}
				size_t codec_buf_size;
				uint8_t *codec_buf = AMediaCodec_getInputBuffer(decoder->codec, (size_t)codec_buf_index, &codec_buf_size);
				size_t chunk = buf_size < codec_buf_size ? buf_size : codec_buf_size;
				memcpy(codec_buf, buf, chunk);
				AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, chunk, decoder->timestamp_cur++, 0);
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

	while(1)
	{
		AMediaCodecBufferInfo info;
		ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, -1);
		if(status >= 0)
		{
			if(info.size != 0)
			{
				AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status, true);
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
