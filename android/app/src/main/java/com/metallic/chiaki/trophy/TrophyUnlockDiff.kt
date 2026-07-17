// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.Trophy

/**
 * Pure diffing logic behind the in-stream trophy-unlock popup: compares a freshly fetched
 * trophy list's earned trophies against the set already known from the previous poll, and
 * reports only the ones that newly transitioned to earned. Pulled out from [TrophyUnlockWatcher]
 * so the "what counts as newly unlocked" rule can be unit tested without any network dependency.
 */
object TrophyUnlockDiff
{
	/**
	 * [previousEarnedIds] is null on the very first poll of a session — in that case every
	 * already-earned trophy silently becomes the baseline (the user didn't earn these during
	 * this stream, so none are reported), and only trophies earned since baseline are ever
	 * returned by later calls. Returns the updated baseline to keep, plus the newly-earned
	 * trophies to notify.
	 */
	fun diff(previousEarnedIds: Set<Int>?, trophies: List<Trophy>): Pair<Set<Int>, List<Trophy>>
	{
		val earnedNow = trophies.filter { it.earned }.map { it.trophyId }.toSet()

		if (previousEarnedIds == null) return earnedNow to emptyList()

		val newlyEarnedIds = earnedNow - previousEarnedIds
		if (newlyEarnedIds.isEmpty()) return previousEarnedIds to emptyList()

		val newlyEarned = trophies.filter { it.trophyId in newlyEarnedIds }
		return earnedNow to newlyEarned
	}
}
