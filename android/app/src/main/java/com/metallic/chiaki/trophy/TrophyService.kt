// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.util.Log
import com.metallic.chiaki.cloudplay.api.HttpClient
import com.metallic.chiaki.trophy.model.Trophy
import com.metallic.chiaki.trophy.model.TrophyAccountSummary
import com.metallic.chiaki.trophy.model.TrophyCounts
import com.metallic.chiaki.trophy.model.TrophyGroup
import com.metallic.chiaki.trophy.model.TrophyTitleDetail
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import com.metallic.chiaki.trophy.model.TrophyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Sony Trophies API client — see [PsnTrophyConstants] for the OAuth/host details. */
object TrophyService
{
	private const val TAG = "TrophyService"
	private const val PAGE_SIZE = 100

	private data class TrophyTitlesPage(val titles: List<TrophyTitleSummary>, val totalItemCount: Int)

	private fun fetchTrophyTitlesPage(accessToken: String, accountId: String, offset: Int): TrophyTitlesPage
	{
		val url = "${PsnTrophyConstants.TROPHY_BASE}/users/$accountId/trophyTitles?limit=$PAGE_SIZE&offset=$offset"
		val response = HttpClient.get(
			url = url,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)

		if (response.statusCode != 200)
		{
			Log.e(TAG, "fetchAllTrophyTitles failed for $accountId at offset=$offset: ${response.statusCode} - ${response.body}")
			if (response.statusCode == 403)
				throw Exception("Failed to fetch trophy titles: Please logout and back into cloudpad, if this fails, then ensure that the PS servers are up and running")
			throw Exception("Failed to fetch trophy titles: HTTP ${response.statusCode}")
		}

		val json = JSONObject(response.body)
		val titlesArray = json.optJSONArray("trophyTitles") ?: JSONArray()
		val titles = (0 until titlesArray.length()).mapNotNull { parseTrophyTitleSummary(titlesArray.getJSONObject(it)) }
		val totalItemCount = json.optInt("totalItemCount", offset + titles.size)
		return TrophyTitlesPage(titles, totalItemCount)
	}

	/** All trophy titles (games with trophy data) for [accountId] ("me" by default), across every
	 *  page — parameterised so [TrophyCompareRepository] can fetch a friend's titles through the
	 *  exact same call, just with their accountId instead of "me". The first page's response tells
	 *  us the total count, so every remaining page is known upfront and fetched concurrently rather
	 *  than one-at-a-time — with 200+ titles (2-3 pages) on a real account, this turns what was a
	 *  chain of sequential round trips into a single one plus one parallel batch. */
	suspend fun fetchAllTrophyTitles(accessToken: String, accountId: String = "me"): List<TrophyTitleSummary> = coroutineScope {
		val firstPage = async(Dispatchers.IO) { fetchTrophyTitlesPage(accessToken, accountId, offset = 0) }.await()

		val remainingOffsets = if (firstPage.titles.isEmpty())
			emptyList()
		else
			(PAGE_SIZE until firstPage.totalItemCount step PAGE_SIZE).toList()

		val remainingPages = remainingOffsets
			.map { offset -> async(Dispatchers.IO) { fetchTrophyTitlesPage(accessToken, accountId, offset) } }
			.awaitAll()

		val all = firstPage.titles + remainingPages.flatMap { it.titles }
		Log.i(TAG, "fetchAllTrophyTitles($accountId): ${all.size} titles")
		all
	}

	/** Overall account-level trophy stats (level + total earned counts) — the same figure PSN
	 *  shows on a profile. Returns null on failure (including a friend's data being private),
	 *  distinct from a genuinely empty result, same reasoning as FriendsService.fetchConversation. */
	suspend fun fetchTrophySummary(accessToken: String, accountId: String = "me"): TrophyAccountSummary?
	{
		val url = "${PsnTrophyConstants.TROPHY_BASE}/users/$accountId/trophySummary"
		val response = HttpClient.get(
			url = url,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)
		if (response.statusCode != 200)
		{
			Log.w(TAG, "fetchTrophySummary failed for $accountId: ${response.statusCode} - ${response.body}")
			return null
		}

		val json = JSONObject(response.body)
		// Field name isn't independently confirmed against a live response yet — "trophyLevel" is
		// the more common name across PSN API projects, "level" a fallback if that's ever wrong.
		val level = json.optInt("trophyLevel", json.optInt("level", 0))
		if (level == 0) Log.d(TAG, "fetchTrophySummary $accountId: no recognized level field in response body: ${response.body}")
		return TrophyAccountSummary(
			level = level,
			progressPercent = json.optInt("progress", 0),
			earnedTrophies = parseTrophyCounts(json.optJSONObject("earnedTrophies"))
		)
	}

