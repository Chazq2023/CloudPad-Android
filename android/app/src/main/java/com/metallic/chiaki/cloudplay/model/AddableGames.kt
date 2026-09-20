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

/** Case-insensitive name/productId match used by the add-a-game search box; blank keeps everything. */
fun List<CloudGame>.matchingQuery(query: String): List<CloudGame>
{
	val q = query.trim()
	if (q.isEmpty()) return this
	return filter { it.name.contains(q, ignoreCase = true) || it.productId.contains(q, ignoreCase = true) }
}
