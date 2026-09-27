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
#include <dlfcn.h>

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

// ---- Stall tracing -------------------------------------------------------------------------
// Every frame is timestamped at each hand-off on its way to the screen (see
// AndroidChiakiVideoFrameTrace and ChiakiVideoFrameTiming). When playback stalls, the output
// thread compares the stalled frame against the previous one stage by stage and logs which stage
// the delay first appeared in (FRAME_STALL), so a stutter can be pinned on the network/server,
// a specific thread in this app, the hardware decoder, or the display. Only logs on stalls plus a
// 5-second summary (PIPELINE_5S), so it costs a few clock reads per frame.

static void trace_record_input(AndroidChiakiVideoDecoder *decoder, const AndroidChiakiVideoDecoderFrame *frame, int64_t in_pop_us, int64_t pts_us)
{
	chiaki_mutex_lock(&decoder->trace_mutex);
	AndroidChiakiVideoFrameTrace *t = &decoder->trace[decoder->trace_next];
	decoder->trace_next = (decoder->trace_next + 1) % ANDROID_CHIAKI_VIDEO_TRACE_SIZE;
	memset(t, 0, sizeof(*t));
	t->used = true;
	t->pts_us = pts_us;
	t->net = frame->timing;
	t->enqueue_us = frame->enqueue_us;
	t->in_pop_us = in_pop_us;
	t->queued_us = pts_us; // pts is taken right as the frame is queued to MediaCodec
	chiaki_mutex_unlock(&decoder->trace_mutex);
}

static AndroidChiakiVideoFrameTrace *trace_find_locked(AndroidChiakiVideoDecoder *decoder, int64_t pts_us)
{
	for(size_t i=0; i<ANDROID_CHIAKI_VIDEO_TRACE_SIZE; i++)
		if(decoder->trace[i].used && decoder->trace[i].pts_us == pts_us)
			return &decoder->trace[i];
	return NULL;
}

typedef void (*CloudpadOnFrameRendered)(AMediaCodec *codec, void *userdata, int64_t media_time_us, int64_t system_nano);
typedef media_status_t (*CloudpadSetOnFrameRendered)(AMediaCodec *codec, CloudpadOnFrameRendered callback, void *userdata);

// MediaCodec's frame-rendered callback (Android 13+): when each frame actually reached the screen.
static void trace_on_frame_rendered(AMediaCodec *codec, void *userdata, int64_t media_time_us, int64_t system_nano)
{
	(void)codec;
	AndroidChiakiVideoDecoder *decoder = userdata;
	const int64_t period_ns = 1000000000LL / decoder->target_fps;
	bool log_gap = false;
	int64_t gap_ns = 0, late_ns = 0, out_to_screen_ns = 0;
	int32_t frame_index = -1;

	chiaki_mutex_lock(&decoder->trace_mutex);
	if(decoder->rendered_last_ns > 0)
	{
		gap_ns = system_nano - decoder->rendered_last_ns;
		if(gap_ns > decoder->rendered_max_gap_ns)
			decoder->rendered_max_gap_ns = gap_ns;
		if(gap_ns > 2 * period_ns + 8000000LL)
		{
			if(system_nano - decoder->display_gap_log_window_ns > 5000000000LL)
			{
				decoder->display_gap_log_window_ns = system_nano;
				decoder->display_gap_logs = 0;
			}
			if(decoder->display_gap_logs < 10)
			{
				decoder->display_gap_logs++;
				log_gap = true;
				AndroidChiakiVideoFrameTrace *t = trace_find_locked(decoder, media_time_us);
				if(t)
				{
					frame_index = t->net.flush_us ? t->net.frame_index : -1;
					if(t->render_target_ns > 0)
						late_ns = system_nano - t->render_target_ns;
					if(t->out_us > 0)
						out_to_screen_ns = system_nano - t->out_us * 1000LL;
				}
			}
		}
	}
	decoder->rendered_last_ns = system_nano;
	decoder->rendered_count++;
	chiaki_mutex_unlock(&decoder->trace_mutex);

	// late_ms ~0 means the frame was shown when it was scheduled, so the gap came from earlier in
	// the pipeline (see the matching FRAME_STALL); a large late_ms means the display/compositor
	// held a frame that was ready on time.
	if(log_gap)
		CHIAKI_LOGW(decoder->log, "DISPLAY_GAP gap_ms=%.1f frame=%d late_vs_schedule_ms=%.1f decoded_to_screen_ms=%.1f",
			gap_ns / 1e6, (int)frame_index, late_ns / 1e6, out_to_screen_ns / 1e6);
}

