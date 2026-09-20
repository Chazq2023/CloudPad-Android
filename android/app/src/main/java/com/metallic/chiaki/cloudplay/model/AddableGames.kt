// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.model

/**
 * Games from the full PS5 streaming catalog that the user hasn't added to their library yet,
 * A-Z by name, one entry per productId/platform (the catalog can carry the same title twice).
 */
fun List<CloudGame>.notInLibrary(): List<CloudGame> =
	filter { !it.isOwned }
		.distinctBy { "${it.productId}|${it.platform}" }
		.sortedBy { it.name.lowercase() }

private val STABLE_KEY = Regex("(?:PPSA|CUSA)\\d+")

/** The PPSA/CUSA number in a product id — what identifies a game across its edition SKUs. */
fun productStableKey(productId: String): String? = STABLE_KEY.find(productId)?.value

/** Marks the games whose PPSA/CUSA number is in [plusKeys] (from the PS Store's PS Plus tags). */
fun List<CloudGame>.taggedWithPsPlus(plusKeys: Set<String>): List<CloudGame> =
	map { game -> game.copy(psPlus = productStableKey(game.productId)?.let { it in plusKeys } == true) }

// Words that only name an edition of a game, so "RESIDENT EVIL 7 biohazard Gold Edition" in the
// library and "RESIDENT EVIL 7 biohazard" in the catalog compare as the same title. Kept to
// unambiguous edition words — stripping anything looser (e.g. prefix matching) would hide
// different games in a series ("Resident Evil" vs "Resident Evil 7").
private val EDITION_WORDS = setOf(
	"gold", "deluxe", "ultimate", "standard", "premium", "complete", "edition",
	"digital", "goty", "legendary", "special"
)

private fun normalizedName(name: String): String =
	Regex("[\\p{L}\\p{N}]+").findAll(name.lowercase())
		.map { it.value }
		.filter { it !in EDITION_WORDS }
		.joinToString("")

/**
 * Drops catalog games that are already in [library], matched by what the library entry actually
 * carries rather than the catalog's own isOwned flag — that flag misses titles whose name differs
 * between the two sources (e.g. "The Last of Us Part II Remastered" vs "The Last of Us Part II",
 * "Dying Light 2: Stay Human" vs "Dying Light 2"), which would otherwise show up on both lists.
 * A game counts as owned when it shares a productId/storeProductId, the same PPSA/CUSA number
 * (edition SKUs of one game differ only in their suffix), or the same name (ignoring edition words like Gold/Deluxe) on the same platform.
 */
fun List<CloudGame>.excludingLibrary(library: List<CloudGame>): List<CloudGame>
{
	if (library.isEmpty()) return this
	val ids = HashSet<String>()
	val stableKeys = HashSet<String>()
	val names = HashSet<String>()
	for (owned in library)
	{
		for (id in listOf(owned.productId, owned.storeProductId))
		{
			if (id.isEmpty()) continue
			ids.add(id)
			STABLE_KEY.find(id)?.let { stableKeys.add(it.value) }
		}
		normalizedName(owned.name).takeIf { it.isNotEmpty() }?.let { names.add("$it|${owned.platform}") }
	}
	return filter { game ->
		game.productId !in ids &&
			STABLE_KEY.find(game.productId)?.value !in stableKeys &&
			"${normalizedName(game.name)}|${game.platform}" !in names
	}
}

/** Case-insensitive name/productId match used by the add-a-game search box; blank keeps everything. */
fun List<CloudGame>.matchingQuery(query: String): List<CloudGame>
{
	val q = query.trim()
	if (q.isEmpty()) return this
	return filter { it.name.contains(q, ignoreCase = true) || it.productId.contains(q, ignoreCase = true) }
}

/** The Add Game to PS5 Library page's filter. */
enum class AddGameFilter
{
	/** Everything not in the library. */
	ALL,
	/** Titles that have to be bought: in no PS Plus catalog, not included with PS Plus, not free to play. */
	PURCHASABLE,
	/** Titles in the PS Plus catalog lists Sony publishes for cloud streaming. */
	PS_CATALOG,
	/** Titles included with a PS Plus subscription, per the PS Store. Overlaps PS_CATALOG. */
	PS_PLUS,
	/** Free-to-play titles: nothing to buy, and not part of PS Plus. */
	FREE_TO_PLAY,
	/** Games hidden because the store offers no way to get them. They aren't part of All; the page
	 *  supplies that separate list, so [matchingFilter] leaves it untouched. Tapping one re-checks it. */
	UNAVAILABLE
}

fun List<CloudGame>.matchingFilter(filter: AddGameFilter): List<CloudGame> = when(filter)
{
	AddGameFilter.ALL -> this
	AddGameFilter.PURCHASABLE -> filter { !it.psCatalog && !it.psPlus && !it.freeToPlay }
	AddGameFilter.PS_CATALOG -> filter { it.psCatalog }
	AddGameFilter.PS_PLUS -> filter { it.psPlus }
	AddGameFilter.FREE_TO_PLAY -> filter { it.freeToPlay }
	AddGameFilter.UNAVAILABLE -> this
}
