// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>
#include <chiaki/thread.h>

#include <oboe/Oboe.h>

#include <chrono>
#include <thread>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_CHUNKS_COUNT 32

using AudioBuffer = CircularBuffer<BUFFER_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioOutput;

class AudioOutputCallback: public oboe::AudioStreamCallback
{
private:
	AudioOutput *audio_output;

public:
	AudioOutputCallback(AudioOutput *audio_output) : audio_output(audio_output) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioOutput
{
	ChiakiLog *log;
	oboe::ManagedStream stream;
	AudioOutputCallback stream_callback;
	AudioBuffer buf;

	AudioOutput() : stream_callback(this) {}
};

static bool android_chiaki_audio_output_open(AudioOutput *ao, uint32_t channels, uint32_t rate,
		oboe::PerformanceMode performance_mode)
{
	oboe::AudioStreamBuilder builder;
	builder.setPerformanceMode(performance_mode)
		->setSharingMode(oboe::SharingMode::Shared)
		->setUsage(oboe::Usage::Game)
		->setContentType(oboe::ContentType::Music)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(channels)
		->setSampleRate(rate)
		->setCallback(&ao->stream_callback);

	auto result = builder.openManagedStream(ao->stream);
	if(result != oboe::Result::OK || !ao->stream)
	{
		CHIAKI_LOGE(ao->log, "Audio Output failed to open Oboe stream (%s): %s",
				oboe::convertToText(performance_mode), oboe::convertToText(result));
		ao->stream = nullptr;
		return false;
	}

	result = ao->stream->start();
	if(result != oboe::Result::OK)
	{
		CHIAKI_LOGE(ao->log, "Audio Output failed to start Oboe stream (%s): %s",
				oboe::convertToText(performance_mode), oboe::convertToText(result));
		ao->stream = nullptr;
		return false;
	}

	CHIAKI_LOGI(ao->log, "Audio Output opened and started shared Oboe stream (%s)",
			oboe::convertToText(performance_mode));
	return true;
}

extern "C" void *android_chiaki_audio_output_new(ChiakiLog *log)
{
	auto r = new AudioOutput();
	r->log = log;
	return r;
}

extern "C" void android_chiaki_audio_output_free(void *audio_output)
{
	if(!audio_output)
		return;
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stream = nullptr;
	delete ao;
}

extern "C" void android_chiaki_audio_output_settings(uint32_t channels, uint32_t rate, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);
	ao->stream = nullptr;

	// AAudio can transiently fail to open a stream right at session start (e.g. AAUDIO_ERROR_UNAVAILABLE
	// while the audio server is still settling from the video decoder/surface setup happening at the same
	// moment), and this settings callback only ever fires once per stream start — so a failure here isn't
	// retried later and leaves the session silent for its whole duration. Retry a few times with backoff
	// before giving up, since these errors typically clear within tens to a couple hundred milliseconds.
	constexpr int kMaxAttempts = 4;
	for(int attempt = 1; attempt <= kMaxAttempts; attempt++)
	{
		if(android_chiaki_audio_output_open(ao, channels, rate, oboe::PerformanceMode::LowLatency))
			return;
		if(android_chiaki_audio_output_open(ao, channels, rate, oboe::PerformanceMode::None))
			return;
		if(attempt < kMaxAttempts)
		{
			CHIAKI_LOGW(ao->log, "Audio Output failed to open in both performance modes, retrying (attempt %d/%d)", attempt, kMaxAttempts);
			std::this_thread::sleep_for(std::chrono::milliseconds(100 * attempt));
		}
	}
	CHIAKI_LOGE(ao->log, "Audio Output giving up opening Oboe stream after %d attempts — stream will be silent", kMaxAttempts);
}

extern "C" void android_chiaki_audio_output_frame(int16_t *buf, size_t samples_count, void *audio_output)
{
	auto ao = reinterpret_cast<AudioOutput *>(audio_output);

	size_t buf_size = samples_count * sizeof(int16_t);
	size_t pushed = ao->buf.Push(reinterpret_cast<uint8_t *>(buf), buf_size);
	if(pushed < buf_size)
		CHIAKI_LOGW(ao->log, "Audio Output Buffer Overflow!");
}

oboe::DataCallbackResult AudioOutputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(audio_output->log, "Oboe stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size_requested = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	size_t buf_size_delivered = audio_output->buf.Pop(buf, buf_size_requested);
	//CHIAKI_LOGW(audio_output->log, "Delivered %llu", (unsigned long long)buf_size_delivered);

	if(buf_size_delivered < buf_size_requested)
	{
		CHIAKI_LOGV(audio_output->log, "Audio Output Buffer Underflow!");
		memset(buf + buf_size_delivered, 0, buf_size_requested - buf_size_delivered);
	}

	return oboe::DataCallbackResult::Continue;
}

void AudioOutputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error before close: %s", oboe::convertToText(error));
}

void AudioOutputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_output->log, "Oboe reported error after close: %s", oboe::convertToText(error));
}
