// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.api

import android.util.Log
import com.metallic.chiaki.cloudplay.model.productStableKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a game's PlayStation Store page says about being able to get the game. */
enum class StoreVerdict
{
	/** The page offers a way to buy or add this game's PS5 edition. */
	AVAILABLE,
	/** The page offers other editions but none for this game (e.g. Tennis World Tour 2: only the PS4 edition is sold). */
	UNAVAILABLE,
	/** Couldn't tell — page failed to load or looked different than expected. Never treated as unavailable. */
	UNKNOWN
}

/**
 * Checks ONE game's PlayStation Store page for a buy / add-to-library button for that game, when
 * the user taps it on the Add Game page. Neither the store listing nor the catalog carries this:
 * Tennis World Tour 2 is listed with a normal price and a PS Plus tag but its page sells only the
 * PS4 edition, so the game can't be bought or added.
 *
 * The page embeds one `GameCTA:<type>:<action>:<productId>-E###[:...]` entry per button. The game
 * is available if any button belongs to its own product (matched by PPSA/CUSA number). The verdict
 * is deliberately narrow: [StoreVerdict.UNAVAILABLE] only when buttons exist for OTHER products but
 * not this one, or the page is gone (404/410). A page with no buttons at all, or any load problem,
 * is [StoreVerdict.UNKNOWN] — a lighter variant of the page has been seen without them, so their
 * absence alone proves nothing, and a wrong "unavailable" would hide a game that can be added.
 *
 * This is a single page request per tap (~600 KB) with the app's own identity, never a bulk crawl;
 * StoreAvailabilityRepository also caches the answers and caps how many live checks run per hour.
 */
internal object StoreAvailabilityService
{
	private const val TAG = "StoreAvailability"
	private const val USER_AGENT = "CloudPad-Android"
	private const val TIMEOUT_MS = 20_000

	private val CTA_PRODUCT = Regex("GameCTA:[A-Z_]+:[A-Z_]+:([A-Za-z0-9_\\-]+?)-E\\d{3}")

	internal fun verdictFromPage(html: String, productId: String): StoreVerdict
	{
		val ourKey = productStableKey(productId) ?: return StoreVerdict.UNKNOWN
		val buttonProducts = CTA_PRODUCT.findAll(html).map { it.groupValues[1] }.toSet()
		if (buttonProducts.isEmpty()) return StoreVerdict.UNKNOWN
		return if (buttonProducts.any { productStableKey(it) == ourKey }) StoreVerdict.AVAILABLE else StoreVerdict.UNAVAILABLE
	}

	internal fun verdictFromStatus(statusCode: Int, body: String, productId: String): StoreVerdict = when (statusCode)
	{
		200 -> verdictFromPage(body, productId)
		404, 410 -> StoreVerdict.UNAVAILABLE
		else -> StoreVerdict.UNKNOWN
	}

	suspend fun check(conceptUrl: String, productId: String): StoreVerdict = withContext(Dispatchers.IO)
	{
		try
		{
			val response = HttpClient.get(conceptUrl, headers = mapOf("User-Agent" to USER_AGENT), timeoutMs = TIMEOUT_MS)
			verdictFromStatus(response.statusCode, response.body, productId)
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Availability check failed for $productId: ${e.message}")
			StoreVerdict.UNKNOWN
		}
	}
}
