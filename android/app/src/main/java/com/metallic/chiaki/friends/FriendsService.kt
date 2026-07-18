// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

import android.util.Log
import com.metallic.chiaki.cloudplay.api.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Sony friends/presence/messaging API client — see [PsnFriendsConstants] for host details. */
object FriendsService
{
	private const val TAG = "FriendsService"

	suspend fun fetchFriendAccountIds(accessToken: String): List<String>
	{
		val url = "${PsnFriendsConstants.PROFILE_BASE}/me/friends?limit=1000"
		val response = HttpClient.get(
			url = url,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)
		if (response.statusCode != 200)
		{
			Log.e(TAG, "fetchFriendAccountIds failed: ${response.statusCode} - ${response.body}")
			throw Exception("Failed to fetch friends list: HTTP ${response.statusCode}")
		}
		val json = JSONObject(response.body)
		val arr = json.optJSONArray("friends") ?: JSONArray()
		return (0 until arr.length()).map { arr.getString(it) }
	}

	/** Returns (onlineId, avatarUrl), or null if the profile couldn't be fetched. Explicitly
	 *  requests the `avatars` field — PSN's profile endpoints are commonly opt-in per field, so
	 *  omitting this was silently returning a payload with no avatar data at all. */
	suspend fun fetchProfile(accessToken: String, accountId: String): Pair<String, String>?
	{
		val url = "${PsnFriendsConstants.PROFILE_BASE}/$accountId/profiles?fields=onlineId,avatars"
		val response = HttpClient.get(
			url = url,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)
		if (response.statusCode != 200)
		{
			Log.w(TAG, "fetchProfile failed for $accountId: ${response.statusCode} - ${response.body}")
			return null
		}

		val json = JSONObject(response.body)
		val onlineId = json.optString("onlineId", "")
		val avatars = json.optJSONArray("avatars") ?: JSONArray()
		var avatarUrl = ""
		for (i in 0 until avatars.length())
		{
			val avatar = avatars.getJSONObject(i)
			if (avatar.optString("size") == "m")
			{
				avatarUrl = avatar.optString("url", "")
				break
			}
		}
		if (avatarUrl.isEmpty() && avatars.length() > 0) avatarUrl = avatars.getJSONObject(0).optString("url", "")
		// Live-tested: Sony serves these as plain http:// — Android blocks cleartext traffic by
		// default (API 28+), so Coil was silently failing to load every avatar. The same CDN
		// hosts serve https fine, so upgrading the scheme here is a safe, low-risk fix.
		if (avatarUrl.startsWith("http://")) avatarUrl = "https://" + avatarUrl.removePrefix("http://")
		return onlineId to avatarUrl
	}

	data class PresenceInfo(val isOnline: Boolean, val currentGame: String, val lastOnlineDateMs: Long?)

	/** Presence for every id in [accountIds], batched into a single call. */
	suspend fun fetchPresences(accessToken: String, accountIds: List<String>): Map<String, PresenceInfo>
	{
		if (accountIds.isEmpty()) return emptyMap()

		val idsParam = URLEncoder.encode(accountIds.joinToString(","), "UTF-8")
		val platformsParam = URLEncoder.encode("PS4,PS5,MOBILE_APP,PSPC", "UTF-8")
		// Live-tested: Sony rejects this call without a `type` param ("Bad Request (query: type)").
		// "primary" isn't independently confirmed — best-effort guess pending verification against
		// a real response; if this is still wrong, the body logged below on failure will show
		// whatever the actual validation error is next time.
		val url = "${PsnFriendsConstants.PROFILE_BASE_V2}/basicPresences" +
			"?accountIds=$idsParam&platforms=$platformsParam&withOwnGameTitleInfo=true&type=primary"
		val response = HttpClient.get(
			url = url,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)
		if (response.statusCode != 200)
		{
			Log.w(TAG, "fetchPresences failed: ${response.statusCode} - ${response.body}")
			return emptyMap()
		}

		val json = JSONObject(response.body)
		val arr = json.optJSONArray("basicPresences") ?: JSONArray()
		val result = mutableMapOf<String, PresenceInfo>()
		for (i in 0 until arr.length())
		{
			val obj = arr.getJSONObject(i)
			val accountId = obj.optString("accountId", "")
			if (accountId.isEmpty()) continue

			val primary = obj.optJSONObject("primaryPlatformInfo")
			val isOnline = primary?.optString("onlineStatus", "offline") == "online"
			val titles = obj.optJSONArray("gameTitleInfoList")
			val titleName = if (titles != null && titles.length() > 0) titles.getJSONObject(0).optString("titleName", "") else ""
			// primaryPlatformInfo.lastOnlineDate is the per-platform figure; lastAvailableDate is
			// the top-level fallback Sony sends when there's no primaryPlatformInfo at all.
			val lastOnlineDate = primary?.optString("lastOnlineDate", "")?.ifEmpty { null }
				?: obj.optString("lastAvailableDate", "").ifEmpty { null }
			result[accountId] = PresenceInfo(isOnline, titleName, lastOnlineDate?.let { parseIsoTimestamp(it) })
		}
		return result
	}

