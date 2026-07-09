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
// Starts mic capture and encoding (idempotent). Returns false on failure, e.g. missing RECORD_AUDIO permission.
bool android_chiaki_audio_input_start(void *audio_input, ChiakiOpusEncoder *encoder);
void android_chiaki_audio_input_set_muted(void *audio_input, bool muted);

#ifdef __cplusplus
}
#endif

#endif //CHIAKI_JNI_AUDIO_INPUT_H
