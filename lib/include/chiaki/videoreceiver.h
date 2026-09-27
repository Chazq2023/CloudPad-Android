// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_VIDEORECEIVER_H
#define CHIAKI_VIDEORECEIVER_H

#include "common.h"
#include "log.h"
#include "video.h"
#include "takion.h"
#include "frameprocessor.h"
#include "bitstream.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CHIAKI_VIDEO_PROFILES_MAX 8

/**
 * Stall tracing for one video frame (CLOCK_MONOTONIC µs, see ChiakiTakion.cur_packet_*_us): when
 * its first packet and the packet that completed it reached the device (kernel), were read by the
 * recv thread, and were picked up by the takion thread, plus when the whole frame was handed to the
 * video sample callback. flush_us == 0 means no timing (e.g. the codec header).
 */
typedef struct chiaki_video_frame_timing_t
{
	int32_t frame_index;
	size_t frame_size;
	uint16_t units_total;
	uint16_t units_fec;
	uint64_t first_kernel_us, first_recv_us, first_pop_us;
	uint64_t last_kernel_us, last_recv_us, last_pop_us;
	uint64_t flush_us;
} ChiakiVideoFrameTiming;

typedef struct chiaki_video_receiver_t
{
	struct chiaki_session_t *session;
	ChiakiLog *log;
	ChiakiVideoProfile profiles[CHIAKI_VIDEO_PROFILES_MAX];
	size_t profiles_count;
	int profile_cur; // < 1 if no profile selected yet, else index in profiles

	int32_t frame_index_cur; // frame that is currently being filled
	int32_t frame_index_prev; // last frame that has been at least partially decoded
	int32_t frame_index_prev_complete; // last frame that has been completely decoded
	ChiakiFrameProcessor frame_processor;
	ChiakiPacketStats *packet_stats;

	int32_t frames_lost;
	int32_t reference_frames[16];
	ChiakiBitstream bitstream;

	ChiakiVideoFrameTiming frame_timing_cur; // frame currently being filled
	ChiakiVideoFrameTiming frame_timing_flushed; // valid inside video_sample_cb for the frame being passed
} ChiakiVideoReceiver;

CHIAKI_EXPORT void chiaki_video_receiver_init(ChiakiVideoReceiver *video_receiver, struct chiaki_session_t *session, ChiakiPacketStats *packet_stats);
CHIAKI_EXPORT void chiaki_video_receiver_fini(ChiakiVideoReceiver *video_receiver);

/**
 * Called after receiving the Stream Info Packet.
 *
 * @param video_receiver
 * @param profiles Array of profiles. Ownership of the contained header buffers will be transferred to the ChiakiVideoReceiver!
 * @param profiles_count must be <= CHIAKI_VIDEO_PROFILES_MAX
 */
CHIAKI_EXPORT void chiaki_video_receiver_stream_info(ChiakiVideoReceiver *video_receiver, ChiakiVideoProfile *profiles, size_t profiles_count);

CHIAKI_EXPORT void chiaki_video_receiver_av_packet(ChiakiVideoReceiver *video_receiver, ChiakiTakionAVPacket *packet);

static inline ChiakiVideoReceiver *chiaki_video_receiver_new(struct chiaki_session_t *session, ChiakiPacketStats *packet_stats)
{
	ChiakiVideoReceiver *video_receiver = CHIAKI_NEW(ChiakiVideoReceiver);
	if(!video_receiver)
		return NULL;
	chiaki_video_receiver_init(video_receiver, session, packet_stats);
	return video_receiver;
}

static inline void chiaki_video_receiver_free(ChiakiVideoReceiver *video_receiver)
{
	if(!video_receiver)
		return;
	chiaki_video_receiver_fini(video_receiver);
	free(video_receiver);
}

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_VIDEORECEIVER_H
