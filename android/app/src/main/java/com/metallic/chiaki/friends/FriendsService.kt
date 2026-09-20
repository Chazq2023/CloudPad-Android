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

/** Sony friends/presence API client — see [PsnFriendsConstants] for host details. */
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
			if (response.statusCode == 403)
				throw Exception("Failed to fetch friends list: Please logout and back into cloudpad, if this fails, then ensure that the PS servers are up and running")
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

	/** [isBusy] is a separate top-level `availability` field Sony sends alongside
	 *  `primaryPlatformInfo.onlineStatus` — live-tested: onlineStatus stays "online" when the
	 *  account sets itself to Busy in the PS App, so isOnline alone can't distinguish them. */
	data class PresenceInfo(val isOnline: Boolean, val isBusy: Boolean, val currentGame: String, val lastOnlineDateMs: Long?)

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
			// Top-level, not under primaryPlatformInfo — confirmed against a live "Busy" account:
			// {"accountId":"...","availability":"busy","primaryPlatformInfo":{"onlineStatus":"online",...}}
			val isBusy = obj.optString("availability", "") == "busy"
			val titles = obj.optJSONArray("gameTitleInfoList")
			val titleName = if (titles != null && titles.length() > 0) titles.getJSONObject(0).optString("titleName", "") else ""
			// primaryPlatformInfo.lastOnlineDate is the per-platform figure; lastAvailableDate is
			// the top-level fallback Sony sends when there's no primaryPlatformInfo at all.
			val lastOnlineDate = primary?.optString("lastOnlineDate", "")?.ifEmpty { null }
				?: obj.optString("lastAvailableDate", "").ifEmpty { null }
			result[accountId] = PresenceInfo(isOnline, isBusy, titleName, lastOnlineDate?.let { parseIsoTimestamp(it) })
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
				put("isBusy", f.isBusy)
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
					isBusy = obj.optBoolean("isBusy", false),
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
