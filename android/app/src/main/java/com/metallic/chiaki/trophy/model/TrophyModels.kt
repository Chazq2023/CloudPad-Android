// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy.model

enum class TrophyType { BRONZE, SILVER, GOLD, PLATINUM;

	companion object
	{
		fun fromApiValue(value: String): TrophyType = when (value.lowercase())
		{
			"platinum" -> PLATINUM
			"gold" -> GOLD
			"silver" -> SILVER
			else -> BRONZE
		}
	}
}

data class TrophyCounts(
	val bronze: Int = 0,
	val silver: Int = 0,
	val gold: Int = 0,
	val platinum: Int = 0
)
{
	val total: Int get() = bronze + silver + gold + platinum
}

/**
 * One entry from the account-wide `trophyTitles` list — a game the user has trophy data for.
 * [npServiceName] must be threaded through to the groups/trophies calls for this same title
 * ("trophy" for legacy PS3/PS4-only titles, "trophy2" for titles on the PS5 trophy service) —
 * Sony returns it per-title rather than something derivable from the platform string alone.
 */
data class TrophyTitleSummary(
	val npCommunicationId: String,
	val npServiceName: String,
	val trophyTitleName: String,
	val trophyTitleIconUrl: String,
	val trophyTitlePlatform: String,
	val hasTrophyGroups: Boolean,
	val definedTrophies: TrophyCounts,
	val earnedTrophies: TrophyCounts,
	val progressPercent: Int
)

/** Account-wide trophy stats — the "Level 312" figure PSN shows on a profile, used by trophy
 *  comparison rather than any per-game screen. */
data class TrophyAccountSummary(
	val level: Int,
	val progressPercent: Int,
	val earnedTrophies: TrophyCounts
)

data class TrophyGroup(
	val groupId: String,
	val groupName: String,
	val groupIconUrl: String,
	val definedTrophies: TrophyCounts,
	val earnedTrophies: TrophyCounts
)

data class Trophy(
	val trophyId: Int,
	val groupId: String,
	val type: TrophyType,
	val name: String,
	val detail: String,
	val iconUrl: String,
	val hidden: Boolean,
	val earned: Boolean,
	val earnedDateTimeMs: Long?
)

/** Full detail for one game's trophy list, ready for display. */
data class TrophyTitleDetail(
	val summary: TrophyTitleSummary,
	val groups: List<TrophyGroup>,
	val trophies: List<Trophy>
)
