// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <chiaki/videoreceiver.h>
#include <chiaki/session.h>

#include <string.h>
#include <chiaki/time.h>

static ChiakiErrorCode chiaki_video_receiver_flush_frame(ChiakiVideoReceiver *video_receiver);

static void add_ref_frame(ChiakiVideoReceiver *video_receiver, int32_t frame)
{
	if(video_receiver->reference_frames[0] != -1)
	{
		memmove(&video_receiver->reference_frames[1], &video_receiver->reference_frames[0], sizeof(int32_t) * 15);
		video_receiver->reference_frames[0] = frame;
		return;
	}
	for(int i=15; i>=0; i--)
	{
		if(video_receiver->reference_frames[i] == -1)
		{
			video_receiver->reference_frames[i] = frame;
			return;
		}
	}
}

static bool have_ref_frame(ChiakiVideoReceiver *video_receiver, int32_t frame)
{
	for(int i=0; i<16; i++)
		if(video_receiver->reference_frames[i] == frame)
			return true;
	return false;
}

CHIAKI_EXPORT void chiaki_video_receiver_init(ChiakiVideoReceiver *video_receiver, struct chiaki_session_t *session, ChiakiPacketStats *packet_stats)
{
	video_receiver->session = session;
	video_receiver->log = session->log;
	memset(video_receiver->profiles, 0, sizeof(video_receiver->profiles));
	video_receiver->profiles_count = 0;
	video_receiver->profile_cur = -1;

	video_receiver->frame_index_cur = -1;
	video_receiver->frame_index_prev = -1;
	video_receiver->frame_index_prev_complete = 0;

	chiaki_frame_processor_init(&video_receiver->frame_processor, video_receiver->log);
	video_receiver->packet_stats = packet_stats;

	video_receiver->frames_lost = 0;
	memset(video_receiver->reference_frames, -1, sizeof(video_receiver->reference_frames));
	memset(&video_receiver->frame_timing_cur, 0, sizeof(video_receiver->frame_timing_cur));
	memset(&video_receiver->frame_timing_flushed, 0, sizeof(video_receiver->frame_timing_flushed));
	chiaki_bitstream_init(&video_receiver->bitstream, video_receiver->log, video_receiver->session->connect_info.video_profile.codec);
}

CHIAKI_EXPORT void chiaki_video_receiver_fini(ChiakiVideoReceiver *video_receiver)
{
	for(size_t i=0; i<video_receiver->profiles_count; i++)
		free(video_receiver->profiles[i].header);
	chiaki_frame_processor_fini(&video_receiver->frame_processor);
}

CHIAKI_EXPORT void chiaki_video_receiver_stream_info(ChiakiVideoReceiver *video_receiver, ChiakiVideoProfile *profiles, size_t profiles_count)
{
	if(video_receiver->profiles_count > 0)
	{
		CHIAKI_LOGE(video_receiver->log, "Video Receiver profiles already set");
		return;
	}

	memcpy(video_receiver->profiles, profiles, profiles_count * sizeof(ChiakiVideoProfile));
	video_receiver->profiles_count = profiles_count;

	CHIAKI_LOGI(video_receiver->log, "Video Profiles:");
	for(size_t i=0; i<video_receiver->profiles_count; i++)
	{
		ChiakiVideoProfile *profile = &video_receiver->profiles[i];
		CHIAKI_LOGI(video_receiver->log, "  %zu: %ux%u", i, profile->width, profile->height);
		//chiaki_log_hexdump(video_receiver->log, CHIAKI_LOG_DEBUG, profile->header, profile->header_sz);
	}
}

