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

sealed class FriendsResult
{
	data class Success(val friends: List<Friend>) : FriendsResult()
	data class Error(val message: String) : FriendsResult()
}
