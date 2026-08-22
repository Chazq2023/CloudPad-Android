// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

data class Friend(
	val accountId: String,
	val onlineId: String,
	val avatarUrl: String,
	val isOnline: Boolean,
	/** Separate from [isOnline] — Sony's own "Busy" PS App status, distinct from being connected
	 *  at all (see FriendsService.PresenceInfo). */
	val isBusy: Boolean,
	val currentGame: String,
	/** Epoch millis, or null if never reported — formatted relative-to-now at display time
	 *  (see FriendAdapter), same as how TrophyAdapter formats a trophy's earnedDateTimeMs. */
	val lastOnlineDateMs: Long?
)

data class ChatMessage(
	val body: String,
	val senderAccountId: String,
	val isMine: Boolean,
	val timestampMs: Long
)

sealed class FriendsResult
{
	data class Success(val friends: List<Friend>) : FriendsResult()
	data class Error(val message: String) : FriendsResult()
}

sealed class ConversationResult
{
	data class Success(val groupId: String, val messages: List<ChatMessage>) : ConversationResult()
	/** [groupId] is non-null when the DM group itself was created/resolved fine and only the
	 *  history fetch failed — callers should still capture it so sending can proceed even though
	 *  history couldn't be loaded, rather than losing the group entirely. */
	data class Error(val message: String, val groupId: String? = null) : ConversationResult()
}
