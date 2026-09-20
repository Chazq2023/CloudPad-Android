// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.api

import android.util.Log
import com.metallic.chiaki.cloudplay.model.productStableKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Finds which PS5 games are included with PS Plus, for the Add Game page's PS Plus filter.
 *
 * Sony's imagic lists (see [PsCloudCatalogService]) only name ~130 PS Plus titles, so they miss
 * most of the Game Catalog. The PS Store's own PS5 games listing carries a PS Plus tag on each
 * title, but the tag covers several different things — see [isIncludedWithPsPlus] — so this reads
 * that listing, keeps only the titles genuinely included with the subscription, and returns their
 * PPSA numbers. It is only used to TAG games the app already knows are streamable; nothing from
 * the listing is added to the catalog.
 *
 * The listing is big (~3.6 KB a title, ~25 MB for all of PS5 full games, uncompressed), so
 * CloudGameRepository caches the result for a week and only refetches it when that expires or the
 * user refreshes. It goes through the same
 * unauthenticated web API the store website uses; the persisted-query hash below is that site's,
 * and if Sony rotates it the fetch fails and the filter reports the data as unavailable.
 */
internal object PsStorePlusService
{
	private const val TAG = "PsStorePlusService"
	private const val GRAPHQL_URL = "https://web.np.playstation.com/api/graphql/v1/op"
	private const val PERSISTED_QUERY_HASH = "9845afc0dbaab4965f6563fffc703f588c8e76792000e8610843b8d3ee9c4c09"

	/** The store's "PS5 games" category. */
	private const val PS5_GAMES_CATEGORY = "4cbf39e2-5749-4970-ba81-93a489e4570c"
	private const val PAGE_SIZE = 200
	private const val CONCURRENCY = 6
	private const val PAGE_ATTEMPTS = 3
	private const val PAGE_TIMEOUT_MS = 45_000

	/** Sanity floor: PS5 has a couple of hundred included titles; far fewer means Sony changed the
	 *  data, and an empty PS Plus filter would be worse than reporting it as unavailable. */
	internal const val MIN_EXPECTED_KEYS = 80

	internal data class Page(val totalCount: Int, val plusKeys: Set<String>)

	/**
	 * Whether a listing entry's price says the game is *included* with PS Plus. The PS_PLUS branding
	 * on its own isn't enough — `upsellText` says what the subscription actually offers:
	 *  - "Extra": the Game Catalog — included.
	 *  - "Essential": a monthly game — included.
	 *  - "Included", or a price tied to the subscription (`serviceBranding` has PS_PLUS): included.
	 *  - "Premium": a time-limited game trial (or a Classics title), not the full game — NOT included.
	 *  - "Save 10% more" and similar: a member discount — NOT included.
	 */
	internal fun isIncludedWithPsPlus(price: JSONObject): Boolean
	{
		if (hasPsPlusBranding(price.optJSONArray("serviceBranding"))) return true
		return hasPsPlusBranding(price.optJSONArray("upsellServiceBranding")) &&
			price.optString("upsellText") in INCLUDED_UPSELL_TEXTS
	}

	private val INCLUDED_UPSELL_TEXTS = setOf("Extra", "Essential", "Included")

	internal fun pageUrl(offset: Int, size: Int = PAGE_SIZE): String
	{
		val variables = JSONObject()
			.put("id", PS5_GAMES_CATEGORY)
			.put("pageArgs", JSONObject().put("size", size).put("offset", offset))
			.put("sortBy", JSONObject.NULL)
			.put("filterBy", JSONArray().put("storeDisplayClassification:FULL_GAME"))
			.put("facetOptions", JSONArray())
		val extensions = JSONObject().put(
			"persistedQuery", JSONObject().put("version", 1).put("sha256Hash", PERSISTED_QUERY_HASH)
		)
		fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
		return "$GRAPHQL_URL?operationName=categoryGridRetrieve&variables=${enc(variables.toString())}&extensions=${enc(extensions.toString())}"
	}

