// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.util.TypedValue
import coil.load
import com.pylux.stream.R
import com.pylux.stream.databinding.ItemTrophyCompareHeaderBinding

/** Populates the shared `item_trophy_compare_header` layout — included by both
 *  [com.metallic.chiaki.friends.TrophyCompareActivity]'s full-screen layout and
 *  [com.metallic.chiaki.stream.QuickSettingsPanel]'s inline Trophy Compare sub-view, so both
 *  present identical header content from the same fetched comparison. Each side gets its own
 *  avatar + level + 2x2 grid of its own trophy counts, side by side rather than stacked, to fit
 *  this screen's usual landscape orientation. */
fun ItemTrophyCompareHeaderBinding.bindTrophyCompareHeader(
	comparison: TrophyComparisonResult.Success,
	myAvatarUrl: String,
	theirName: String,
	theirAvatarUrl: String
)
{
	val context = trophyCompareMyLevel.context

	if (myAvatarUrl.isNotEmpty()) trophyCompareMyAvatar.load(myAvatarUrl) { crossfade(true) }
	else trophyCompareMyAvatar.setImageResource(android.R.drawable.ic_menu_gallery)
	trophyCompareMyLevel.text = formatLevel(comparison.myAccountSummary.level)
	trophyCompareMyTotalTrophies.text =
		context.getString(R.string.trophy_compare_total_trophies_format, comparison.myAccountSummary.earnedTrophies.total)
	trophyCompareMyPlatinumChip.text = comparison.myAccountSummary.earnedTrophies.platinum.toString()
	trophyCompareMyGoldChip.text = comparison.myAccountSummary.earnedTrophies.gold.toString()
	trophyCompareMySilverChip.text = comparison.myAccountSummary.earnedTrophies.silver.toString()
	trophyCompareMyBronzeChip.text = comparison.myAccountSummary.earnedTrophies.bronze.toString()

	if (theirAvatarUrl.isNotEmpty()) trophyCompareTheirAvatar.load(theirAvatarUrl) { crossfade(true) }
	else trophyCompareTheirAvatar.setImageResource(android.R.drawable.ic_menu_gallery)
	trophyCompareTheirLevel.text = formatLevel(comparison.theirAccountSummary.level)
	trophyCompareTheirTotalTrophies.text =
		context.getString(R.string.trophy_compare_total_trophies_format, comparison.theirAccountSummary.earnedTrophies.total)
	trophyCompareTheirPlatinumChip.text = comparison.theirAccountSummary.earnedTrophies.platinum.toString()
	trophyCompareTheirGoldChip.text = comparison.theirAccountSummary.earnedTrophies.gold.toString()
	trophyCompareTheirSilverChip.text = comparison.theirAccountSummary.earnedTrophies.silver.toString()
	trophyCompareTheirBronzeChip.text = comparison.theirAccountSummary.earnedTrophies.bronze.toString()
}

private fun ItemTrophyCompareHeaderBinding.formatLevel(level: Int): String
{
	val context = trophyCompareMyLevel.context
	val value = if (level > 0) level.toString() else context.getString(R.string.trophy_compare_level_unknown)
	return context.getString(R.string.trophy_compare_level_format, value)
}

internal fun resolvePyluxAccent(context: android.content.Context): Int
{
	val tv = TypedValue()
	context.theme.resolveAttribute(R.attr.pyluxAccent, tv, true)
	return tv.data
}
