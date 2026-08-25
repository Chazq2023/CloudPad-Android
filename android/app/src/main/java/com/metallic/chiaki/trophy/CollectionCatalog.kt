// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

/**
 * PS3/PS4 "HD Collection" style discs that bundle several separate games, each with its own
 * independent trophy list on Sony's servers — unlike most catalogue titles, there is no single
 * Sony trophy title matching the collection's own name at all (confirmed against a real account:
 * "The Sly Trilogy" never gets its own npCommunicationId — only "Sly Cooper and the Thievius
 * Raccoonus", "Sly 2: Band of Thieves" and "Sly 3: Honor Among Thieves" individually do). CloudPad
 * also only ever learns the collection disc's own catalogue name for the whole stream session —
 * it has no way to tell which of the bundled games is actually being played — so the only
 * workable approach is to fetch and display all of the bundled games together, broken out under
 * their own headers via the same trophy-group mechanism a single game's DLC packs already use
 * (see [TrophyRepository.fetchCollectionTrophies]).
 *
 * Not every multi-game disc behaves this way — e.g. Uncharted: Legacy of Thieves Collection (PS5)
 * has been confirmed to have one single combined Sony trophy title matching its own catalogue
 * name, so it needs no entry here at all and is handled by the ordinary single-title match path.
 */
object CollectionCatalog
{
	private data class Collection(val platform: String, val subGameNames: List<String>)

	// Keyed by TrophyMatcher.normalize(catalogue title) so trademark symbols, platform
	// parentheticals etc. don't need to match exactly. Sub-game names are fed individually
	// through TrophyMatcher.findBestMatch, so they get the full benefit of its normalization,
	// Roman-numeral and regional-alias handling — they don't need to be Sony's exact title text.
	private val collections: Map<String, Collection> = mapOf(
		TrophyMatcher.normalize("The Sly Trilogy") to Collection(
			platform = "ps3",
			subGameNames = listOf(
				"Sly Cooper and the Thievius Raccoonus",
				"Sly 2: Band of Thieves",
				"Sly 3: Honor Among Thieves"
			)
		),
		TrophyMatcher.normalize("Uncharted: The Nathan Drake Collection") to Collection(
			platform = "ps4",
			subGameNames = listOf(
				"Uncharted: Drake's Fortune Remastered",
				"Uncharted 2: Among Thieves Remastered",
				"Uncharted 3: Drake's Deception Remastered"
			)
		),
		TrophyMatcher.normalize("Devil May Cry HD Collection") to Collection(
			platform = "ps3",
			subGameNames = listOf(
				"Devil May Cry",
				"Devil May Cry 2",
				"Devil May Cry 3 Special Edition"
			)
		),
		TrophyMatcher.normalize("Assassin's Creed The Ezio Collection") to Collection(
			platform = "ps4",
			subGameNames = listOf(
				"Assassin's Creed II",
				"Assassin's Creed Brotherhood",
				"Assassin's Creed Revelations"
			)
		),
		TrophyMatcher.normalize("Serious Sam Collection") to Collection(
			platform = "ps4",
			subGameNames = listOf(
				"Serious Sam HD: The First Encounter",
				"Serious Sam HD: The Second Encounter",
				"Serious Sam 3: BFE"
			)
		)
	)

	/** The individual sub-game search names for [gameName]/[platform] if it's a known collection
	 *  disc, or null if it's an ordinary single-game title that should use the normal match path. */
	fun subGameNamesFor(gameName: String, platform: String): List<String>?
	{
		val collection = collections[TrophyMatcher.normalize(gameName)] ?: return null
		if (collection.platform != platform.lowercase().trim()) return null
		return collection.subGameNames
	}
}
