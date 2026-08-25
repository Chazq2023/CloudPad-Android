// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

/**
 * PS3/PS4 "HD Collection" style discs that bundle several separate games, each with its own
 * independent trophy list on Sony's servers — unlike most catalogue titles, there is no single
 * Sony trophy title matching the collection's own name at all (confirmed against a real account:
 * "The Sly Trilogy" never gets its own npCommunicationId — only per-game ones do). CloudPad also
 * only ever learns the collection disc's own catalogue name for the whole stream session — it has
 * no way to tell which of the bundled games is actually being played — so the only workable
 * approach is to fetch and display all of the bundled games together, broken out under their own
 * headers via the same trophy-group mechanism a single game's DLC packs already use (see
 * [TrophyRepository.fetchCollectionTrophies]).
 *
 * Not every multi-game disc behaves this way — e.g. Uncharted: Legacy of Thieves Collection (PS5)
 * has been confirmed to have one single combined Sony trophy title matching its own catalogue
 * name, so it needs no entry here at all and is handled by the ordinary single-title match path.
 */
object CollectionCatalog
{
	/** Each bundled game is a *list* of candidate search names, not one — confirmed against a
	 *  real account: Sony registers a game's PS3-Trilogy-disc trophy title under a bare name
	 *  ("Sly 3") completely differently from the fuller name the same game's standalone release
	 *  uses elsewhere (e.g. "Sly 3: Honor Among Thieves" for the PS4 remaster) — these are
	 *  different npCommunicationIds for the same underlying game, and there's no way to know
	 *  upfront which naming convention any particular disc/game uses, so every plausible
	 *  candidate is tried and the first one that matches wins. */
	private data class Collection(val platform: String, val subGames: List<List<String>>)

	// Keyed by TrophyMatcher.normalize(catalogue title) so trademark symbols, platform
	// parentheticals etc. don't need to match exactly. Candidate names are fed individually
	// through TrophyMatcher.findBestMatch, so they get the full benefit of its normalization,
	// Roman-numeral and regional-alias handling — they don't need to be Sony's exact title text.
	private val collections: Map<String, Collection> = mapOf(
		TrophyMatcher.normalize("The Sly Trilogy") to Collection(
			platform = "ps3",
			subGames = listOf(
				// No bare "Sly Cooper" candidate here, unlike games 2 and 3 below — confirmed
				// against a real account that it wrongly matches the unrelated, separately
				// released "Sly Cooper: Thieves in Time" (2013) via Pass 3's subsequence rule
				// ("Sly Cooper" is a literal prefix of that different game's real title), since
				// this disc's own PS3 trophy title for game 1 hadn't synced yet to disambiguate.
				listOf("Sly Cooper and the Thievius Raccoonus"),
				listOf("Sly 2", "Sly 2: Band of Thieves"),
				// "Sly 3" confirmed directly against a real account; the fuller name kept as a
				// fallback candidate in case a future account's data differs.
				listOf("Sly 3", "Sly 3: Honor Among Thieves")
			)
		),
		TrophyMatcher.normalize("Uncharted: The Nathan Drake Collection") to Collection(
			platform = "ps4",
			subGames = listOf(
				listOf("Uncharted: Drake's Fortune", "Uncharted: Drake's Fortune Remastered"),
				listOf("Uncharted 2: Among Thieves", "Uncharted 2: Among Thieves Remastered"),
				listOf("Uncharted 3: Drake's Deception", "Uncharted 3: Drake's Deception Remastered")
			)
		),
		TrophyMatcher.normalize("Devil May Cry HD Collection") to Collection(
			platform = "ps3",
			subGames = listOf(
				listOf("Devil May Cry"),
				listOf("Devil May Cry 2"),
				listOf("Devil May Cry 3", "Devil May Cry 3 Special Edition")
			)
		),
		TrophyMatcher.normalize("Assassin's Creed The Ezio Collection") to Collection(
			platform = "ps4",
			subGames = listOf(
				listOf("Assassin's Creed II"),
				listOf("Assassin's Creed Brotherhood"),
				listOf("Assassin's Creed Revelations")
			)
		),
		TrophyMatcher.normalize("Serious Sam Collection") to Collection(
			platform = "ps4",
			subGames = listOf(
				listOf("Serious Sam HD: The First Encounter"),
				listOf("Serious Sam HD: The Second Encounter"),
				listOf("Serious Sam 3: BFE")
			)
		)
	)

	/** The individual bundled games' candidate search names for [gameName]/[platform] if it's a
	 *  known collection disc, or null if it's an ordinary single-game title that should use the
	 *  normal match path. Each inner list is one bundled game's alternative candidate names, in
	 *  priority order. */
	fun subGamesFor(gameName: String, platform: String): List<List<String>>?
	{
		val collection = collections[TrophyMatcher.normalize(gameName)] ?: return null
		if (collection.platform != platform.lowercase().trim()) return null
		return collection.subGames
	}
}
