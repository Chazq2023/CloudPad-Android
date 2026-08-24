// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.util.Log
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import com.pylux.stream.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** Pure matching/sorting step of trophy comparison, pulled out of [TrophyCompareRepository]'s
 *  network-calling function so it's unit-testable without a live PSN account — matches shared
 *  games by npCommunicationId (both lists come from the same API in the same shape) and ranks
 *  them by combined trophies earned, so games you've both actually put time into rise to the top
 *  rather than an alphabetical list dominated by titles neither of you has touched much. */
fun matchSharedGames(myTitles: List<TrophyTitleSummary>, theirTitles: List<TrophyTitleSummary>): List<SharedGameComparison>
{
	val theirTitlesByGame = theirTitles.associateBy { it.npCommunicationId }
	return myTitles.mapNotNull { mine ->
		val theirs = theirTitlesByGame[mine.npCommunicationId] ?: return@mapNotNull null
		SharedGameComparison(
			gameName = mine.trophyTitleName,
			gameIconUrl = mine.trophyTitleIconUrl,
			platform = mine.trophyTitlePlatform,
			myProgressPercent = mine.progressPercent,
			theirProgressPercent = theirs.progressPercent,
			myEarned = mine.earnedTrophies,
			theirEarned = theirs.earnedTrophies
		)
	}.sortedByDescending { it.myEarned.total + it.theirEarned.total }
}

/**
 * Orchestrates trophy comparison against a friend: your (cached) trophy titles + account summary,
 * their (always-fresh) equivalents, matched into a shared-games list by npCommunicationId — a
 * cleaner match than [TrophyMatcher]'s name-based fuzzy matching, since both lists come from the
 * same API in the same shape rather than one side being a live stream's game name. Takes a plain
 * accountId/onlineId rather than the `friends` package's Friend type, so this package doesn't
 * depend on `friends` (which already depends on `trophy` for token reuse — keeping that one-way).
 */
class TrophyCompareRepository(private val preferences: Preferences, private val trophyRepository: TrophyRepository)
{
	companion object
	{
		private const val TAG = "TrophyCompareRepository"
	}

	private val tokenManager = PsnTrophyTokenManager(preferences)

	suspend fun fetchComparison(friendAccountId: String): TrophyComparisonResult = withContext(Dispatchers.IO) {
		try
		{
			val token = tokenManager.getValidToken()
				?: return@withContext TrophyComparisonResult.Error(preferences.getString(R.string.trophy_compare_error_auth_failed))

			val myTitlesDeferred = async { trophyRepository.fetchMyTrophyTitles() }
			val theirTitlesDeferred = async { TrophyService.fetchAllTrophyTitles(token, friendAccountId) }
			val mySummaryDeferred = async { TrophyService.fetchTrophySummary(token) }
			val theirSummaryDeferred = async { TrophyService.fetchTrophySummary(token, friendAccountId) }
			val myAvatarDeferred = async { TrophyService.fetchAvatarUrl(token) }

			val myTitles = myTitlesDeferred.await()
				?: return@withContext TrophyComparisonResult.Error(preferences.getString(R.string.trophy_compare_error_my_titles_failed))
			val theirTitles = theirTitlesDeferred.await()
			val mySummary = mySummaryDeferred.await()
				?: return@withContext TrophyComparisonResult.Error(preferences.getString(R.string.trophy_compare_error_my_summary_failed))
			val theirSummary = theirSummaryDeferred.await()
				?: return@withContext TrophyComparisonResult.Error(preferences.getString(R.string.trophy_compare_error_their_data_unavailable))
			val myAvatarUrl = myAvatarDeferred.await()

			val sharedGames = matchSharedGames(myTitles, theirTitles)

			Log.i(TAG, "fetchComparison($friendAccountId): mine=${myTitles.size} titles, theirs=${theirTitles.size} titles, " +
				"matched=${sharedGames.size} shared games")
			TrophyComparisonResult.Success(mySummary, theirSummary, sharedGames, myAvatarUrl)
		}
		catch (e: Exception)
		{
			Log.e(TAG, "fetchComparison failed for $friendAccountId", e)
			TrophyComparisonResult.Error(e.message ?: preferences.getString(R.string.trophy_compare_error_generic))
		}
	}
}
