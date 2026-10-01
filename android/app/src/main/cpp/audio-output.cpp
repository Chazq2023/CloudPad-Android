// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-output.h"

#include "circular-buf.hpp"

#include <chiaki/log.h>
#include <chiaki/thread.h>

#include <oboe/Oboe.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <thread>

#define BUFFER_CHUNK_SIZE 1024
#define BUFFER_CHUNKS_COUNT 32

using AudioBuffer = CircularBuffer<BUFFER_CHUNKS_COUNT, BUFFER_CHUNK_SIZE>;

// Software volume boost applied to every decoded frame. Sony's streams are mixed with a lot of
// headroom, so even at full device volume they come out noticeably quieter than other apps.
// Process-wide (only one session plays at a time) so it can be changed live from Quick Settings.
static std::atomic<float> audio_volume_boost(1.0f);

// Samples below this stay perfectly linear; above it they're compressed smoothly towards full
// scale with tanh, so a boosted loud passage rounds off instead of hard-clipping into crackle.
#define LIMITER_KNEE 0.7f

static inline int16_t limit_to_sample(float x)
{
	float mag = std::fabs(x);
	if(mag > LIMITER_KNEE)
	{
		float over = (mag - LIMITER_KNEE) / (1.0f - LIMITER_KNEE);
		mag = LIMITER_KNEE + (1.0f - LIMITER_KNEE) * std::tanh(over);
		x = x < 0.0f ? -mag : mag;
	}
	return static_cast<int16_t>(std::lrint(x * 32767.0f));
}

extern "C" void android_chiaki_audio_output_set_volume_boost(float gain)
{
	audio_volume_boost.store(gain < 1.0f ? 1.0f : gain, std::memory_order_relaxed);
}

// Clear Voice: game dialogue is almost always mixed dead centre, while music and ambience are
// spread wide. The signal is split into side (L-R), the speech band of the mid (L+R, 250Hz-4kHz)
// and the rest of the mid. The speech band is lifted and everything else is lowered, and while
// dialogue is detected (speech band clearly dominating everything else) the non-speech parts
// are additionally ducked, then released between lines. Overall loudness stays about the same,
// so it separates voices rather than just making the stream louder. Runs before Audio Boost and
// shares its limiter, so the two stack without clipping.
static std::atomic<bool> audio_clear_voice(false);

#define CLEAR_VOICE_BAND_LOW_HZ 250.0f
#define CLEAR_VOICE_BAND_HIGH_HZ 4000.0f
#define CLEAR_VOICE_SPEECH_GAIN 1.68f      // +4.5dB
#define CLEAR_VOICE_REST_GAIN 0.5f         // -6dB
#define CLEAR_VOICE_SIDE_GAIN 0.4f         // -8dB
#define CLEAR_VOICE_DUCK_GAIN 0.28f        // extra -11dB on non-speech while dialogue plays
#define CLEAR_VOICE_DETECT_FLOOR 0.003f    // ~-50dBFS: quieter speech-band content never ducks
#define CLEAR_VOICE_DETECT_RATIO 0.5f      // speech-band level vs everything else to count as dialogue
#define CLEAR_VOICE_ENV_ATTACK_S 0.01f
#define CLEAR_VOICE_ENV_RELEASE_S 0.25f
#define CLEAR_VOICE_DUCK_ATTACK_S 0.05f
#define CLEAR_VOICE_DUCK_RELEASE_S 0.4f

struct Biquad
{
	float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
	float z1 = 0.0f, z2 = 0.0f;

	float Process(float x)
	{
		float y = b0 * x + z1;
		z1 = b1 * x - a1 * y + z2;
		z2 = b2 * x - a2 * y;
		return y;
	}

	void Reset() { z1 = z2 = 0.0f; }

	void SetNormalized(float nb0, float nb1, float nb2, float na0, float na1, float na2)
	{
		b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0;
		a1 = na1 / na0; a2 = na2 / na0;
	}

	// RBJ Audio EQ Cookbook high-pass, Butterworth Q
	void SetHighPass(float rate, float freq)
	{
		float w0 = 2.0f * static_cast<float>(M_PI) * freq / rate;
		float cosw = std::cos(w0);
		float alpha = std::sin(w0) / (2.0f * static_cast<float>(M_SQRT1_2));
		SetNormalized((1.0f + cosw) / 2.0f, -(1.0f + cosw), (1.0f + cosw) / 2.0f,
				1.0f + alpha, -2.0f * cosw, 1.0f - alpha);
	}

	// RBJ Audio EQ Cookbook low-pass, Butterworth Q
	void SetLowPass(float rate, float freq)
	{
		float w0 = 2.0f * static_cast<float>(M_PI) * freq / rate;
		float cosw = std::cos(w0);
		float alpha = std::sin(w0) / (2.0f * static_cast<float>(M_SQRT1_2));
		SetNormalized((1.0f - cosw) / 2.0f, 1.0f - cosw, (1.0f - cosw) / 2.0f,
				1.0f + alpha, -2.0f * cosw, 1.0f - alpha);
	}
};

