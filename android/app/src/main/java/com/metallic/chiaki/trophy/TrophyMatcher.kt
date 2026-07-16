// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.TrophyTitleSummary

/**
 * Sony's Trophies API has no direct mapping from a store productId/CUSA id to the
 * npCommunicationId trophy data is keyed by — the only way to resolve it is a best-effort
 * name match against the account's full trophyTitles list. Pulled out as pure functions so
 * the matching heuristics can be unit tested without any network dependency.
 */
object TrophyMatcher
{
	private val editionSuffixPattern = Regex(
		"[:\\-–—]\\s*(standard|digital|deluxe|ultimate|complete|goty|game of the year|" +
			"definitive|remastered?|anniversary|legendary|gold|special|enhanced)\\s*edition.*",
		RegexOption.IGNORE_CASE
	)
	private val platformSuffixPattern = Regex(
		"\\((?:ps3|ps4|ps5|playstation ?[345])\\)",
		RegexOption.IGNORE_CASE
	)
	private val trademarkPattern = Regex("[™®©]")
	private val nonAlphaNumPattern = Regex("[^a-z0-9]+")
	private val whitespacePattern = Regex("\\s+")

	fun normalize(name: String): String
	{
		var result = name.lowercase()
		result = trademarkPattern.replace(result, "")
		result = platformSuffixPattern.replace(result, "")
		result = editionSuffixPattern.replace(result, "")
		result = nonAlphaNumPattern.replace(result, " ")
		return whitespacePattern.replace(result, " ").trim()
	}

	/** "ps3"/"ps4"/"ps5" style platform token from any of this app's platform string variants. */
	private fun platformToken(platform: String): String = when
	{
		platform.contains("5") -> "ps5"
		platform.contains("4") -> "ps4"
		platform.contains("3") -> "ps3"
		else -> ""
	}

	/**
	 * Best-effort match: exact normalized name first, then substring containment either
	 * direction (handles truncated/expanded subtitles), preferring a title whose platform
	 * matches [platform] when more than one candidate remains. Returns null if nothing
	 * reasonable is found.
	 */
	fun findBestMatch(gameName: String, platform: String, titles: List<TrophyTitleSummary>): TrophyTitleSummary?
	{
		val normalizedGame = normalize(gameName)
		if (normalizedGame.isEmpty() || titles.isEmpty()) return null

		val candidates = titles.map { it to normalize(it.trophyTitleName) }
		val token = platformToken(platform)

		val exact = candidates.filter { it.second == normalizedGame }.map { it.first }
		if (exact.isNotEmpty()) return pickByPlatform(exact, token)

		val partial = candidates.filter {
			it.second.isNotEmpty() && (it.second.contains(normalizedGame) || normalizedGame.contains(it.second))
		}.map { it.first }
		if (partial.isNotEmpty()) return pickByPlatform(partial, token)

		return null
	}

	private fun pickByPlatform(matches: List<TrophyTitleSummary>, platformToken: String): TrophyTitleSummary
	{
		if (platformToken.isNotEmpty())
		{
			matches.firstOrNull { it.trophyTitlePlatform.lowercase().contains(platformToken) }?.let { return it }
		}
		return matches.first()
	}
}