static void trace_register_render_callback(AndroidChiakiVideoDecoder *decoder)
{
	decoder->render_callback_enabled = false;
	// Looked up at runtime: the API only exists on Android 13+ and minSdk is lower.
	void *lib = dlopen("libmediandk.so", RTLD_NOW);
	CloudpadSetOnFrameRendered set_cb = lib ? (CloudpadSetOnFrameRendered)dlsym(lib, "AMediaCodec_setOnFrameRenderedCallback") : NULL;
	if(!set_cb)
	{
		CHIAKI_LOGI(decoder->log, "STALL_TRACE display timing unavailable (needs Android 13+)");
		return;
	}
	media_status_t r = set_cb(decoder->codec, trace_on_frame_rendered, decoder);
	decoder->render_callback_enabled = r == AMEDIA_OK;
	CHIAKI_LOGI(decoder->log, "STALL_TRACE display timing %s (%d)", decoder->render_callback_enabled ? "enabled" : "failed", (int)r);
}

static void trace_reset(AndroidChiakiVideoDecoder *decoder)
{
	chiaki_mutex_lock(&decoder->trace_mutex);
	memset(decoder->trace, 0, sizeof(decoder->trace));
	decoder->trace_next = 0;
	decoder->rendered_last_ns = 0;
	decoder->rendered_count = 0;
	decoder->rendered_max_gap_ns = 0;
	chiaki_mutex_unlock(&decoder->trace_mutex);
}

// Per-stage latencies of one frame, in µs. "read" needs kernel timestamps (0 otherwise).
typedef struct
{
	int64_t read; // reached device -> read by recv thread
	int64_t takq; // read -> picked up by takion thread
	int64_t assemble; // picked up -> frame complete and handed off (decrypt/FEC/reassembly)
	int64_t queue; // handed off -> taken by decoder input thread
	int64_t codec_in; // taken -> accepted by MediaCodec (waiting for an input buffer)
	int64_t decode; // accepted -> decoded frame out of MediaCodec
	int64_t span; // first packet -> last packet of the frame arriving
} TraceStages;

static void trace_stages(const AndroidChiakiVideoFrameTrace *t, TraceStages *s)
{
	const ChiakiVideoFrameTiming *n = &t->net;
	bool kernel = n->last_kernel_us != 0 && n->first_kernel_us != 0;
	s->read = kernel ? (int64_t)n->last_recv_us - (int64_t)n->last_kernel_us : 0;
	s->takq = (int64_t)n->last_pop_us - (int64_t)n->last_recv_us;
	s->assemble = (int64_t)n->flush_us - (int64_t)n->last_pop_us;
	s->queue = t->in_pop_us - (int64_t)n->flush_us;
	s->codec_in = t->queued_us - t->in_pop_us;
	s->decode = t->out_us - t->queued_us;
	s->span = kernel ? (int64_t)n->last_kernel_us - (int64_t)n->first_kernel_us : (int64_t)n->last_recv_us - (int64_t)n->first_recv_us;
}

static int64_t trace_arrival_us(const ChiakiVideoFrameTiming *n, bool use_kernel)
{
	return use_kernel ? (int64_t)n->last_kernel_us : (int64_t)n->last_recv_us;
}

typedef struct
{
	int frames, stalls, untraced, scheduled;
	int64_t sum[6], max[6];
	int64_t span_max;
	size_t size_max;
	int64_t window_start_us;
	int stall_logs;
	int pacing_dropped; // frames Smooth dropped to thin bursts (was in ADAPTIVE_PACING)
} TraceSummary;

