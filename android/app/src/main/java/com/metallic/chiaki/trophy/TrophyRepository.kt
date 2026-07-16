// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.util.Log
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.trophy.model.TrophyTitleDetail
import kotlinx.coroutines.Dispatchers
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
			val match = TrophyMatcher.findBestMatch(gameName, platform, titles)
			if (match == null)
			{
				Log.i(TAG, "No trophy title match for \"$gameName\" ($platform) among ${titles.size} known titles " +
					"— likely never launched, so Sony has no trophy title entry for it yet")
				return@withContext TrophyResult.NoMatchFound
			}

			val detail = TrophyService.fetchTrophyTitleDetail(token, match)
			TrophyResult.Success(detail)
		}
		catch (e: Exception)
		{
			Log.e(TAG, "fetchTrophiesForGame failed", e)
			TrophyResult.Error(e.message ?: "Failed to fetch trophy data")
		}
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
