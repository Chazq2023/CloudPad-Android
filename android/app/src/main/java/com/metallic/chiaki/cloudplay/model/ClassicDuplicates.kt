// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.model

// An emulated classic's product id ends in its original disc code, e.g.
// UP9000-CUSA48754_00-SCUS973550000000 (SCUS-97355) — unlike a remake such as
// EP9000-CUSA12982_00-MEDIEVILHD000001.
private val CLASSIC_SUFFIX = Regex("^[A-Z]{4}\\d{5}")
private val TITLE_NUMBER = Regex("(?:CUSA|PPSA)(\\d{5})")

internal fun isEmulatedClassic(productId: String): Boolean =
	CLASSIC_SUFFIX.containsMatchIn(productId.split("-", limit = 3).getOrElse(2) { "" })

private fun titleNumber(productId: String): Int =
	TITLE_NUMBER.find(productId)?.groupValues?.get(1)?.toInt() ?: -1

private fun titleKey(game: CloudGame): String =
	game.name.replace(Regex("[™®]"), "").replace(Regex("\\s+"), " ").trim().lowercase() + "|" + game.platform

/**
 * The PS3/PS4 catalog lists some classics twice under the same name — an old regional release
 * and Sony's newer re-release (Forbidden Siren: EU CUSA02274 from 2016 and US CUSA48754, the one
 * the PS Store presents). Keeps only the newest (highest CUSA number) of same-named emulated
 * classics; anything else sharing a name (e.g. the MediEvil remake beside the PS1 original) stays.
 */
fun List<CloudGame>.withoutSupersededClassics(): List<CloudGame>
{
	val newestClassic = filter { isEmulatedClassic(it.productId) }
		.groupBy(::titleKey)
		.mapValues { (_, games) -> games.maxBy { titleNumber(it.productId) } }
	return filter { game ->
		!isEmulatedClassic(game.productId) || newestClassic[titleKey(game)] === game
	}
}