static void trace_log_stall(AndroidChiakiVideoDecoder *decoder, const AndroidChiakiVideoFrameTrace *prev, const AndroidChiakiVideoFrameTrace *cur,
	int64_t source_period_us, int64_t headroom_ns, int64_t buffer_ns, int64_t prev_output_busy_us)
{
	TraceStages p, c;
	trace_stages(prev, &p);
	trace_stages(cur, &c);
	bool use_kernel = prev->net.last_kernel_us != 0 && cur->net.last_kernel_us != 0;
	int frames_between = (int16_t)(cur->net.frame_index - prev->net.frame_index);
	int64_t arrival_gap = trace_arrival_us(&cur->net, use_kernel) - trace_arrival_us(&prev->net, use_kernel);
	int64_t expected_gap = frames_between * source_period_us;

	// Output gap = arrival gap + how much each stage's latency grew versus the previous frame, so
	// whichever term is largest is where the stall came from.
	const char *names[] = { use_kernel ? "network_or_server" : "network_or_device_read", "device_read", "takion_thread", "frame_assembly", "decoder_queue", "decoder_input_wait", "hardware_decoder" };
	int64_t growth[] = {
		arrival_gap - expected_gap,
		c.read - p.read,
		c.takq - p.takq,
		c.assemble - p.assemble,
		c.queue - p.queue,
		c.codec_in - p.codec_in,
		c.decode - p.decode
	};
	int origin = 0;
	for(int i=1; i<7; i++)
		if(growth[i] > growth[origin])
			origin = i;
	const char *origin_name = growth[origin] < 5000 ? "no_single_stage" : names[origin];

	// "decode" growth is measured up to when the output thread picked the frame up, so it would also
	// include time the output thread itself spent busy on the previous frame — shown separately.
	if(origin == 6 && prev_output_busy_us > growth[6] / 2)
		origin_name = "output_thread";

	CHIAKI_LOGW(decoder->log, "FRAME_STALL origin=%s frame=%d skipped=%d out_gap_ms=%.1f headroom_ms=%.1f buffer_ms=%.1f output_thread_busy_ms=%.1f"
		" | arrival_gap_ms=%.1f expected_ms=%.1f frame_arrival_span_ms=%.1f size_kb=%.1f units=%u/%u"
		" | growth_ms net=%.1f read=%.1f takion=%.1f assemble=%.1f queue=%.1f codec_in=%.1f decode=%.1f"
		" | now_ms read=%.1f takion=%.1f assemble=%.1f queue=%.1f codec_in=%.1f decode=%.1f",
		origin_name, (int)cur->net.frame_index, frames_between - 1,
		(cur->out_us - prev->out_us) / 1000.0, headroom_ns / 1e6, buffer_ns / 1e6, prev_output_busy_us / 1000.0,
		arrival_gap / 1000.0, expected_gap / 1000.0, c.span / 1000.0, cur->net.frame_size / 1024.0,
		(unsigned int)cur->net.units_total, (unsigned int)cur->net.units_fec,
		growth[0] / 1000.0, growth[1] / 1000.0, growth[2] / 1000.0, growth[3] / 1000.0, growth[4] / 1000.0, growth[5] / 1000.0, growth[6] / 1000.0,
		c.read / 1000.0, c.takq / 1000.0, c.assemble / 1000.0, c.queue / 1000.0, c.codec_in / 1000.0, c.decode / 1000.0);
}

static void trace_summary_add(TraceSummary *s, const AndroidChiakiVideoFrameTrace *t)
{
	TraceStages st;
	trace_stages(t, &st);
	int64_t v[6] = { st.read, st.takq, st.assemble, st.queue, st.codec_in, st.decode };
	for(int i=0; i<6; i++)
	{
		s->sum[i] += v[i];
		if(v[i] > s->max[i])
			s->max[i] = v[i];
	}
	if(st.span > s->span_max)
		s->span_max = st.span;
	if(t->net.frame_size > s->size_max)
		s->size_max = t->net.frame_size;
	s->frames++;
}

