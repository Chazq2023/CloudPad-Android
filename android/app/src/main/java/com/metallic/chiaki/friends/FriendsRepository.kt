// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

import android.util.Log
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.trophy.PsnTrophyTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Orchestrates the Friends feature: friends list + presence (short-TTL cached, mirroring
 * [com.metallic.chiaki.trophy.TrophyRepository]'s trophy-titles cache) and 1:1 messaging. Reuses
 * [PsnTrophyTokenManager] as-is rather than minting a separate token — the friends/messaging
 * endpoints sit under the exact same OAuth client/scope Sony issues for Trophies (see
 * [PsnFriendsConstants]).
 */
class FriendsRepository(private val preferences: Preferences)
{
	companion object
	{
		private const val TAG = "FriendsRepository"
	}

	private val tokenManager = PsnTrophyTokenManager(preferences)

	// Doesn't change for the lifetime of a signed-in account, so a plain in-memory memoization
	// (rather than a Preferences-backed cache like the friends list itself) is enough — this
	// repository is cheaply re-created per Activity/QuickSettingsPanel instance anyway.
	private var cachedMyAccountId: String? = null

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
				?: return@withContext FriendsResult.Error("Could not authenticate with PSN for friends data")

			val accountIds = FriendsService.fetchFriendAccountIds(token)
			if (accountIds.isEmpty())
			{
				preferences.setCachedFriendsJson(FriendsService.serializeFriends(emptyList()))
				return@withContext FriendsResult.Success(emptyList())
			}

			val profiles = accountIds.map { id -> async { id to FriendsService.fetchProfile(token, id) } }.awaitAll()
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
			FriendsResult.Error(e.message ?: "Failed to fetch friends")
		}
	}

	/** Resolves (creating if necessary) the 1:1 DM group with [friendAccountId] and loads its
	 *  conversation so far. */
	suspend fun openConversation(friendAccountId: String): ConversationResult = withContext(Dispatchers.IO) {
		try
		{
			val token = tokenManager.getValidToken()
				?: return@withContext ConversationResult.Error("Could not authenticate with PSN for messaging")

			val groupId = FriendsService.createOrGetDmGroup(token, friendAccountId)
				?: return@withContext ConversationResult.Error("Could not start a conversation with this friend")

			val myAccountId = resolveMyAccountId(token)
			val messages = FriendsService.fetchConversation(token, groupId, myAccountId)
				// null means the fetch itself failed — distinct from a real, empty conversation.
				// Reporting that as Error rather than Success(emptyList()) matters to callers like
				// the chat UI's optimistic-send path, which otherwise can't tell "nothing to show
				// yet" apart from "couldn't check", and would wipe an unconfirmed sent message.
				// groupId is passed through regardless — it was already successfully created above,
				// so a history-fetch failure shouldn't also block the user from sending.
				?: return@withContext ConversationResult.Error("Could not load the conversation history", groupId)
			ConversationResult.Success(groupId, messages)
		}
		catch (e: Exception)
		{
			Log.e(TAG, "openConversation failed for $friendAccountId", e)
			ConversationResult.Error(e.message ?: "Failed to open conversation")
		}
	}

	/** Re-fetches an already-open conversation's messages — used after sending, and for a manual
	 *  refresh. Always fetched fresh, same reasoning as per-game trophy detail never being cached. */
	suspend fun refreshConversation(groupId: String): ConversationResult = withContext(Dispatchers.IO) {
		try
		{
			val token = tokenManager.getValidToken()
				?: return@withContext ConversationResult.Error("Could not authenticate with PSN for messaging")
			val myAccountId = resolveMyAccountId(token)
			val messages = FriendsService.fetchConversation(token, groupId, myAccountId)
				?: return@withContext ConversationResult.Error("Could not refresh the conversation")
			ConversationResult.Success(groupId, messages)
		}
		catch (e: Exception)
		{
			Log.e(TAG, "refreshConversation failed for $groupId", e)
			ConversationResult.Error(e.message ?: "Failed to refresh conversation")
		}
	}

	suspend fun sendMessage(groupId: String, body: String): Boolean = withContext(Dispatchers.IO) {
		val token = tokenManager.getValidToken() ?: return@withContext false
		FriendsService.sendMessage(token, groupId, body)
	}

	private suspend fun resolveMyAccountId(token: String): String?
	{
		cachedMyAccountId?.let { return it }
		val id = FriendsService.fetchMyAccountId(token)
		if (id != null) cachedMyAccountId = id
		return id
	}
}