extern "C" void android_chiaki_audio_output_set_clear_voice(bool enabled)
{
	audio_clear_voice.store(enabled, std::memory_order_relaxed);
}

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

	// Only touched from the decoder thread that delivers frames (and the settings callback that
	// precedes them), so no locking is needed.
	uint32_t channels = 0;
	Biquad voice_high_pass;
	Biquad voice_low_pass;
	// One-pole smoothing coefficients (per sample) and state for the dialogue detector/ducker.
	float env_attack = 0.0f, env_release = 0.0f, duck_attack = 0.0f, duck_release = 0.0f;
	float speech_env = 0.0f, other_env = 0.0f, duck = 1.0f;
	bool clear_voice_was_on = false;

	void ResetClearVoice()
	{
		voice_high_pass.Reset();
		voice_low_pass.Reset();
		speech_env = other_env = 0.0f;
		duck = 1.0f;
	}

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

	ao->channels = channels;
	float frate = static_cast<float>(rate);
	ao->voice_high_pass.SetHighPass(frate, CLEAR_VOICE_BAND_LOW_HZ);
	ao->voice_low_pass.SetLowPass(frate, CLEAR_VOICE_BAND_HIGH_HZ);
	auto one_pole = [frate](float seconds) { return 1.0f - std::exp(-1.0f / (seconds * frate)); };
	ao->env_attack = one_pole(CLEAR_VOICE_ENV_ATTACK_S);
	ao->env_release = one_pole(CLEAR_VOICE_ENV_RELEASE_S);
	ao->duck_attack = one_pole(CLEAR_VOICE_DUCK_ATTACK_S);
	ao->duck_release = one_pole(CLEAR_VOICE_DUCK_RELEASE_S);
	ao->ResetClearVoice();

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

	float gain = audio_volume_boost.load(std::memory_order_relaxed);
	bool clear_voice = audio_clear_voice.load(std::memory_order_relaxed) && ao->channels > 0;

	if(clear_voice && !ao->clear_voice_was_on)
	{
		// Don't let filter/detector history from the last time it was on leak into the first samples.
		ao->ResetClearVoice();
	}
	ao->clear_voice_was_on = clear_voice;

	if(clear_voice)
	{
		constexpr float scale = 1.0f / 32768.0f;
		// Interleaved; only the first two channels (front L/R) are processed, any extra
		// channels just get the gain. Mono has no side, so it's speech band vs the rest only.
		uint32_t ch = ao->channels;
		bool stereo = ch >= 2;
		for(size_t i = 0; i + ch <= samples_count; i += ch)
		{
			float l = buf[i] * scale;
			float r = stereo ? buf[i + 1] * scale : l;
			float mid = (l + r) * 0.5f;
			float side = (l - r) * 0.5f;
			float speech = ao->voice_low_pass.Process(ao->voice_high_pass.Process(mid));
			float rest = mid - speech;

			float speech_mag = std::fabs(speech);
			float other_mag = std::fabs(rest) + std::fabs(side);
			ao->speech_env += (speech_mag - ao->speech_env) * (speech_mag > ao->speech_env ? ao->env_attack : ao->env_release);
			ao->other_env += (other_mag - ao->other_env) * (other_mag > ao->other_env ? ao->env_attack : ao->env_release);
			bool dialogue = ao->speech_env > CLEAR_VOICE_DETECT_FLOOR
					&& ao->speech_env > CLEAR_VOICE_DETECT_RATIO * ao->other_env;
			float duck_target = dialogue ? CLEAR_VOICE_DUCK_GAIN : 1.0f;
			ao->duck += (duck_target - ao->duck) * (duck_target < ao->duck ? ao->duck_attack : ao->duck_release);

			float mid_out = speech * CLEAR_VOICE_SPEECH_GAIN + rest * CLEAR_VOICE_REST_GAIN * ao->duck;
			float side_out = side * CLEAR_VOICE_SIDE_GAIN * ao->duck;
			if(stereo)
			{
				buf[i] = limit_to_sample((mid_out + side_out) * gain);
				buf[i + 1] = limit_to_sample((mid_out - side_out) * gain);
				for(uint32_t c = 2; c < ch; c++)
					buf[i + c] = limit_to_sample(buf[i + c] * scale * gain);
			}
			else
				buf[i] = limit_to_sample(mid_out * gain);
		}
	}
	else if(gain > 1.0f)
	{
		for(size_t i = 0; i < samples_count; i++)
			buf[i] = limit_to_sample(buf[i] / 32768.0f * gain);
	}

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