	/** Just the "m"-size avatar URL from a PSN profile — used only to show "your" avatar in trophy
	 *  comparison. A small deliberate duplicate of the parsing FriendsService.fetchProfile already
	 *  does (that lives in the `friends` package, which already depends on `trophy` for token
	 *  reuse — importing it back here would make that a circular package dependency for one field). */
	suspend fun fetchAvatarUrl(accessToken: String, accountId: String = "me"): String
	{
		// Unlike trophyTitles/friends, this profile endpoint live-tested as rejecting the literal
		// "me" in the path ("Bad Request (path: accountId)") — needs the real resolved id first.
		val resolvedAccountId = if (accountId == "me") resolveMyAccountId(accessToken) ?: return "" else accountId
		val url = "https://m.np.playstation.com/api/userProfile/v1/internal/users/$resolvedAccountId/profiles?fields=avatars"
		val response = HttpClient.get(
			url = url,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)
		if (response.statusCode != 200)
		{
			Log.w(TAG, "fetchAvatarUrl failed for $accountId: ${response.statusCode} - ${response.body}")
			return ""
		}

		val avatars = JSONObject(response.body).optJSONArray("avatars") ?: JSONArray()
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
		// Sony serves these as plain http:// — Android blocks cleartext traffic by default, same
		// fix already applied in FriendsService.fetchProfile.
		if (avatarUrl.startsWith("http://")) avatarUrl = "https://" + avatarUrl.removePrefix("http://")
		return avatarUrl
	}

	/** Same device-account lookup FriendsService.fetchMyAccountId already uses to resolve our own
	 *  accountId — duplicated here for the same "avoid a circular package dependency" reasoning. */
	private suspend fun resolveMyAccountId(accessToken: String): String?
	{
		val response = HttpClient.get(
			url = "https://dms.api.playstation.com/api/v1/devices/accounts/me",
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)
		if (response.statusCode != 200)
		{
			Log.w(TAG, "resolveMyAccountId failed: ${response.statusCode} - ${response.body}")
			return null
		}
		return JSONObject(response.body).optString("accountId", "").ifEmpty { null }
	}

	/** Full detail (groups + every trophy, earned state merged in) for one trophy title. Each
	 *  group's trophies are independent of every other group's, so a title with several trophy
	 *  groups (base game + DLC packs) fetches them all concurrently rather than one at a time. */
	suspend fun fetchTrophyTitleDetail(accessToken: String, summary: TrophyTitleSummary): TrophyTitleDetail = coroutineScope {
		val groups = fetchTrophyGroups(accessToken, summary)

		val trophies = if (groups.isEmpty())
		{
			fetchTrophiesForGroup(accessToken, summary, "default")
		}
		else
		{
			groups
				.map { group -> async(Dispatchers.IO) { fetchTrophiesForGroup(accessToken, summary, group.groupId) } }
				.awaitAll()
				.flatten()
		}

		TrophyTitleDetail(summary, groups, trophies)
	}

