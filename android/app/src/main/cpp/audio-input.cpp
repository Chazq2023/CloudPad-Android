// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-input.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>

#include <oboe/Oboe.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

// Must match the ChiakiAudioHeader (channels=2, rate=48000, frame_size=480) that
// chiaki-jni.c registers via chiaki_opus_encoder_header() for the mic encoder.
#define MIC_FRAME_SIZE 480
#define MIC_ENCODE_CHANNELS 2
#define MIC_SAMPLE_RATE 48000

#define BUFFER_CHUNK_SIZE (MIC_FRAME_SIZE * sizeof(int16_t))
#define BUFFER_CHUNKS_COUNT 16

using AudioBuffer = CircularBuffer<BUFFER_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

class AudioInput;

class AudioInputCallback: public oboe::AudioStreamCallback
{
private:
	AudioInput *audio_input;

public:
	AudioInputCallback(AudioInput *audio_input) : audio_input(audio_input) {}
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
	void onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error) override;
	void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;
};

struct AudioInput
{
	ChiakiLog *log;
	oboe::ManagedStream stream;
	AudioInputCallback stream_callback;
	AudioBuffer buf;
	ChiakiOpusEncoder *opus_encoder = nullptr;

	std::atomic<bool> muted{true};
	std::atomic<bool> running{false};
	std::atomic<bool> started{false};
	std::thread encode_thread;
	std::mutex cv_mutex;
	std::condition_variable cv;

	AudioInput() : stream_callback(this) {}
};

static void android_chiaki_audio_input_encode_thread(AudioInput *ai)
{
	int16_t mono_frame[MIC_FRAME_SIZE];
	int16_t stereo_frame[MIC_FRAME_SIZE * MIC_ENCODE_CHANNELS];

	while(ai->running.load())
	{
		size_t popped = ai->buf.Pop(reinterpret_cast<uint8_t *>(mono_frame), sizeof(mono_frame));
		if(popped < sizeof(mono_frame))
		{
			std::unique_lock<std::mutex> lock(ai->cv_mutex);
			ai->cv.wait_for(lock, std::chrono::milliseconds(20));
			continue;
		}

		// Don't bother encoding/sending while muted, matching the desktop client's mic mute behavior.
		if(ai->muted.load())
			continue;

		for(size_t i = 0; i < MIC_FRAME_SIZE; i++)
		{
			stereo_frame[2 * i] = mono_frame[i];
			stereo_frame[2 * i + 1] = mono_frame[i];
		}

		chiaki_opus_encoder_frame(stereo_frame, ai->opus_encoder);
	}
}

extern "C" void *android_chiaki_audio_input_new(ChiakiLog *log)
{
	auto r = new AudioInput();
	r->log = log;
	return r;
}

extern "C" void android_chiaki_audio_input_free(void *audio_input)
{
	if(!audio_input)
		return;
	auto ai = reinterpret_cast<AudioInput *>(audio_input);

	ai->running = false;
	ai->cv.notify_all();
	if(ai->encode_thread.joinable())
		ai->encode_thread.join();

	ai->stream = nullptr;
	delete ai;
}

extern "C" bool android_chiaki_audio_input_start(void *audio_input, ChiakiOpusEncoder *encoder)
{
	if(!audio_input)
		return false;
	auto ai = reinterpret_cast<AudioInput *>(audio_input);
	if(ai->started.load())
		return true;

	ai->opus_encoder = encoder;
	ai->muted = true;

	oboe::AudioStreamBuilder builder;
	builder.setDirection(oboe::Direction::Input)
		->setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(oboe::SharingMode::Shared)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(oboe::ChannelCount::Mono)
		->setSampleRate(MIC_SAMPLE_RATE)
		->setInputPreset(oboe::InputPreset::VoiceCommunication)
		->setCallback(&ai->stream_callback);

	auto result = builder.openManagedStream(ai->stream);
	if(result != oboe::Result::OK)
	{
		CHIAKI_LOGE(ai->log, "Audio Input failed to open Oboe stream: %s", oboe::convertToText(result));
		return false;
	}

	result = ai->stream->start();
	if(result != oboe::Result::OK)
	{
		CHIAKI_LOGE(ai->log, "Audio Input failed to start Oboe stream: %s", oboe::convertToText(result));
		ai->stream = nullptr;
		return false;
	}

	ai->running = true;
	ai->encode_thread = std::thread(android_chiaki_audio_input_encode_thread, ai);
	ai->started = true;
	CHIAKI_LOGI(ai->log, "Audio Input started Oboe capture stream");
	return true;
}

extern "C" void android_chiaki_audio_input_set_muted(void *audio_input, bool muted)
{
	if(!audio_input)
		return;
	reinterpret_cast<AudioInput *>(audio_input)->muted = muted;
}

oboe::DataCallbackResult AudioInputCallback::onAudioReady(oboe::AudioStream *stream, void *audio_data, int32_t num_frames)
{
	if(stream->getFormat() != oboe::AudioFormat::I16)
	{
		CHIAKI_LOGE(audio_input->log, "Oboe input stream has invalid format in callback");
		return oboe::DataCallbackResult::Stop;
	}

	int32_t bytes_per_frame = stream->getBytesPerFrame();
	size_t buf_size = static_cast<size_t>(bytes_per_frame * num_frames);
	auto buf = reinterpret_cast<uint8_t *>(audio_data);

	size_t pushed = audio_input->buf.Push(buf, buf_size);
	if(pushed < buf_size)
		CHIAKI_LOGW(audio_input->log, "Audio Input Buffer Overflow!");

	audio_input->cv.notify_one();

	return oboe::DataCallbackResult::Continue;
}

void AudioInputCallback::onErrorBeforeClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_input->log, "Oboe input reported error before close: %s", oboe::convertToText(error));
}

void AudioInputCallback::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error)
{
	CHIAKI_LOGE(audio_input->log, "Oboe input reported error after close: %s", oboe::convertToText(error));
}
