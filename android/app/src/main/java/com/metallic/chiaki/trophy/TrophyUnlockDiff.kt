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
	 *
	 * Keyed by groupId+trophyId rather than trophyId alone: Sony's numeric trophyId is only
	 * guaranteed unique *within* one trophy title's own list, not globally. A collection disc's
	 * merged detail (see CollectionCatalog/TrophyRepository.fetchCollectionTrophies) can contain
	 * several bundled games' trophy lists at once, each numbered from a small range like any
	 * other title, so the same trophyId can easily appear in more than one of them — a bare-ID
	 * key would then either mask a real new unlock (already "seen" under a different game's
	 * trophy of the same number) or report the wrong trophy's name in the popup.
	 */
	fun diff(previousEarnedIds: Set<String>?, trophies: List<Trophy>): Pair<Set<String>, List<Trophy>>
	{
		fun key(trophy: Trophy) = "${trophy.groupId}:${trophy.trophyId}"

		val earnedNow = trophies.filter { it.earned }.map(::key).toSet()

		if (previousEarnedIds == null) return earnedNow to emptyList()

		val newlyEarnedKeys = earnedNow - previousEarnedIds
		if (newlyEarnedKeys.isEmpty()) return previousEarnedIds to emptyList()

		val newlyEarned = trophies.filter { key(it) in newlyEarnedKeys }
		return earnedNow to newlyEarned
	}
}