	private fun parseIsoTimestamp(value: String): Long?
	{
		if (value.isEmpty()) return null
		// Sony's presence dates include milliseconds (unlike trophy earnedDateTime) — fall back to
		// the no-millis format defensively in case that ever isn't the case.
		val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
		for (pattern in patterns)
		{
			try
			{
				val format = SimpleDateFormat(pattern, Locale.US)
				format.timeZone = TimeZone.getTimeZone("UTC")
				return format.parse(value)?.time
			}
			catch (e: Exception) { /* try next pattern */ }
		}
		Log.w(TAG, "Failed to parse presence timestamp: $value")
		return null
	}

	/** Resolves our own PSN accountId — a different API family (device-account lookup) from
	 *  everything else here, used only to tell our own messages apart from the friend's in
	 *  [fetchConversation]. */
	suspend fun fetchMyAccountId(accessToken: String): String?
	{
		val response = HttpClient.get(
			url = PsnFriendsConstants.MY_ACCOUNT_URL,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)
		if (response.statusCode != 200)
		{
			Log.w(TAG, "fetchMyAccountId failed: ${response.statusCode} - ${response.body}")
			return null
		}
		return JSONObject(response.body).optString("accountId", "").ifEmpty { null }
	}

	/** Creates a 1:1 DM group with [friendAccountId] — assumed (not confirmed against a live
	 *  account) to return the same existing groupId if a conversation already exists for this
	 *  pair, matching how Sony's own apps present a stable 1:1 thread rather than a new one per
	 *  message. Worth confirming manually the first time this is used. */
	suspend fun createOrGetDmGroup(accessToken: String, friendAccountId: String): String?
	{
		val body = JSONObject().apply {
			put("invitees", JSONArray().put(JSONObject().put("accountId", friendAccountId)))
		}.toString()

		val response = HttpClient.post(
			url = "${PsnFriendsConstants.GAMING_LOUNGE_BASE}/groups",
			body = body,
			headers = mapOf(
				"Authorization" to "Bearer $accessToken",
				"Content-Type" to "application/json",
				"Accept" to "application/json",
				"Accept-Language" to "en-US"
			)
		)
		if (response.statusCode !in 200..299)
		{
			Log.e(TAG, "createOrGetDmGroup failed for $friendAccountId: ${response.statusCode} - ${response.body}")
			return null
		}
		return JSONObject(response.body).optString("groupId", "").ifEmpty { null }
	}