	private suspend fun fetchTrophyGroups(accessToken: String, summary: TrophyTitleSummary): List<TrophyGroup>
	{
		if (!summary.hasTrophyGroups) return emptyList()

		val url = "${PsnTrophyConstants.TROPHY_BASE}/users/me/npCommunicationIds/${summary.npCommunicationId}/trophyGroups" +
			"?npServiceName=${summary.npServiceName}"
		val response = HttpClient.get(
			url = url,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)

		if (response.statusCode != 200)
		{
			Log.w(TAG, "fetchTrophyGroups failed for ${summary.npCommunicationId}: ${response.statusCode}")
			return emptyList()
		}

		val json = JSONObject(response.body)
		val groupsArray = json.optJSONArray("trophyGroups") ?: JSONArray()
		val groups = mutableListOf<TrophyGroup>()
		for (i in 0 until groupsArray.length())
		{
			val obj = groupsArray.getJSONObject(i)
			groups.add(
				TrophyGroup(
					groupId = obj.optString("trophyGroupId", "default"),
					groupName = obj.optString("trophyGroupName", ""),
					groupIconUrl = obj.optString("trophyGroupIconUrl", ""),
					definedTrophies = parseTrophyCounts(obj.optJSONObject("definedTrophies")),
					earnedTrophies = parseTrophyCounts(obj.optJSONObject("earnedTrophies"))
				)
			)
		}
		return groups
	}

	/**
	 * Sony splits trophy data across two calls that must be merged client-side by trophyId:
	 * the account-scoped endpoint (`users/me/...`) only returns per-user earned status/stats
	 * (id, hidden, type, earned, rarity) — the actual name/description/icon only comes back
	 * from the un-scoped title-definition endpoint (no `users/{accountId}` prefix).
	 */
	private suspend fun fetchTrophiesForGroup(
		accessToken: String,
		summary: TrophyTitleSummary,
		groupId: String
	): List<Trophy> = coroutineScope {
		val definitionsDeferred = async(Dispatchers.IO) { fetchTrophyDefinitions(accessToken, summary, groupId) }
		val earnedStatusDeferred = async(Dispatchers.IO) { fetchTrophyEarnedStatus(accessToken, summary, groupId) }
		val definitions = definitionsDeferred.await()
		val earnedStatus = earnedStatusDeferred.await()

		if (definitions.isEmpty()) return@coroutineScope earnedStatus.values.toList()

		definitions.map { (trophyId, definition) ->
			val status = earnedStatus[trophyId]
			definition.copy(
				earned = status?.earned ?: definition.earned,
				earnedDateTimeMs = status?.earnedDateTimeMs ?: definition.earnedDateTimeMs,
				// The definitions endpoint doesn't know per-user hidden state resolution edge
				// cases as reliably as the account endpoint, so prefer it when present.
				hidden = status?.hidden ?: definition.hidden
			)
		}
	}

	private suspend fun fetchTrophyDefinitions(
		accessToken: String,
		summary: TrophyTitleSummary,
		groupId: String
	): Map<Int, Trophy>
	{
		val url = "${PsnTrophyConstants.TROPHY_BASE}/npCommunicationIds/${summary.npCommunicationId}" +
			"/trophyGroups/$groupId/trophies?npServiceName=${summary.npServiceName}"
		val response = HttpClient.get(
			url = url,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)

		if (response.statusCode != 200)
		{
			Log.w(TAG, "fetchTrophyDefinitions failed for ${summary.npCommunicationId}/$groupId: ${response.statusCode}")
			return emptyMap()
		}

		val json = JSONObject(response.body)
		val trophiesArray = json.optJSONArray("trophies") ?: JSONArray()
		val result = mutableMapOf<Int, Trophy>()
		for (i in 0 until trophiesArray.length())
		{
			val trophy = parseTrophy(trophiesArray.getJSONObject(i))
			if (trophy != null) result[trophy.trophyId] = trophy
		}
		return result
	}

	private suspend fun fetchTrophyEarnedStatus(
		accessToken: String,
		summary: TrophyTitleSummary,
		groupId: String
	): Map<Int, Trophy>
	{
		val url = "${PsnTrophyConstants.TROPHY_BASE}/users/me/npCommunicationIds/${summary.npCommunicationId}" +
			"/trophyGroups/$groupId/trophies?npServiceName=${summary.npServiceName}"
		val response = HttpClient.get(
			url = url,
			headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
		)

		if (response.statusCode != 200)
		{
			Log.w(TAG, "fetchTrophyEarnedStatus failed for ${summary.npCommunicationId}/$groupId: ${response.statusCode}")
			return emptyMap()
		}

		val json = JSONObject(response.body)
		val trophiesArray = json.optJSONArray("trophies") ?: JSONArray()
		val result = mutableMapOf<Int, Trophy>()
		for (i in 0 until trophiesArray.length())
		{
			val trophy = parseTrophy(trophiesArray.getJSONObject(i))
			if (trophy != null) result[trophy.trophyId] = trophy
		}
		return result
	}

