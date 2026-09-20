// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

/**
 * Which friends' profiles (username + avatar) to download. A friend's profile costs one request
 * each, so a list of 300 friends refreshed every couple of minutes was hundreds of requests a time.
 * Profiles rarely change, so reuse the saved ones and fetch only friends not seen before — plus a
 * full refetch once [PROFILE_MAX_AGE_MS] has passed, to pick up renames and new avatars.
 */
object FriendProfilePlan
{
	const val PROFILE_MAX_AGE_MS = 24L * 60 * 60 * 1000

	fun idsToFetch(accountIds: List<String>, knownIds: Set<String>, profilesFetchedAtMs: Long, nowMs: Long): List<String>
	{
		val profilesFresh = profilesFetchedAtMs > 0 && nowMs - profilesFetchedAtMs in 0 until PROFILE_MAX_AGE_MS
		return if (profilesFresh) accountIds.filter { it !in knownIds } else accountIds
	}

	/** True when a full profile refetch happened (so the timestamp should move); false when only new friends were added. */
	fun isFullRefetch(fetchedIds: List<String>, accountIds: List<String>) = fetchedIds.size == accountIds.size
}