	/** `/groups/{id}/threads/{id}/messages` (no `members/me/` prefix) live-tested as POST-only —
	 *  GET on that exact path returns 405. Reverted to the `members/me/`-prefixed shape
	 *  [sendMessage] doesn't use, the only other source-backed candidate; body-logged on failure
	 *  below so a still-wrong path surfaces Sony's actual error text rather than a bare status.
	 *  Returns null (not an empty list) on failure — distinguishing "fetch failed" from
	 *  "genuinely no messages yet" matters to the caller, see [FriendsRepository]. Requires an
	 *  Accept-Language header — live-tested: Sony rejects this specific call without one. */
	suspend fun fetchConversation(accessToken: String, groupId: String, myAccountId: String?, limit: Int = 20): List<ChatMessage>?
	{
		val encodedGroupId = URLEncoder.encode(groupId, "UTF-8")
		val url = "${PsnFriendsConstants.GAMING_LOUNGE_BASE}/members/me/groups/$encodedGroupId/threads/$encodedGroupId/messages?limit=$limit"
		val response = HttpClient.get(
			url = url,
			headers = mapOf(
				"Authorization" to "Bearer $accessToken",
				"Accept" to "application/json",
				"Accept-Language" to "en-US"
			)
		)
		if (response.statusCode != 200)
		{
			Log.w(TAG, "fetchConversation failed for $groupId: ${response.statusCode} - ${response.body}")
			return null
		}

		val json = JSONObject(response.body)
		val arr = json.optJSONArray("messages") ?: JSONArray()
		val messages = mutableListOf<ChatMessage>()
		for (i in 0 until arr.length())
		{
			val obj = arr.getJSONObject(i)
			val text = obj.optString("body", "")
			if (text.isEmpty()) continue // skip non-text messages (images etc.) for now
			val senderAccountId = obj.optJSONObject("sender")?.optString("accountId", "") ?: ""
			val timestampMs = obj.optString("createdTimestamp", "0").toLongOrNull() ?: 0L
			messages.add(ChatMessage(text, senderAccountId, senderAccountId.isNotEmpty() && senderAccountId == myAccountId, timestampMs))
		}
		return messages.sortedBy { it.timestampMs }
	}

	suspend fun sendMessage(accessToken: String, groupId: String, body: String): Boolean
	{
		val payload = JSONObject().apply {
			put("messageType", 1)
			put("body", body)
		}.toString()

		val encodedGroupId = URLEncoder.encode(groupId, "UTF-8")
		val response = HttpClient.post(
			url = "${PsnFriendsConstants.GAMING_LOUNGE_BASE}/groups/$encodedGroupId/threads/$encodedGroupId/messages",
			body = payload,
			headers = mapOf(
				"Authorization" to "Bearer $accessToken",
				"Content-Type" to "application/json",
				"Accept" to "application/json",
				"Accept-Language" to "en-US"
			)
		)
		if (response.statusCode !in 200..299)
		{
			Log.e(TAG, "sendMessage failed for $groupId: ${response.statusCode} - ${response.body}")
			return false
		}
		return true
	}

	/** Serializes a friends list for short-TTL caching in [com.metallic.chiaki.common.Preferences]. */
	fun serializeFriends(friends: List<Friend>): String
	{
		val array = JSONArray()
		friends.forEach { f ->
			array.put(JSONObject().apply {
				put("accountId", f.accountId)
				put("onlineId", f.onlineId)
				put("avatarUrl", f.avatarUrl)
				put("isOnline", f.isOnline)
				put("currentGame", f.currentGame)
				put("lastOnlineDateMs", f.lastOnlineDateMs ?: -1L)
			})
		}
		return array.toString()
	}

	/** Inverse of [serializeFriends]. Returns an empty list if the cached JSON is malformed. */
	fun deserializeFriends(json: String): List<Friend>
	{
		return try
		{
			val array = JSONArray(json)
			(0 until array.length()).map { i ->
				val obj = array.getJSONObject(i)
				val lastOnline = obj.optLong("lastOnlineDateMs", -1L)
				Friend(
					accountId = obj.optString("accountId", ""),
					onlineId = obj.optString("onlineId", ""),
					avatarUrl = obj.optString("avatarUrl", ""),
					isOnline = obj.optBoolean("isOnline", false),
					currentGame = obj.optString("currentGame", ""),
					lastOnlineDateMs = if (lastOnline >= 0) lastOnline else null
				)
			}
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Failed to deserialize cached friends", e)
			emptyList()
		}
	}
}
