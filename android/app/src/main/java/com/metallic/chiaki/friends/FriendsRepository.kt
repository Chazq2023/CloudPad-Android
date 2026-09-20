// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

import android.util.Log
import com.metallic.chiaki.common.Preferences
import com.pylux.stream.R
import com.metallic.chiaki.trophy.PsnTrophyTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Orchestrates the Friends feature: friends list + presence (short-TTL cached, mirroring
 * [com.metallic.chiaki.trophy.TrophyRepository]'s trophy-titles cache). Reuses
 * [PsnTrophyTokenManager] as-is rather than minting a separate token — the friends
 * endpoints sit under the exact same OAuth client/scope Sony issues for Trophies (see
 * [PsnFriendsConstants]).
 */
class FriendsRepository(private val preferences: Preferences)
{
	companion object
	{
		private const val TAG = "FriendsRepository"
		/** At most this many profile requests in flight at once. */
		private const val PROFILE_CONCURRENCY = 5
	}

	private val tokenManager = PsnTrophyTokenManager(preferences)

	// Doesn't change for the lifetime of a signed-in account, so a plain in-memory memoization
	// (rather than a Preferences-backed cache like the friends list itself) is enough — this
	// repository is cheaply re-created per Activity/QuickSettingsPanel instance anyway.

	suspend fun fetchFriends(forceRefresh: Boolean = false): FriendsResult = withContext(Dispatchers.IO) {
		try
		{
			if (!forceRefresh && preferences.isFriendsCacheFresh)
			{
				val cached = preferences.getCachedFriendsJson()
				if (cached != null)
				{
					val parsed = FriendsService.deserializeFriends(cached)
					if (parsed.isNotEmpty()) return@withContext FriendsResult.Success(parsed)
				}
			}

			val token = tokenManager.getValidToken()
				?: return@withContext FriendsResult.Error(preferences.getString(R.string.friends_error_auth_failed))

			val accountIds = FriendsService.fetchFriendAccountIds(token)
			if (accountIds.isEmpty())
			{
				preferences.setCachedFriendsJson(FriendsService.serializeFriends(emptyList()))
				return@withContext FriendsResult.Success(emptyList())
			}

			// Reuse saved profiles; download only friends we haven't seen (or all, once a day), and
			// never more than a few at a time.
			val savedProfiles = preferences.getCachedFriendsJson()
				?.let { FriendsService.deserializeFriends(it) }.orEmpty()
				.associate { it.accountId to (it.onlineId to it.avatarUrl) }
			val nowMs = System.currentTimeMillis()
			val toFetch = FriendProfilePlan.idsToFetch(accountIds, savedProfiles.keys, preferences.friendsProfilesFetchedAtMs, nowMs)
			val gate = kotlinx.coroutines.sync.Semaphore(PROFILE_CONCURRENCY)
			val fetchedProfiles = toFetch.map { id ->
				async { gate.withPermit { id to FriendsService.fetchProfile(token, id) } }
			}.awaitAll().toMap()
			if (FriendProfilePlan.isFullRefetch(toFetch, accountIds)) preferences.friendsProfilesFetchedAtMs = nowMs
			val profiles = accountIds.map { id -> id to (fetchedProfiles[id] ?: savedProfiles[id]) }
			val presences = FriendsService.fetchPresences(token, accountIds)

			val friends = profiles.mapNotNull { (accountId, profile) ->
				val (onlineId, avatarUrl) = profile ?: return@mapNotNull null
				val presence = presences[accountId]
				Friend(
					accountId = accountId,
					onlineId = onlineId,
					avatarUrl = avatarUrl,
					isOnline = presence?.isOnline ?: false,
					isBusy = presence?.isBusy ?: false,
					currentGame = presence?.currentGame ?: "",
					lastOnlineDateMs = presence?.lastOnlineDateMs
				)
			}.sortedWith(compareByDescending<Friend> { it.isOnline }.thenBy { it.onlineId.lowercase() })

			preferences.setCachedFriendsJson(FriendsService.serializeFriends(friends))
			FriendsResult.Success(friends)
		}
		catch (e: Exception)
		{
			Log.e(TAG, "fetchFriends failed", e)
			FriendsResult.Error(e.message ?: preferences.getString(R.string.friends_error_fetch_failed))
		}
	}
}
