// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_AUDIO_INPUT_H
#define CHIAKI_JNI_AUDIO_INPUT_H

#include <chiaki/log.h>
#include <chiaki/opusencoder.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

void *android_chiaki_audio_input_new(ChiakiLog *log);
void android_chiaki_audio_input_free(void *audio_input);
// Opens and starts the mic capture stream + encode thread (idempotent). Returns false on
// failure, e.g. missing RECORD_AUDIO permission.
bool android_chiaki_audio_input_start(void *audio_input, ChiakiOpusEncoder *encoder);
// Stops and closes the capture stream (idempotent), releasing the mic hardware/OS recording
// indicator. android_chiaki_audio_input_start() can be called again later to resume.
void android_chiaki_audio_input_stop(void *audio_input);

#ifdef __cplusplus
}
#endif

#endif //CHIAKI_JNI_AUDIO_INPUT_H