static void trace_summary_flush(AndroidChiakiVideoDecoder *decoder, TraceSummary *s, int64_t now_us_val,
	const char *pacing, int64_t buffer_ns, int64_t hold_ns, int64_t arrival_ns)
{
	chiaki_mutex_lock(&decoder->trace_mutex);
	int32_t evictions = decoder->queue_evictions;
	decoder->queue_evictions = 0;
	int32_t rendered = decoder->render_callback_enabled ? decoder->rendered_count : -1;
	decoder->rendered_count = 0;
	int64_t disp_max_gap = decoder->rendered_max_gap_ns;
	decoder->rendered_max_gap_ns = 0;
	chiaki_mutex_unlock(&decoder->trace_mutex);

	int n = s->frames > 0 ? s->frames : 1;
	CHIAKI_LOGI(decoder->log, "PIPELINE_5S pacing=%s buffer_ms=%.1f hold_ms=%.1f arrival_fps=%.1f pacing_dropped=%d"
		" | frames=%d scheduled=%d shown=%d display_max_gap_ms=%.1f stalls=%d queue_evictions=%d untraced=%d"
		" | avg/max_ms read=%.1f/%.1f takion=%.1f/%.1f assemble=%.1f/%.1f queue=%.1f/%.1f codec_in=%.1f/%.1f decode=%.1f/%.1f"
		" | arrival_span_max_ms=%.1f size_max_kb=%.1f",
		pacing, buffer_ns / 1e6, hold_ns / 1e6, arrival_ns > 0 ? 1e9 / (double)arrival_ns : 0.0, s->pacing_dropped,
		s->frames, s->scheduled, (int)rendered, disp_max_gap / 1e6, s->stalls, (int)evictions, s->untraced,
		s->sum[0] / 1000.0 / n, s->max[0] / 1000.0, s->sum[1] / 1000.0 / n, s->max[1] / 1000.0,
		s->sum[2] / 1000.0 / n, s->max[2] / 1000.0, s->sum[3] / 1000.0 / n, s->max[3] / 1000.0,
		s->sum[4] / 1000.0 / n, s->max[4] / 1000.0, s->sum[5] / 1000.0 / n, s->max[5] / 1000.0,
		s->span_max / 1000.0, s->size_max / 1024.0);
	memset(s, 0, sizeof(*s));
	s->window_start_us = now_us_val;
}

ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, int32_t target_fps, ChiakiCodec codec, bool adaptive_frame_pacing_enabled)
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
	decoder->adaptive_frame_pacing_enabled = adaptive_frame_pacing_enabled;

	decoder->frame_queue_head = 0;
	decoder->frame_queue_tail = 0;
	decoder->frame_queue_count = 0;
	decoder->frame_queue_shutdown = true;
	decoder->input_thread_running = false;

	memset(decoder->trace, 0, sizeof(decoder->trace));
	decoder->trace_next = 0;
	decoder->queue_evictions = 0;
	decoder->render_callback_enabled = false;
	decoder->rendered_last_ns = 0;
	decoder->rendered_count = 0;
	decoder->rendered_max_gap_ns = 0;
	decoder->display_gap_logs = 0;
	decoder->display_gap_log_window_ns = 0;

	ChiakiErrorCode err = chiaki_mutex_init(&decoder->trace_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;

	err = chiaki_mutex_init(&decoder->codec_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_mutex_fini(&decoder->trace_mutex);
		return err;
	}

	err = chiaki_mutex_init(&decoder->frame_queue_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_mutex_fini(&decoder->codec_mutex);
		chiaki_mutex_fini(&decoder->trace_mutex);
		return err;
	}

	err = chiaki_cond_init(&decoder->frame_queue_cond);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_mutex_fini(&decoder->frame_queue_mutex);
		chiaki_mutex_fini(&decoder->codec_mutex);
		chiaki_mutex_fini(&decoder->trace_mutex);
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
	chiaki_mutex_fini(&decoder->trace_mutex);
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

	CHIAKI_LOGI(decoder->log, "Video Pacing: %s", decoder->adaptive_frame_pacing_enabled ? "smooth" : "standard");

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
	// Ask for the decoder's maximum speed rather than just the stream fps. An operating rate equal
	// to the fps lets the hardware clock down until an *average* frame just fits its 16.7ms slot,
	// so the large frames of heavy scenes (stall tracing measured 15-20ms decodes at 1440p) overrun
	// it and add to every network stall. Same approach as Moonlight. Some decoders reject rates
	// beyond their capability, so fall back to the stream fps if configure fails.
	AMediaFormat_setFloat(format, "operating-rate", 32767.0f);

	media_status_t r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
	if(r != AMEDIA_OK)
	{
		CHIAKI_LOGW(decoder->log, "AMediaCodec_configure() with max operating rate failed: %d, retrying at %d fps", (int)r, decoder->target_fps);
		// A failed configure can leave the codec unusable, so start again with a fresh one.
		AMediaCodec_delete(decoder->codec);
		decoder->codec = AMediaCodec_createDecoderByType(mime);
		if(!decoder->codec)
		{
			CHIAKI_LOGE(decoder->log, "Failed to re-create AMediaCodec for mime type %s", mime);
			AMediaFormat_delete(format);
			goto error_surface;
		}
		AMediaFormat_setFloat(format, "operating-rate", (float)decoder->target_fps);
		r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
	}
	else
		CHIAKI_LOGI(decoder->log, "Decoder configured with max operating rate");
	if(r != AMEDIA_OK)
	{
		CHIAKI_LOGE(decoder->log, "AMediaCodec_configure() failed: %d", (int)r);
		AMediaFormat_delete(format);
		goto error_codec;
	}

	trace_reset(decoder);
	trace_register_render_callback(decoder);

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
	(void)frames_lost;
	(void)frame_recovered;
	return android_chiaki_video_decoder_video_sample_timed(buf, buf_size, NULL, user);
}

bool android_chiaki_video_decoder_video_sample_timed(uint8_t *buf, size_t buf_size, const ChiakiVideoFrameTiming *timing, AndroidChiakiVideoDecoder *decoder)
{
	int64_t enqueue_us = now_us();
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
		// Queue full: evict the oldest frame so the stream thread never stalls. This throws away a
		// frame that arrived fine (and breaks the frames that reference it), so make it visible.
		AndroidChiakiVideoDecoderFrame *evicted = &decoder->frame_queue[decoder->frame_queue_head];
		chiaki_mutex_lock(&decoder->trace_mutex);
		int32_t evictions = ++decoder->queue_evictions;
		chiaki_mutex_unlock(&decoder->trace_mutex);
		if(evictions <= 5)
			CHIAKI_LOGW(decoder->log, "VIDEO_QUEUE_EVICT frame=%d waited_ms=%.1f — decoder input is not keeping up, frame discarded",
				evicted->timing.flush_us ? (int)evicted->timing.frame_index : -1, (enqueue_us - evicted->enqueue_us) / 1000.0);
		free(decoder->frame_queue[decoder->frame_queue_head].data);
		decoder->frame_queue_head = (decoder->frame_queue_head + 1) % ANDROID_CHIAKI_VIDEO_DECODER_FRAME_QUEUE_CAPACITY;
		decoder->frame_queue_count--;
	}

	decoder->frame_queue[decoder->frame_queue_tail].data = data;
	decoder->frame_queue[decoder->frame_queue_tail].size = buf_size;
	if(timing)
		decoder->frame_queue[decoder->frame_queue_tail].timing = *timing;
	else
		memset(&decoder->frame_queue[decoder->frame_queue_tail].timing, 0, sizeof(ChiakiVideoFrameTiming));
	decoder->frame_queue[decoder->frame_queue_tail].enqueue_us = enqueue_us;
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
		int64_t in_pop_us = now_us();

		chiaki_mutex_lock(&decoder->codec_mutex);
		if(decoder->codec)
		{
			uint8_t *buf = frame.data;
			size_t buf_size = frame.size;
			bool first_chunk = true;
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
				int64_t pts_us = now_us();
				AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, chunk, pts_us, 0);
				if(first_chunk)
				{
					trace_record_input(decoder, &frame, in_pop_us, pts_us);
					first_chunk = false;
				}
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