CHIAKI_EXPORT void chiaki_video_receiver_av_packet(ChiakiVideoReceiver *video_receiver, ChiakiTakionAVPacket *packet)
{
	// old frame?
	ChiakiSeqNum16 frame_index = packet->frame_index;
	ChiakiErrorCode err = CHIAKI_ERR_SUCCESS;
	if(video_receiver->frame_index_cur >= 0
		&& chiaki_seq_num_16_lt(frame_index, (ChiakiSeqNum16)video_receiver->frame_index_cur))
	{
		CHIAKI_LOGW(
			video_receiver->log,
			"VIDEO_OLD_PACKET_DETAIL packet_frame=%d current_frame=%d unit=%u total=%u fec=%u data=%zu",
			(int)frame_index,
			(int)video_receiver->frame_index_cur,
			(unsigned int)packet->unit_index,
			(unsigned int)packet->units_in_frame_total,
			(unsigned int)packet->units_in_frame_fec,
			packet->data_size
		);
		return;
	}

	// check adaptive stream index
	if(video_receiver->profile_cur < 0 || video_receiver->profile_cur != packet->adaptive_stream_index)
	{
		if(packet->adaptive_stream_index >= video_receiver->profiles_count)
		{
			CHIAKI_LOGE(video_receiver->log, "Packet has invalid adaptive stream index %u >= %u",
					(unsigned int)packet->adaptive_stream_index,
					(unsigned int)video_receiver->profiles_count);
			return;
		}
		video_receiver->profile_cur = packet->adaptive_stream_index;

		ChiakiVideoProfile *profile = video_receiver->profiles + video_receiver->profile_cur;
		CHIAKI_LOGI(video_receiver->log, "Switched to profile %d, resolution: %ux%u", video_receiver->profile_cur, profile->width, profile->height);
		video_receiver->frame_timing_flushed.flush_us = 0; // header, not a frame: untimed
		if(video_receiver->session->video_sample_cb)
			video_receiver->session->video_sample_cb(profile->header, profile->header_sz, 0, false, video_receiver->session->video_sample_cb_user);
		if(!chiaki_bitstream_header(&video_receiver->bitstream, profile->header, profile->header_sz))
			CHIAKI_LOGW(video_receiver->log, "Failed to parse video header");
	}

	// next frame?
	if(video_receiver->frame_index_cur < 0 ||
		chiaki_seq_num_16_gt(frame_index, (ChiakiSeqNum16)video_receiver->frame_index_cur))
	{
		if(video_receiver->packet_stats)
			chiaki_frame_processor_report_packet_stats(&video_receiver->frame_processor, video_receiver->packet_stats);

		// last frame not flushed yet?
		if(video_receiver->frame_index_cur >= 0 && video_receiver->frame_index_prev != video_receiver->frame_index_cur)
		{
			CHIAKI_LOGW(
				video_receiver->log,
				"VIDEO_FORCED_FLUSH_DETAIL old_frame=%d new_frame=%d src=%u/%u fec=%u/%u",
				(int)video_receiver->frame_index_cur,
				(int)frame_index,
				video_receiver->frame_processor.units_source_received,
				video_receiver->frame_processor.units_source_expected,
				video_receiver->frame_processor.units_fec_received,
				video_receiver->frame_processor.units_fec_expected
			);

			err = chiaki_video_receiver_flush_frame(video_receiver);
		}

		if(err != CHIAKI_ERR_SUCCESS)
			CHIAKI_LOGW(video_receiver->log, "Video receiver could not flush frame.");

		ChiakiSeqNum16 next_frame_expected = (ChiakiSeqNum16)(video_receiver->frame_index_prev_complete + 1);
		if(chiaki_seq_num_16_gt(frame_index, next_frame_expected)
			&& !(frame_index == 1 && video_receiver->frame_index_cur < 0)) // ok for frame 1
		{
			CHIAKI_LOGW(
				video_receiver->log,
				"VIDEO_FRAME_GAP_DETAIL expected=%d got=%d current=%d prev=%d prev_complete=%d",
				(int)next_frame_expected,
				(int)frame_index,
				(int)video_receiver->frame_index_cur,
				(int)video_receiver->frame_index_prev,
				(int)video_receiver->frame_index_prev_complete
			);

			CHIAKI_LOGW(video_receiver->log, "Detected missing or corrupt frame(s) from %d to %d", next_frame_expected, (int)frame_index);
			err = stream_connection_send_corrupt_frame(&video_receiver->session->stream_connection, next_frame_expected, frame_index - 1);
			if(err != CHIAKI_ERR_SUCCESS)
				CHIAKI_LOGW(video_receiver->log, "Error sending corrupt frame.");

			// A frame entirely skipped here (not one packet of it ever arrived) never gets a
			// frame_processor instance, so the chiaki_frame_processor_report_packet_stats() call
			// above never runs for it and it silently drops out of the loss fraction that feeds
			// the server's own bitrate/resolution adaptation (see StreamConnection's "connection
			// quality" reports) — the server can end up ramping the bitrate *up* right through a
			// real gap because nothing told its congestion control there was one. Approximate
			// each skipped frame's unit count from the last known generation size (FEC layout is
			// stable frame-to-frame in practice) so this loss is at least represented, even though
			// we never received a single unit of these frames to measure them directly.
			if(video_receiver->packet_stats)
			{
				uint64_t skipped_frames = (uint64_t)(frame_index - next_frame_expected);
				uint64_t units_per_frame = video_receiver->frame_processor.units_source_expected
					+ video_receiver->frame_processor.units_fec_expected;
				if(units_per_frame > 0)
					chiaki_packet_stats_push_generation(video_receiver->packet_stats, 0, skipped_frames * units_per_frame);
			}
		}

		video_receiver->frame_index_cur = frame_index;

		// Stall tracing: remember when this frame's first packet arrived / was read / picked up.
		ChiakiTakion *takion = &video_receiver->session->stream_connection.takion;
		ChiakiVideoFrameTiming *timing = &video_receiver->frame_timing_cur;
		memset(timing, 0, sizeof(*timing));
		timing->frame_index = frame_index;
		timing->units_total = packet->units_in_frame_total;
		timing->units_fec = packet->units_in_frame_fec;
		timing->first_kernel_us = takion->cur_packet_kernel_us;
		timing->first_recv_us = takion->cur_packet_recv_us;
		timing->first_pop_us = takion->cur_packet_pop_us;

		err = chiaki_frame_processor_alloc_frame(&video_receiver->frame_processor, packet);
		if(err != CHIAKI_ERR_SUCCESS)
			CHIAKI_LOGW(video_receiver->log, "Video receiver could not allocate frame for packet.");
	}

	err = chiaki_frame_processor_put_unit(&video_receiver->frame_processor, packet);
	if(err != CHIAKI_ERR_SUCCESS)
		CHIAKI_LOGW(video_receiver->log, "Video receiver could not put unit.");

	// if we are currently building up a frame
	if(video_receiver->frame_index_cur != video_receiver->frame_index_prev)
	{
		// if we already have enough for the whole frame, flush it already
		if(chiaki_frame_processor_flush_possible(&video_receiver->frame_processor))
		    err = chiaki_video_receiver_flush_frame(video_receiver);
		if(err != CHIAKI_ERR_SUCCESS)
			CHIAKI_LOGW(video_receiver->log, "Video receiver could not flush frame.");
	}
}

