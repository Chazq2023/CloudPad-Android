// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.TrophyAccountSummary
import com.metallic.chiaki.trophy.model.TrophyCounts

data class SharedGameComparison(
	val gameName: String,
	val gameIconUrl: String,
	val platform: String,
	val myProgressPercent: Int,
	val theirProgressPercent: Int,
	val myEarned: TrophyCounts,
	val theirEarned: TrophyCounts
)

sealed class TrophyComparisonResult
{
	data class Success(
		val myAccountSummary: TrophyAccountSummary,
		val theirAccountSummary: TrophyAccountSummary,
		val sharedGames: List<SharedGameComparison>,
		val myAvatarUrl: String
	) : TrophyComparisonResult()
	data class Error(val message: String) : TrophyComparisonResult()
}