	/** Parses one page of the category listing; throws [IOException] if it carries an API error. */
	internal fun parsePage(body: String): Page
	{
		val root = JSONObject(body)
		root.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { errors ->
			throw IOException("Store API error: ${errors.getJSONObject(0).optString("message", "unknown")}")
		}
		val grid = root.optJSONObject("data")?.optJSONObject("categoryGridRetrieve")
			?: throw IOException("Store API returned no category data")
		val total = grid.optJSONObject("pageInfo")?.optInt("totalCount", 0) ?: 0
		val products = grid.optJSONArray("products") ?: JSONArray()
		val keys = HashSet<String>()
		for (i in 0 until products.length())
		{
			val product = products.getJSONObject(i)
			val price = product.optJSONObject("price") ?: continue
			if (isIncludedWithPsPlus(price))
				productStableKey(product.optString("id", ""))?.let { keys.add(it) }
		}
		return Page(total, keys)
	}

	private fun hasPsPlusBranding(branding: JSONArray?): Boolean
	{
		if (branding == null) return false
		for (i in 0 until branding.length())
			if (branding.optString(i) == "PS_PLUS") return true
		return false
	}

	private fun fetchPage(offset: Int, storeLocale: String): Page
	{
		var lastError: Exception? = null
		repeat(PAGE_ATTEMPTS) {
			try
			{
				val response = HttpClient.get(
					url = pageUrl(offset),
					headers = mapOf(
						"x-psn-store-locale-override" to storeLocale,
						"Content-Type" to "application/json",
						"Accept" to "application/json"
					),
					timeoutMs = PAGE_TIMEOUT_MS
				)
				if (response.statusCode != 200) throw IOException("HTTP ${response.statusCode}")
				return parsePage(response.body)
			}
			catch (e: Exception)
			{
				lastError = e
				Log.w(TAG, "Page at offset $offset failed (${e.message})")
			}
		}
		throw IOException("Could not load PS Store listing at offset $offset", lastError)
	}

	/**
	 * The PPSA numbers of every PS5 full game the store tags as included with PS Plus, for the
	 * store region [storeLocale] (e.g. "en-GB"). Must be the same locale the catalog was fetched
	 * with: a title's PPSA number differs between regions (US vs EU), so keys only line up within one.
	 */
	suspend fun fetchPlusKeys(storeLocale: String): Set<String> = coroutineScope {
		val first = fetchPage(0, storeLocale)
		val gate = Semaphore(CONCURRENCY)
		val rest = (PAGE_SIZE until first.totalCount step PAGE_SIZE).map { offset ->
			async(Dispatchers.IO) { gate.withPermit { fetchPage(offset, storeLocale) } }
		}.awaitAll()

		val keys = HashSet<String>(first.plusKeys)
		rest.forEach { keys.addAll(it.plusKeys) }
		if (keys.size < MIN_EXPECTED_KEYS)
			throw IOException("Only ${keys.size} PS Plus titles found; the store data looks different than expected")
		Log.i(TAG, "PS Plus titles found: ${keys.size} (of ${first.totalCount} PS5 full games)")
		keys
	}
}

/** On-disk form of the PS Plus lookup, kept apart from the catalog cache so a library refresh or a
 *  cache clear never triggers another large download. */
internal object PsPlusCache
{
	internal data class Entry(val storeLocale: String, val fetchedAtMs: Long, val keys: Set<String>)

	const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

	/** Bumped whenever the rule for what counts as included changes, so a lookup saved under an
	 *  older rule (which would tag the wrong games) is discarded and fetched again. */
	const val RULE_VERSION = 2

	fun encode(entry: Entry): String = JSONObject()
		.put("storeLocale", entry.storeLocale)
		.put("fetchedAtMs", entry.fetchedAtMs)
		.put("ruleVersion", RULE_VERSION)
		.put("keys", JSONArray(entry.keys.toList()))
		.toString()

	fun decode(text: String): Entry?
	{
		return try
		{
			val obj = JSONObject(text)
			if (obj.optInt("ruleVersion", 0) != RULE_VERSION) return null
			val array = obj.getJSONArray("keys")
			Entry(
				obj.getString("storeLocale"), obj.getLong("fetchedAtMs"),
				(0 until array.length()).mapTo(HashSet()) { array.getString(it) }
			)
		}
		catch (e: Exception) { null }
	}

	fun isFresh(entry: Entry, nowMs: Long): Boolean = nowMs - entry.fetchedAtMs in 0 until MAX_AGE_MS
}