static ChiakiErrorCode chiaki_video_receiver_flush_frame(ChiakiVideoReceiver *video_receiver)
{
	uint8_t *frame;
	size_t frame_size;
	ChiakiFrameProcessorFlushResult flush_result = chiaki_frame_processor_flush(&video_receiver->frame_processor, &frame, &frame_size);

	if(flush_result == CHIAKI_FRAME_PROCESSOR_FLUSH_RESULT_FAILED
		|| flush_result == CHIAKI_FRAME_PROCESSOR_FLUSH_RESULT_FEC_FAILED)
	{
		if (flush_result == CHIAKI_FRAME_PROCESSOR_FLUSH_RESULT_FEC_FAILED)
		{
		    ChiakiSeqNum16 next_frame_expected = (ChiakiSeqNum16)(video_receiver->frame_index_prev_complete + 1);

		    stream_connection_send_corrupt_frame(
		        &video_receiver->session->stream_connection,
		        next_frame_expected,
		        video_receiver->frame_index_cur
		    );

			// Do not enter waiting_for_idr here.
			// Cloud server does not appear to honour our IDR request,
			// and waiting forever causes the stream to freeze.
			CHIAKI_LOGW(
			    video_receiver->log,
			    "FEC failed for frame %d, reporting corrupt frame and continuing",
			    (int)video_receiver->frame_index_cur
			);

			video_receiver->frames_lost += video_receiver->frame_index_cur - next_frame_expected + 1;
			video_receiver->frame_index_prev = video_receiver->frame_index_cur;
			video_receiver->frame_index_prev_complete = video_receiver->frame_index_cur;
			// Track this frame as received even though FEC failed, so subsequent
			// P-frames that reference it are sent to the decoder (which uses error
			// concealment) rather than being cascade-dropped until the next I-frame.
			add_ref_frame(video_receiver, video_receiver->frame_index_cur);

			return CHIAKI_ERR_SUCCESS;
		}

		CHIAKI_LOGW(video_receiver->log, "Failed to complete frame %d", (int)video_receiver->frame_index_cur);
		return CHIAKI_ERR_UNKNOWN;
	}

	bool succ = flush_result != CHIAKI_FRAME_PROCESSOR_FLUSH_RESULT_FEC_FAILED;
	bool recovered = false;

	ChiakiBitstreamSlice slice;
	if(chiaki_bitstream_slice(&video_receiver->bitstream, frame, frame_size, &slice))
	{

	    if(slice.slice_type == CHIAKI_BITSTREAM_SLICE_P)
		{
			ChiakiSeqNum16 ref_frame_index = video_receiver->frame_index_cur - slice.reference_frame - 1;
			if(slice.reference_frame != 0xff && !have_ref_frame(video_receiver, ref_frame_index))
			{
				for(unsigned i=slice.reference_frame+1; i<16; i++)
				{
					ChiakiSeqNum16 ref_frame_index_new = video_receiver->frame_index_cur - i - 1;
					if(have_ref_frame(video_receiver, ref_frame_index_new))
					{
						if(chiaki_bitstream_slice_set_reference_frame(&video_receiver->bitstream, frame, frame_size, i))
						{
							recovered = true;
							CHIAKI_LOGW(video_receiver->log, "Missing reference frame %d for decoding frame %d -> changed to %d", (int)ref_frame_index, (int)video_receiver->frame_index_cur, (int)ref_frame_index_new);
						}
						break;
					}
				}
				if(!recovered)
				{
				    succ = false;
				    video_receiver->frames_lost++;

				    CHIAKI_LOGW(
				        video_receiver->log,
				        "Missing reference frame %d for decoding frame %d — dropping dependent frame",
				        (int)ref_frame_index,
				        (int)video_receiver->frame_index_cur
				    );

				    /*
				     * Do NOT allow this frame to become part of the future
				     * reference chain, otherwise corruption cascades forever.
				     */
					video_receiver->frame_index_prev = video_receiver->frame_index_cur;
					video_receiver->frame_index_prev_complete = video_receiver->frame_index_cur;

					return CHIAKI_ERR_SUCCESS;
				}
			}
		}
	}

	if(succ && video_receiver->session->video_sample_cb)
	{
		// Stall tracing: the packet being handled right now is the one that completed (or
		// force-flushed) this frame.
		ChiakiTakion *takion = &video_receiver->session->stream_connection.takion;
		ChiakiVideoFrameTiming *timing = &video_receiver->frame_timing_flushed;
		*timing = video_receiver->frame_timing_cur;
		timing->frame_size = frame_size;
		timing->last_kernel_us = takion->cur_packet_kernel_us;
		timing->last_recv_us = takion->cur_packet_recv_us;
		timing->last_pop_us = takion->cur_packet_pop_us;
		timing->flush_us = chiaki_time_now_monotonic_us();

		bool cb_succ = video_receiver->session->video_sample_cb(frame, frame_size, video_receiver->frames_lost, recovered, video_receiver->session->video_sample_cb_user);
		video_receiver->frames_lost = 0;
		if(!cb_succ)
		{
			succ = false;
			CHIAKI_LOGW(video_receiver->log, "Video callback did not process frame successfully.");
		}
	}
	// Always track this frame in the reference list and advance prev_complete regardless
	// of whether the decoder accepted it. If the decoder was busy, subsequent P-frames
	// would otherwise cascade-drop (missing reference) until the next I-frame. The
	// hardware decoder uses error concealment for missing reference frames, which is
	// far better than a multi-second freeze.
	add_ref_frame(video_receiver, video_receiver->frame_index_cur);
	CHIAKI_LOGV(video_receiver->log, "Added reference frame %d", (int)video_receiver->frame_index_cur);

	video_receiver->frame_index_prev = video_receiver->frame_index_cur;
	video_receiver->frame_index_prev_complete = video_receiver->frame_index_cur;

	return CHIAKI_ERR_SUCCESS;
}
