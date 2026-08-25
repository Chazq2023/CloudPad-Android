// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.util.Log
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.trophy.model.Trophy
import com.metallic.chiaki.trophy.model.TrophyCounts
import com.metallic.chiaki.trophy.model.TrophyGroup
import com.metallic.chiaki.trophy.model.TrophyTitleDetail
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import com.metallic.chiaki.trophy.model.TrophyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

sealed class TrophyResult
{
	data class Success(val detail: TrophyTitleDetail) : TrophyResult()
	object NoMatchFound : TrophyResult()
	data class Error(val message: String) : TrophyResult()
}

/**
 * Orchestrates the Trophies feature: obtaining a trophy-scoped token, fetching/caching the
 * account's full trophy titles list, matching it to a specific game (see [TrophyMatcher]), and
 * fetching that title's full trophy detail. Works the same for PS3 Catalog, PS4 Catalog and PS5
 * Library games since matching is purely name/platform-based.
 */
class TrophyRepository(private val preferences: Preferences)
{
	companion object
	{
		private const val TAG = "TrophyRepository"
	}

	private val tokenManager = PsnTrophyTokenManager(preferences)

	suspend fun fetchTrophiesForGame(
		gameName: String,
		platform: String,
		forceRefresh: Boolean = false
	): TrophyResult = withContext(Dispatchers.IO) {
		try
		{
			val token = tokenManager.getValidToken()
				?: return@withContext TrophyResult.Error("Could not authenticate with PSN for trophy data")

			val titles = getTrophyTitles(token, forceRefresh)

			val subGames = CollectionCatalog.subGamesFor(gameName, platform)
			if (subGames != null)
			{
				return@withContext fetchCollectionTrophies(token, gameName, platform, subGames, titles)
			}

			val match = TrophyMatcher.findBestMatch(gameName, platform, titles)
			if (match == null)
			{
				Log.i(TAG, "No trophy title match for \"$gameName\" ($platform) among ${titles.size} known titles " +
					"— likely never launched, so Sony has no trophy title entry for it yet")
				return@withContext TrophyResult.NoMatchFound
			}

			Log.i(TAG, "Matched \"$gameName\" ($platform) to trophy title \"${match.trophyTitleName}\" " +
				"(${match.npCommunicationId}, ${match.trophyTitlePlatform})")

			val detail = TrophyService.fetchTrophyTitleDetail(token, match)
			TrophyResult.Success(detail)
		}
		catch (e: Exception)
		{
			Log.e(TAG, "fetchTrophiesForGame failed", e)
			TrophyResult.Error(e.message ?: "Failed to fetch trophy data")
		}
	}

	/**
	 * Collection discs (see [CollectionCatalog]) have no single Sony trophy title of their own —
	 * each bundled game does. Matches every bundled game independently through the normal
	 * [TrophyMatcher] path (so each gets the full benefit of its aliasing/normalization), fetches
	 * whichever ones Sony already has synced data for concurrently, and merges them into one
	 * synthetic [TrophyTitleDetail] with each bundled game as its own trophy group — reusing the
	 * exact same group-header rendering [buildTrophyListItems] already gives a single game's DLC
	 * packs, so no UI changes are needed to display this per-game breakdown.
	 */
	private suspend fun fetchCollectionTrophies(
		token: String,
		gameName: String,
		platform: String,
		subGames: List<List<String>>,
		titles: List<TrophyTitleSummary>
	): TrophyResult = coroutineScope {
		// Each bundled game may be registered under any one of several candidate names (see
		// CollectionCatalog) — try them in order and take the first that matches.
		val matches = subGames.mapNotNull { candidateNames ->
			candidateNames.firstNotNullOfOrNull { name -> TrophyMatcher.findBestMatch(name, platform, titles) }
		}

		if (matches.isEmpty())
		{
			Log.i(TAG, "No trophy title match for any game in collection \"$gameName\" ($platform) " +
				"among ${titles.size} known titles — likely none of its games have been launched yet")
			return@coroutineScope TrophyResult.NoMatchFound
		}

		Log.i(TAG, "Matched collection \"$gameName\" ($platform) to ${matches.size}/${subGames.size} of its games: " +
			matches.joinToString { "\"${it.trophyTitleName}\" (${it.npCommunicationId})" })

		val details = matches
			.map { match -> async { TrophyService.fetchTrophyTitleDetail(token, match) } }
			.awaitAll()

		val groups = details.map { detail ->
			TrophyGroup(
				groupId = detail.summary.npCommunicationId,
				groupName = detail.summary.trophyTitleName,
				groupIconUrl = detail.summary.trophyTitleIconUrl,
				definedTrophies = countTrophies(detail.trophies),
				earnedTrophies = countTrophies(detail.trophies.filter { it.earned })
			)
		}

		val trophies = details.flatMap { detail ->
			detail.trophies.map { trophy -> trophy.copy(groupId = detail.summary.npCommunicationId) }
		}

		val definedTrophies = countTrophies(trophies)
		val earnedTrophies = countTrophies(trophies.filter { it.earned })

		val summary = TrophyTitleSummary(
			npCommunicationId = matches.joinToString("+") { it.npCommunicationId },
			npServiceName = matches.first().npServiceName,
			trophyTitleName = gameName,
			trophyTitleIconUrl = matches.first().trophyTitleIconUrl,
			trophyTitlePlatform = platform,
			hasTrophyGroups = true,
			definedTrophies = definedTrophies,
			earnedTrophies = earnedTrophies,
			progressPercent = if (definedTrophies.total == 0) 0 else (earnedTrophies.total * 100) / definedTrophies.total
		)

		TrophyResult.Success(TrophyTitleDetail(summary, groups, trophies))
	}

	private fun countTrophies(trophies: List<Trophy>): TrophyCounts = TrophyCounts(
		bronze = trophies.count { it.type == TrophyType.BRONZE },
		silver = trophies.count { it.type == TrophyType.SILVER },
		gold = trophies.count { it.type == TrophyType.GOLD },
		platinum = trophies.count { it.type == TrophyType.PLATINUM }
	)

	/** All of the signed-in account's trophy titles, not matched to any specific game — used by
	 *  trophy comparison. Shares the same cache [fetchTrophiesForGame] populates via
	 *  [getTrophyTitles], so opening a comparison right after browsing Trophies doesn't re-fetch. */
	suspend fun fetchMyTrophyTitles(forceRefresh: Boolean = false): List<com.metallic.chiaki.trophy.model.TrophyTitleSummary>? =
		withContext(Dispatchers.IO) {
			val token = tokenManager.getValidToken() ?: return@withContext null
			getTrophyTitles(token, forceRefresh)
		}

	private suspend fun getTrophyTitles(token: String, forceRefresh: Boolean): List<com.metallic.chiaki.trophy.model.TrophyTitleSummary>
	{
		if (!forceRefresh && preferences.isTrophyTitlesCacheFresh)
		{
			val cached = preferences.getCachedTrophyTitlesJson()
			if (cached != null)
			{
				val parsed = TrophyService.deserializeTitles(cached)
				if (parsed.isNotEmpty()) return parsed
			}
		}

		val fresh = TrophyService.fetchAllTrophyTitles(token)
		preferences.setCachedTrophyTitlesJson(TrophyService.serializeTitles(fresh))
		return fresh
	}
}
