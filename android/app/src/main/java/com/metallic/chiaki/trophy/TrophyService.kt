// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.util.Log
import com.metallic.chiaki.cloudplay.api.HttpClient
import com.metallic.chiaki.trophy.model.Trophy
import com.metallic.chiaki.trophy.model.TrophyCounts
import com.metallic.chiaki.trophy.model.TrophyGroup
import com.metallic.chiaki.trophy.model.TrophyTitleDetail
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import com.metallic.chiaki.trophy.model.TrophyType
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

	/** All trophy titles (games with trophy data) on the signed-in account, across every page. */
	suspend fun fetchAllTrophyTitles(accessToken: String): List<TrophyTitleSummary>
	{
		val all = mutableListOf<TrophyTitleSummary>()
		var offset = 0

		while (true)
		{
			val url = "${PsnTrophyConstants.TROPHY_BASE}/users/me/trophyTitles?limit=$PAGE_SIZE&offset=$offset"
			val response = HttpClient.get(
				url = url,
				headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
			)

			if (response.statusCode != 200)
			{
				Log.e(TAG, "fetchAllTrophyTitles failed at offset=$offset: ${response.statusCode} - ${response.body}")
				throw Exception("Failed to fetch trophy titles: HTTP ${response.statusCode}")
			}

			val json = JSONObject(response.body)
			val titlesArray = json.optJSONArray("trophyTitles") ?: JSONArray()
			for (i in 0 until titlesArray.length())
				parseTrophyTitleSummary(titlesArray.getJSONObject(i))?.let { all.add(it) }

			val totalItemCount = json.optInt("totalItemCount", all.size)
			offset += titlesArray.length()
			if (titlesArray.length() == 0 || offset >= totalItemCount) break
		}

		Log.i(TAG, "fetchAllTrophyTitles: ${all.size} titles")
		return all
	}

	/** Full detail (groups + every trophy, earned state merged in) for one trophy title. */
	suspend fun fetchTrophyTitleDetail(accessToken: String, summary: TrophyTitleSummary): TrophyTitleDetail
	{
		val groups = fetchTrophyGroups(accessToken, summary)
		val trophies = mutableListOf<Trophy>()

		if (groups.isEmpty())
		{
			trophies.addAll(fetchTrophiesForGroup(accessToken, summary, "default"))
		}
		else
		{
			for (group in groups)
				trophies.addAll(fetchTrophiesForGroup(accessToken, summary, group.groupId))
		}

		return TrophyTitleDetail(summary, groups, trophies)
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
	): List<Trophy>
	{
		val definitions = fetchTrophyDefinitions(accessToken, summary, groupId)
		val earnedStatus = fetchTrophyEarnedStatus(accessToken, summary, groupId)

		if (definitions.isEmpty()) return earnedStatus.values.toList()

		return definitions.map { (trophyId, definition) ->
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
