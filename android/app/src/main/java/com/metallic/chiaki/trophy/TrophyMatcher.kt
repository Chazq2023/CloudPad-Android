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
	 * A handful of games were released under a different name per region — either an entirely
	 * different subtitle (the catalogue and Sony's own trophyTitleName can each independently be
	 * either one, so this canonicalises both to the same word) or an extra regional subtitle
	 * Sony's own title never registered. Neither is something the generic token-subsequence
	 * matching in [findBestMatch] can bridge: the first shares no words at all with the other
	 * name, and the second looks identical in shape to a real, distinct game having an extra,
	 * genuinely-unmatched subtitle (see the Pass 3 comment below) — matching it generically would
	 * reopen that bug. Left-hand pattern is rewritten to the right-hand canonical form wherever
	 * it appears.
	 */
	private val titleAliasPatterns = listOf(
		// EU "Gladiator" vs NA "Deadlocked" (PS2/PS3 Ratchet & Clank).
		Regex("\\bgladiator\\b") to "deadlocked",
		// EU "QForce" vs NA "Full Frontal Assault" (PS3 Ratchet & Clank).
		Regex("\\bq\\s*force\\b") to "full frontal assault",
		// EU "Sly Raccoon" vs NA "Sly Cooper and the Thievius Raccoonus" (PS1/PS5 Sly Cooper) —
		// confirmed via a real account's trophy data, which uses the NA name. "the" is stripped
		// from the canonical form since normalize() only removes it after alias substitution runs.
		Regex("\\bsly raccoon\\b") to "sly cooper and thievius raccoonus",
		// EU "Jak II: Renegade" vs Sony's plain "Jak II" (PS2/PS4) — "Renegade" was added only
		// for the EU release; Sony's own trophy title never carries it.
		Regex("\\bjak ii renegade\\b") to "jak ii"
	)

	/**
	 * British vs American spelling of the same word — unlike [titleAliasPatterns] above, this
	 * isn't a per-game naming decision but a general linguistic pattern that can turn up in any
	 * title (confirmed case: catalogue "Sly 3: Honour Among Thieves" vs Sony's own "Sly 3: Honor
	 * Among Thieves"), so it's kept as its own small, general word-level table rather than one
	 * more whole-phrase alias.
	 */
	private val spellingPatterns = listOf(
		Regex("\\bhonour\\b") to "honor",
		Regex("\\bcolour\\b") to "color",
		Regex("\\barmour\\b") to "armor",
		Regex("\\bdefence\\b") to "defense",
		Regex("\\boffence\\b") to "offense",
		Regex("\\bcentre\\b") to "center",
		Regex("\\bfavourite\\b") to "favorite"
	)

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

		for ((pattern, canonical) in titleAliasPatterns)
			result = pattern.replace(result, canonical)
		for ((pattern, canonical) in spellingPatterns)
			result = pattern.replace(result, canonical)

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

	private fun tokens(normalizedTitle: String): List<String> =
		normalizedTitle.split(whitespacePattern).filter { it.isNotEmpty() }

	/**
	 * True if every token of [shorter] appears in [longer], in the same relative order,
	 * with other tokens allowed in between. Unlike a raw substring check, this tolerates a
	 * word being inserted or dropped in the *middle* of a title — e.g. a catalogue title
	 * "Ratchet & Clank: Nexus" against Sony's own "Ratchet & Clank: Into the Nexus" — not
	 * just truncation/expansion at either end.
	 */
	private fun isTokenSubsequence(shorter: List<String>, longer: List<String>): Boolean
	{
		var index = 0

		for (token in longer)
		{
			if (index < shorter.size && token == shorter[index])
			{
				index++
			}
		}

		return index == shorter.size
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
		val gameTokens = tokens(normalizedGame)

		val partial = candidates
			.filter { candidate ->
				val normalizedCandidate = candidate.second

				/*
				 * Only accept Sony's title as the longer, more-complete side (catalogue tokens
				 * must be a subsequence of it), never the reverse. Sony's trophyTitleName is the
				 * authoritative full title; catalogue listings are sometimes abbreviated
				 * (justifying catalogue-is-shorter). But when the catalogue name is the longer
				 * side — e.g. catalogue "Ratchet & Clank: Tools of Destruction" against Sony's
				 * unrelated, shorter "Ratchet & Clank" — the extra words are a real, distinct
				 * subtitle identifying a different game, not filler to ignore, and Sony simply
				 * has no trophy title for it yet. Matching that would silently show the wrong
				 * game's trophies instead of correctly reporting no match.
				 */
				normalizedCandidate.isNotEmpty() &&
						numericTokens(normalizedCandidate) == originalNumbers &&
						isTokenSubsequence(gameTokens, tokens(normalizedCandidate))
			}
			/*
			 * A short, generic title (e.g. "Ratchet & Clank") is a substring of every
			 * longer subtitled entry in the same franchise (e.g. "Ratchet & Clank: Into
			 * the Nexus"), so when several candidates satisfy the containment check above,
			 * the one whose length is closest to the requested name is almost always the
			 * correct, more specific match — pick that first rather than whichever happens
			 * to come first in Sony's list order.
			 */
			.sortedBy { candidate ->
				kotlin.math.abs(candidate.second.length - normalizedGame.length)
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