	/** Serializes a trophy titles list for caching in [com.metallic.chiaki.common.Preferences]. */
	fun serializeTitles(titles: List<TrophyTitleSummary>): String
	{
		val array = JSONArray()
		titles.forEach { title ->
			array.put(JSONObject().apply {
				put("npCommunicationId", title.npCommunicationId)
				put("npServiceName", title.npServiceName)
				put("trophyTitleName", title.trophyTitleName)
				put("trophyTitleIconUrl", title.trophyTitleIconUrl)
				put("trophyTitlePlatform", title.trophyTitlePlatform)
				put("hasTrophyGroups", title.hasTrophyGroups)
				put("definedTrophies", JSONObject().apply {
					put("bronze", title.definedTrophies.bronze)
					put("silver", title.definedTrophies.silver)
					put("gold", title.definedTrophies.gold)
					put("platinum", title.definedTrophies.platinum)
				})
				put("earnedTrophies", JSONObject().apply {
					put("bronze", title.earnedTrophies.bronze)
					put("silver", title.earnedTrophies.silver)
					put("gold", title.earnedTrophies.gold)
					put("platinum", title.earnedTrophies.platinum)
				})
				put("progress", title.progressPercent)
			})
		}
		return array.toString()
	}

	/** Inverse of [serializeTitles]. Returns an empty list if the cached JSON is malformed. */
	fun deserializeTitles(json: String): List<TrophyTitleSummary>
	{
		return try
		{
			val array = JSONArray(json)
			(0 until array.length()).mapNotNull { parseTrophyTitleSummary(array.getJSONObject(it)) }
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Failed to deserialize cached trophy titles", e)
			emptyList()
		}
	}

	private fun parseTrophyTitleSummary(obj: JSONObject): TrophyTitleSummary?
	{
		val npCommunicationId = obj.optString("npCommunicationId", "")
		if (npCommunicationId.isEmpty()) return null

		return TrophyTitleSummary(
			npCommunicationId = npCommunicationId,
			npServiceName = obj.optString("npServiceName", "trophy"),
			trophyTitleName = obj.optString("trophyTitleName", ""),
			trophyTitleIconUrl = obj.optString("trophyTitleIconUrl", ""),
			trophyTitlePlatform = obj.optString("trophyTitlePlatform", ""),
			hasTrophyGroups = obj.optBoolean("hasTrophyGroups", false),
			definedTrophies = parseTrophyCounts(obj.optJSONObject("definedTrophies")),
			earnedTrophies = parseTrophyCounts(obj.optJSONObject("earnedTrophies")),
			progressPercent = obj.optInt("progress", 0)
		)
	}

	private fun parseTrophyCounts(obj: JSONObject?): TrophyCounts
	{
		if (obj == null) return TrophyCounts()
		return TrophyCounts(
			bronze = obj.optInt("bronze", 0),
			silver = obj.optInt("silver", 0),
			gold = obj.optInt("gold", 0),
			platinum = obj.optInt("platinum", 0)
		)
	}

	private fun parseTrophy(obj: JSONObject): Trophy?
	{
		val name = obj.optString("trophyName", "")
		return Trophy(
			trophyId = obj.optInt("trophyId", -1),
			groupId = obj.optString("trophyGroupId", "default"),
			type = TrophyType.fromApiValue(obj.optString("trophyType", "bronze")),
			name = name,
			detail = obj.optString("trophyDetail", ""),
			iconUrl = obj.optString("trophyIconUrl", ""),
			hidden = obj.optBoolean("trophyHidden", false),
			earned = obj.optBoolean("earned", false),
			earnedDateTimeMs = parseEarnedDateTime(obj.optString("earnedDateTime", ""))
		)
	}

	private fun parseEarnedDateTime(value: String): Long?
	{
		if (value.isEmpty()) return null
		return try
		{
			val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
			format.timeZone = TimeZone.getTimeZone("UTC")
			format.parse(value)?.time
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Failed to parse earnedDateTime: $value", e)
			null
		}
	}
}
