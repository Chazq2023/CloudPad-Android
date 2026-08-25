// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.util.Log
import com.metallic.chiaki.trophy.model.Trophy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the currently-streamed game's trophy list at a fixed interval for the lifetime of a
 * stream session, so [onTrophiesUnlocked] can be used to show an unlock popup the moment a new
 * trophy appears. Only ever reports trophies that transition to earned after [start] is called
 * (see [TrophyUnlockDiff]) — trophies the account already had are never reported.
 *
 * [forceRefresh] is deliberately left off the poll: TrophyRepository always fetches this game's
 * trophy detail fresh regardless, so the only thing forceRefresh would buy here is bypassing the
 * account-wide trophy titles cache, which isn't needed for an already-matched game mid-session.
 */
class TrophyUnlockWatcher(
	private val trophyRepository: TrophyRepository,
	private val gameName: String,
	private val platform: String,
	private val onTrophiesUnlocked: (List<Trophy>) -> Unit
)
{
	companion object
	{
		private const val TAG = "TrophyUnlockWatcher"
		private const val POLL_INTERVAL_MS = 30_000L
	}

	private var job: Job? = null
	private var baselineEarnedIds: Set<String>? = null

	fun start(scope: CoroutineScope)
	{
		if (job?.isActive == true) return
		Log.i(TAG, "Started polling trophies for \"$gameName\" ($platform) every ${POLL_INTERVAL_MS}ms")
		job = scope.launch {
			while (isActive)
			{
				poll()
				delay(POLL_INTERVAL_MS)
			}
		}
	}

	fun stop()
	{
		if (job != null) Log.i(TAG, "Stopped polling trophies for \"$gameName\" ($platform)")
		job?.cancel()
		job = null
	}

	private suspend fun poll()
	{
		val result = trophyRepository.fetchTrophiesForGame(gameName, platform, forceRefresh = false)
		val detail = (result as? TrophyResult.Success)?.detail
		if (detail == null)
		{
			Log.i(TAG, "Poll skipped: no trophy detail available yet for \"$gameName\" ($result)")
			return
		}

		val (updatedBaseline, newlyUnlocked) = TrophyUnlockDiff.diff(baselineEarnedIds, detail.trophies)
		val isFirstPoll = baselineEarnedIds == null
		baselineEarnedIds = updatedBaseline

		if (isFirstPoll)
		{
			Log.i(TAG, "Baseline established: ${updatedBaseline.size} already-earned trophy/trophies for \"$gameName\"")
		}
		else if (newlyUnlocked.isNotEmpty())
		{
			Log.i(TAG, "Newly unlocked: ${newlyUnlocked.joinToString { it.name }}")
			onTrophiesUnlocked(newlyUnlocked)
		}
	}
}