void android_chiaki_video_decoder_set_smooth_pacing(AndroidChiakiVideoDecoder *decoder, bool smooth)
{
	// Read once per frame by the output thread, which also restarts its schedule when it sees the
	// change — a plain flag is enough (no other state is shared).
	decoder->adaptive_frame_pacing_enabled = smooth;
}

static void *android_chiaki_video_decoder_output_thread_func(void *user)
{
	AndroidChiakiVideoDecoder *decoder = user;

	// Raise to display-class priority so the OS schedules us promptly when a
	// decoded frame is ready — at 60fps there is only 16ms per vsync window.
	setpriority(PRIO_PROCESS, 0, -8);

	// Presentation grid period in nanoseconds: one frame at the stream's target fps (16666667 ns
	// at 60fps, 33333333 ns at 30fps). This is the unit the presentation cushion is sized in,
	// not the display's own refresh period — a 120Hz panel still gets frames scheduled on this
	// grid and SurfaceFlinger picks the matching refresh.
	const int64_t vsync_period_ns = 1000000000LL / decoder->target_fps;

	// Source cadence: how often frames actually arrive. Starts at the target rate and is
	// re-detected from decode spacing, because the server can deliver half the target rate
	// (e.g. a 30fps game on a 60fps cloud stream). Measuring a steady 30fps arrival against a
	// 60fps grid made every frame look like a stall and every gap look like ~16ms of jitter.
	// It is only used to judge intervals and step the schedule — the cushion size stays in
	// grid periods above, so switching the cadence never changes how much latency is added.
	int64_t source_period_ns = vsync_period_ns;
	const int cadence_detect_streak = 8; // consecutive frames before flipping, to debounce
	int cadence_full_streak = 0;
	int cadence_half_streak = 0;

	// Per-second diagnostics
	int64_t bucket_start_ns   = 0;
	int     bucket_frames     = 0;
	int     short_intervals   = 0; // < 0.6× vsync — frame bunching
	int     long_intervals    = 0; // > 1.5× vsync — frame stalls or drops
	int64_t last_frame_ns     = 0;
	int32_t last_input_timeouts = 0;
	int64_t min_headroom_ns   = INT64_MAX; // minimum raw grid headroom per bucket
	int64_t ema_inter_frame_ns = vsync_period_ns; // EMA of actual inter-frame interval

	// Adaptive Frame Pacing: continuously-updated jitter magnitude (independent of the
	// short/long bucket counts above, which reset every second) used to size the
	// presentation buffer dynamically instead of a fixed constant. Computed unconditionally
	// (cheap) but only changes presentation behavior when adaptive_frame_pacing_enabled.
	int64_t ema_jitter_ns    = 0;
	int     adaptive_dropped = 0; // frames dropped this second to thin a burst

	// Smooth pacing peak hold. The jitter EMA above forgets a stall within a fraction of a second,
	// so the cushion shrank straight back between stalls and the next one got through. Stall
	// tracing showed why stalls come in clusters: in heavy scenes (notably 30fps "graphics" modes)
	// the cloud server sends frames of 140-210KB but paces delivery at the stream bitrate (~32Mbps),
	// so each takes 35-55ms to arrive instead of 16.7ms. After a real stall, hold a cushion big
	// enough for that gap for a while, then let it decay back.
	const int64_t smooth_hold_duration_ns = 10000000000LL; // keep the raised cushion this long
	const int64_t smooth_hold_decay_per_s_ns = vsync_period_ns; // then shrink by one frame per second
	int64_t smooth_hold_ns     = 0;
	int64_t smooth_hold_set_ns = 0;
	int64_t smooth_hold_decay_ns = 0; // time the decay was last applied

	// Smooth: average arrival interval over *all* frames, stalls included (each sample clamped so
	// one outlier can't dominate). ema_inter_frame_ns deliberately ignores long gaps, so in heavy
	// cloud scenes where the server only manages ~55-58 frames/s for seconds at a time, pacing at
	// it presented faster than frames arrived and drained the cushion ~50ms/s into a visible
	// freeze. Pacing at the real rate shows those frames evenly instead.
	int64_t ema_arrival_ns = vsync_period_ns;

	decoder->next_render_ns = 0;
	bool last_smooth = decoder->adaptive_frame_pacing_enabled;
	int64_t last_baseline_ns = 2 * vsync_period_ns; // for the per-second log only

	// Stall tracing (see trace_log_stall): the previous output frame, to compare each frame against.
	AndroidChiakiVideoFrameTrace trace_prev;
	bool trace_have_prev = false;
	TraceSummary trace_summary;
	memset(&trace_summary, 0, sizeof(trace_summary));
	int64_t trace_output_busy_us = 0; // how long this thread spent handling the previous frame

	while(1)
	{
		AMediaCodecBufferInfo info;
		ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, -1);
		if(status >= 0)
		{
			if(info.size != 0)
			{
				int64_t now_ns = now_us() * 1000LL;

				AndroidChiakiVideoFrameTrace trace_cur;
				chiaki_mutex_lock(&decoder->trace_mutex);
				AndroidChiakiVideoFrameTrace *trace_entry = trace_find_locked(decoder, info.presentationTimeUs);
				bool trace_have_cur = trace_entry != NULL;
				if(trace_entry)
				{
					trace_entry->out_us = now_ns / 1000;
					trace_cur = *trace_entry;
				}
				chiaki_mutex_unlock(&decoder->trace_mutex);

				// Video Pacing mode can change mid-stream (quick menu). Re-read it once per frame
				// and, on a change, restart the schedule so the new cushion takes effect right away
				// instead of waiting for the old one to drain (one brief timestamp reset, same as a
				// stall recovery).
				const bool smooth = decoder->adaptive_frame_pacing_enabled;
				if(smooth != last_smooth)
				{
					last_smooth = smooth;
					decoder->next_render_ns = 0;
					CHIAKI_LOGI(decoder->log, "Video pacing switched to %s", smooth ? "smooth" : "standard");
				}

				// Track wall-clock interval between consecutive output frames.
				// EMA of normal intervals tracks the actual source rate so the schedule
				// advances at the real rate, preventing headroom drain.
				if(last_frame_ns > 0)
				{
					int64_t delta_ns = now_ns - last_frame_ns;

					// Cadence detection, relative to the grid period so it holds for a 30 or 60fps
					// target: gaps under 1.5 grid periods are a full-rate source, 1.5-3.3 periods a
					// half-rate one, anything longer is a stall and says nothing about the cadence.
					int64_t detected_period_ns = 0;
					if(delta_ns < vsync_period_ns * 3 / 2)
					{
						cadence_full_streak++;
						cadence_half_streak = 0;
						if(cadence_full_streak >= cadence_detect_streak)
							detected_period_ns = vsync_period_ns;
					}
					else if(delta_ns < vsync_period_ns * 33 / 10)
					{
						cadence_half_streak++;
						cadence_full_streak = 0;
						if(cadence_half_streak >= cadence_detect_streak)
							detected_period_ns = vsync_period_ns * 2;
					}
					if(detected_period_ns != 0 && detected_period_ns != source_period_ns)
					{
						source_period_ns = detected_period_ns;
						ema_inter_frame_ns = source_period_ns;
						CHIAKI_LOGI(decoder->log, "Video pacing: source cadence %.1f ms (%.0f fps)",
							(double)source_period_ns / 1000000.0, 1e9 / (double)source_period_ns);
					}

					// Short/long relative to the source cadence: bunching (<0.6x) or a stall (>1.5x).
					if(delta_ns < source_period_ns * 6 / 10)
						short_intervals++;
					else if(delta_ns > source_period_ns * 15 / 10)
						long_intervals++;
					else
						ema_inter_frame_ns = (ema_inter_frame_ns * 7 + delta_ns) / 8;

					int64_t deviation_ns = delta_ns > source_period_ns ? delta_ns - source_period_ns : source_period_ns - delta_ns;
					ema_jitter_ns = (ema_jitter_ns * 7 + deviation_ns) / 8;

					int64_t arrival_sample_ns = delta_ns < 4 * source_period_ns ? delta_ns : 4 * source_period_ns;
					ema_arrival_ns = (ema_arrival_ns * 15 + arrival_sample_ns) / 16;

					// Smooth peak hold: a real stall (same threshold as FRAME_STALL) raises the held
					// cushion to cover a gap that size plus a frame of margin. Ordinary bunching
					// (e.g. the paired arrivals of 30fps content) stays below the threshold.
					if(delta_ns > 2 * source_period_ns + 8000000LL)
					{
						int64_t want_ns = (delta_ns - source_period_ns) + 3 * vsync_period_ns;
						if(want_ns > smooth_hold_ns)
						{
							smooth_hold_ns = want_ns;
							if(decoder->adaptive_frame_pacing_enabled)
								CHIAKI_LOGI(decoder->log, "Video pacing: %.1f ms gap, holding smooth buffer at up to %.1f ms",
									delta_ns / 1e6, want_ns / 1e6);
						}
						smooth_hold_set_ns = now_ns;
						smooth_hold_decay_ns = now_ns;
					}
					else if(smooth_hold_ns > 0 && now_ns - smooth_hold_set_ns > smooth_hold_duration_ns)
					{
						smooth_hold_ns -= smooth_hold_decay_per_s_ns * (now_ns - smooth_hold_decay_ns) / 1000000000LL;
						if(smooth_hold_ns < 0)
							smooth_hold_ns = 0;
						smooth_hold_decay_ns = now_ns;
					}
					else if(smooth_hold_ns > 0)
						smooth_hold_decay_ns = now_ns;
				}
				last_frame_ns = now_ns;

				// Per-second summary log
				if(bucket_start_ns == 0) bucket_start_ns = now_ns;
				bucket_frames++;
				int64_t elapsed_ns = now_ns - bucket_start_ns;
				if(elapsed_ns >= 1000000000LL)
				{
					// These two once-a-second lines are left commented out to keep routine logging
					// off the output thread; PIPELINE_5S (see trace_summary_flush) carries the same
					// health picture every 5 seconds, and problems are logged as they happen
					// (FRAME_STALL / DISPLAY_GAP). Uncomment when tuning pacing itself.
					// int32_t cur_timeouts = decoder->input_timeouts;
					// int32_t new_timeouts = cur_timeouts - last_input_timeouts;
					// last_input_timeouts = cur_timeouts;
					// int min_hdm_ms = (min_headroom_ns == INT64_MAX) ? 0 : (int)(min_headroom_ns / 1000000LL);
					// CHIAKI_LOGI(decoder->log, "VIDEO_FRAME_TIMING fps=%.1f short=%d long=%d in_tout=%d min_hdm=%d src_ms=%.1f buf_ms=%d mode=%s",
					// 	bucket_frames * 1e9f / (float)elapsed_ns,
					// 	short_intervals, long_intervals, new_timeouts, min_hdm_ms,
					// 	(double)source_period_ns / 1000000.0, (int)(last_baseline_ns / 1000000LL),
					// 	smooth ? "smooth" : "standard");
					// if(smooth)
					// {
					// 	CHIAKI_LOGI(decoder->log, "ADAPTIVE_PACING jitter_ms=%.1f hold_ms=%.1f arrival_fps=%.1f dropped=%d",
					// 		(double)ema_jitter_ns / 1000000.0, (double)smooth_hold_ns / 1000000.0,
					// 		1e9 / (double)ema_arrival_ns, adaptive_dropped);
					// }
					(void)last_input_timeouts;
					trace_summary.pacing_dropped += adaptive_dropped;
					adaptive_dropped = 0;
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
				// Standard: a fixed 2x grid period (33ms at 60fps) baseline minimises display-side
				// input latency.
				// Smooth scales the baseline up with recently observed jitter (ema_jitter_ns above), or
				// the peak-hold cushion after a recent stall (smooth_hold_ns above) if that's larger,
				// instead of using the fixed value, up to 6x (100ms at 60fps — the largest gaps seen in
				// traced cloud sessions needed ~92ms) so a bad connection can't pile latency on
				// indefinitely, and proactively drops frames that arrive well ahead of schedule during
				// a burst — see below — rather than queueing them further into the future and letting
				// latency creep up until a hard cap-reset is needed.
				int64_t baseline_ns = 2 * vsync_period_ns;
				// Cushion a late frame is re-scheduled with. The peak-hold part of the Smooth
				// baseline is deliberately left out: jumping straight to it would freeze the picture
				// for that much longer right on top of the stall. It is grown into gradually instead
				// (see advance_ns below).
				int64_t reset_baseline_ns = baseline_ns;
				if(smooth)
				{
					const int64_t smooth_max_ns = 6 * vsync_period_ns;
					int64_t smooth_baseline_ns = baseline_ns + ema_jitter_ns * 2;
					reset_baseline_ns = smooth_baseline_ns > smooth_max_ns ? smooth_max_ns : smooth_baseline_ns;
					if(smooth_hold_ns > smooth_baseline_ns)
						smooth_baseline_ns = smooth_hold_ns;
					baseline_ns = smooth_baseline_ns > smooth_max_ns ? smooth_max_ns : smooth_baseline_ns;
				}
				last_baseline_ns = baseline_ns;
				const int64_t cap_ns = 4 * baseline_ns;
				int64_t render_ns = decoder->next_render_ns;
				int64_t headroom_ns = render_ns - now_ns;
				// Skip headroom recording on the very first frame (next_render_ns==0
				// gives a boot-time-sized negative that pollutes the min_hdm log).
				if(decoder->next_render_ns > 0 && headroom_ns < min_headroom_ns)
					min_headroom_ns = headroom_ns;
				const bool trace_scheduled = decoder->next_render_ns > 0;
				int64_t trace_render_ns = 0;

				// Smooth pacing: this frame arrived as part of a burst and the
				// schedule is already comfortably ahead — drop it instead of queueing yet
				// another vsync_period_ns onto next_render_ns, which would otherwise let
				// presentation latency creep up through the whole burst until the cap-reset
				// below eventually fires (itself a visible stutter).
				if(smooth && decoder->next_render_ns > 0
					&& headroom_ns > baseline_ns + 2 * vsync_period_ns)
				{
					AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status, false);
					adaptive_dropped++;
				}
				else
				{
					if(headroom_ns <= 1000000LL || headroom_ns > cap_ns)
						render_ns = now_ns + reset_baseline_ns;

					AMediaCodec_releaseOutputBufferAtTime(decoder->codec, (size_t)status, render_ns);
					trace_render_ns = render_ns;
					// Target baseline headroom: when above baseline use vsync_period so excess
					// bleeds off naturally; when at/below baseline use EMA (>= vsync_period) to
					// counteract systematic drain if server delivers slightly below 60fps.
					// This keeps headroom near baseline and prevents cap-overflow resets, which
					// cause timestamp inversions (newer frame scheduled before older frame in
					// SurfaceFlinger queue → visible stutter every ~13s).
					int64_t advance_ns = (headroom_ns > baseline_ns)
						? vsync_period_ns
						: (ema_inter_frame_ns > source_period_ns ? ema_inter_frame_ns : source_period_ns);
					if(smooth && headroom_ns <= baseline_ns)
					{
						// Smooth: present at the rate frames are really arriving (see ema_arrival_ns),
						// never slower than half rate.
						int64_t arrival_ns = ema_arrival_ns < 2 * source_period_ns ? ema_arrival_ns : 2 * source_period_ns;
						if(arrival_ns > advance_ns)
							advance_ns = arrival_ns;
						// Refill the cushion when it's below target — after a stall, or into a raised
						// peak hold — by showing each frame 2ms later than the last until it's within a
						// frame of the target (~0.4s for +50ms). On screen that's an occasional repeated
						// frame rather than waiting for the next freeze to reset it.
						if(render_ns - now_ns < baseline_ns - vsync_period_ns)
							advance_ns += 2000000LL;
					}
					decoder->next_render_ns = render_ns + advance_ns;
					decoder->output_frames_total++;
				}

				// Stall tracing: a frame is a stall when it came out of the decoder much later than
				// the previous one, or too late for its display slot.
				int64_t now_trace_us = now_ns / 1000;
				if(trace_summary.window_start_us == 0)
					trace_summary.window_start_us = now_trace_us;
				if(trace_have_cur)
				{
					trace_cur.render_target_ns = trace_render_ns;
					chiaki_mutex_lock(&decoder->trace_mutex);
					AndroidChiakiVideoFrameTrace *entry = trace_find_locked(decoder, info.presentationTimeUs);
					if(entry)
						entry->render_target_ns = trace_render_ns;
					chiaki_mutex_unlock(&decoder->trace_mutex);

					if(trace_render_ns)
						trace_summary.scheduled++;
					if(trace_cur.net.flush_us != 0)
					{
						trace_summary_add(&trace_summary, &trace_cur);
						if(trace_have_prev)
						{
							const int64_t source_period_us = source_period_ns / 1000;
							int64_t out_gap_us = trace_cur.out_us - trace_prev.out_us;
							bool late = trace_scheduled && headroom_ns < 0;
							if(out_gap_us > 2 * source_period_us + 8000 || late)
							{
								trace_summary.stalls++;
								if(trace_summary.stall_logs < 10)
								{
									trace_summary.stall_logs++;
									trace_log_stall(decoder, &trace_prev, &trace_cur, source_period_us, trace_scheduled ? headroom_ns : 0, baseline_ns, trace_output_busy_us);
								}
							}
						}
						trace_prev = trace_cur;
						trace_have_prev = true;
					}
				}
				else
					trace_summary.untraced++;
				if(now_trace_us - trace_summary.window_start_us >= 5000000)
					trace_summary_flush(decoder, &trace_summary, now_trace_us, smooth ? "smooth" : "standard",
						baseline_ns, smooth_hold_ns, ema_arrival_ns);
				trace_output_busy_us = now_us() - now_trace_us;
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
