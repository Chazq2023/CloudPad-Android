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

	/**
	 * Store/catalogue titles and Sony's own trophyTitleName are inconsistent about including
	 * the word "the" (e.g. a catalogue entry "Tainted Grail: Fall of Avalon" vs Sony's trophy
	 * title "Tainted Grail: The Fall of Avalon"), and the word can appear mid-title rather than
	 * as a leading article, so it must be dropped rather than just trimmed off the start.
	 */
	fun normalize(name: String): String
	{
		var result = name.lowercase()
		result = trademarkPattern.replace(result, "")
		result = platformSuffixPattern.replace(result, "")
		result = editionSuffixPattern.replace(result, "")
		result = nonAlphaNumPattern.replace(result, " ")
		result = whitespacePattern.replace(result, " ").trim()

		return result
			.split(" ")
			.filter { token -> token != "the" }
			.joinToString(" ")
	}

	/**
	 * Converts standalone Arabic-number tokens in a title to Roman numerals.
	 *
	 * Examples:
	 * Alan Wake 2        -> Alan Wake II
	 * Mafia 3            -> Mafia III
	 * Final Fantasy 16   -> Final Fantasy XVI
	 *
	 * Numbers embedded inside words are not changed.
	 */
	private fun withRomanNumerals(name: String): String
	{
		val standaloneNumberPattern = Regex("""(?<!\p{L}|\p{N})\d+(?!\p{L}|\p{N})""")

		return standaloneNumberPattern.replace(name) { match ->
			val number = match.value.toIntOrNull()

			if (number != null && number in 2..3999)
			{
				toRomanNumeral(number)
			}
			else
			{
				match.value
			}
		}
	}

	private fun numericTokens(
		normalizedTitle: String
	): Set<String>
	{
		return normalizedTitle
			.split(Regex("\\s+"))
			.filter { token ->
				token.isNotEmpty() &&
						token.all(Char::isDigit)
			}
			.toSet()
	}

	private fun toRomanNumeral(value: Int): String
	{
		var remaining = value

		val numerals = listOf(
			1000 to "M",
			900 to "CM",
			500 to "D",
			400 to "CD",
			100 to "C",
			90 to "XC",
			50 to "L",
			40 to "XL",
			10 to "X",
			9 to "IX",
			5 to "V",
			4 to "IV",
			1 to "I"
		)

		return buildString {
			for ((number, numeral) in numerals)
			{
				while (remaining >= number)
				{
					append(numeral)
					remaining -= number
				}
			}
		}
	}

	/** "ps3"/"ps4"/"ps5" style platform token from any of this app's platform string variants. */
	private fun platformToken(platform: String): String
	{
		val normalized = platform.lowercase().trim()

		return when
		{
			normalized == "ps5" ||
					normalized == "playstation 5" -> "ps5"

			normalized == "ps4" ||
					normalized == "playstation 4" -> "ps4"

			normalized == "ps3" ||
					normalized == "playstation 3" -> "ps3"

			else -> ""
		}
	}

	/**
	 * Best-effort match: exact normalized name first, then substring containment either
	 * direction (handles truncated/expanded subtitles), preferring a title whose platform
	 * matches [platform] when more than one candidate remains. Returns null if nothing
	 * reasonable is found.
	 */
	fun findBestMatch(
		gameName: String,
		platform: String,
		titles: List<TrophyTitleSummary>
	): TrophyTitleSummary?
	{
		val normalizedGame = normalize(gameName)

		if (normalizedGame.isEmpty() || titles.isEmpty())
		{
			return null
		}

		val candidates = titles.map { title ->
			title to normalize(title.trophyTitleName)
		}

		val platformToken = platformToken(platform)

		/*
         * Pass 1: use the original catalogue title unchanged.
         *
         * This preserves all currently working title matches.
         */
		val exactOriginal = candidates
			.filter { candidate ->
				candidate.second == normalizedGame
			}
			.map { candidate ->
				candidate.first
			}

		if (exactOriginal.isNotEmpty())
		{
			return pickByPlatform(
				matches = exactOriginal,
				platformToken = platformToken
			)
		}

		/*
         * Pass 2: retry using Roman numerals.
         *
         * This only runs when the original exact lookup failed.
         */
		val romanGameName = withRomanNumerals(gameName)
		val normalizedRomanGame = normalize(romanGameName)

		if (
			normalizedRomanGame.isNotEmpty() &&
			normalizedRomanGame != normalizedGame
		)
		{
			val exactRoman = candidates
				.filter { candidate ->
					candidate.second == normalizedRomanGame
				}
				.map { candidate ->
					candidate.first
				}

			if (exactRoman.isNotEmpty())
			{
				return pickByPlatform(
					matches = exactRoman,
					platformToken = platformToken
				)
			}
		}

		/*
         * Preserve the existing subtitle/edition fallback, but only after both
         * exact lookup strategies have failed.
         *
         * Numeric tokens must match so that sequels cannot fall back to the
         * original game or a different numbered title.
         */
		val originalNumbers = numericTokens(normalizedGame)

		val partial = candidates
			.filter { candidate ->
				val normalizedCandidate = candidate.second

				normalizedCandidate.isNotEmpty() &&
						numericTokens(normalizedCandidate) == originalNumbers &&
						(
								normalizedCandidate.contains(normalizedGame) ||
										normalizedGame.contains(normalizedCandidate)
								)
			}
			.map { candidate ->
				candidate.first
			}

		if (partial.isNotEmpty())
		{
			return pickByPlatform(
				matches = partial,
				platformToken = platformToken
			)
		}

		return null
	}

	private fun pickByPlatform(
		matches: List<TrophyTitleSummary>,
		platformToken: String
	): TrophyTitleSummary?
	{
		if (matches.isEmpty())
		{
			return null
		}

		/*
         * If CloudPad cannot identify the requested platform, preserve the
         * existing behaviour and return the first title-name match.
         */
		if (platformToken.isEmpty())
		{
			return matches.firstOrNull()
		}

		val selected = matches.firstOrNull { title ->
			val supportedPlatforms = title.trophyTitlePlatform
				.lowercase()
				.split(Regex("[,/\\s]+"))
				.filter { it.isNotBlank() }

			platformToken in supportedPlatforms
		}

		return selected
	}
}
