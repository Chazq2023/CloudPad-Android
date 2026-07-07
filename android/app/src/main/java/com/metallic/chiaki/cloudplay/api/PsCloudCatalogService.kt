// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.api

import android.util.Log
import com.metallic.chiaki.cloudplay.PsnApiConstants
import com.metallic.chiaki.cloudplay.model.CloudGame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

data class Ps5CloudCatalogResult(
	val browseGames: List<CloudGame>,
	val plusLibrarySupplement: List<CloudGame>,
	val productIdAliases: Map<String, String> = emptyMap(),
	val catalogFetchWarning: String? = null,
)

/**
 * PsCloudCatalogService - PS5 cloud catalog fetching (imagic gameslist).
 * Fetches PS4/PS5 streaming games from multiple Sony imagic category lists.
 * Mirrors: gui/src/cloudcatalogbackend.cpp
 */
class PsCloudCatalogService
{
	companion object
	{
		private const val TAG = "PsCloudCatalogService"
		private const val ACCOUNT_BASE = "https://ca.account.sony.com/api"
		private const val IMAGIC_GAMESLIST_BASE = "https://www.playstation.com/bin/imagic/gameslist"

		// Lists fetched in parallel. plus-* lists are the PS Plus subscription catalog.
		// all-ps5-list is the full streamable universe (PS4 + PS5).
		private val IMAGIC_CATEGORY_LISTS = listOf(
			"plus-games-list",
			"ubisoft-classics-list",
			"plus-classics-list",
			"plus-monthly-games-list",
			"free-to-play-list",
			"all-ps5-list",
		)
	}

	suspend fun fetchPs5CloudCatalog(locale: String): Ps5CloudCatalogResult = coroutineScope {
		Log.i(TAG, "=== Fetching PS5/PS4 Game Catalog (${IMAGIC_CATEGORY_LISTS.size} imagic lists) ===")
		Log.i(TAG, "  Locale: $locale")

		val byEditionKey = LinkedHashMap<String, JSONObject>()
		val plusSupplementByProductId = LinkedHashMap<String, JSONObject>()
		val productIdAliases = LinkedHashMap<String, String>()
		var totalGames = 0
		val failedLists = mutableListOf<String>()

		IMAGIC_CATEGORY_LISTS.map { categoryList ->
			async {
				try
				{
					categoryList to fetchImagicCategoryList(locale, categoryList)
				}
				catch (e: Exception)
				{
					Log.w(TAG, "Imagic list '$categoryList' failed: ${e.message}")
					categoryList to null
				}
			}
		}.awaitAll().forEach { (categoryList, jsonArray) ->
			if (jsonArray == null)
			{
				failedLists.add(categoryList)
				return@forEach
			}
			totalGames += mergeImagicCategoryIntoMap(
				categoryList, jsonArray, byEditionKey, plusSupplementByProductId, productIdAliases
			)
		}

		if (failedLists.size == IMAGIC_CATEGORY_LISTS.size)
			throw Exception("All imagic category lists failed to load")

		val browseGames = byEditionKey.values.mapNotNull { jsonToCloudGame(it) }
		val plusLibrarySupplement = plusSupplementByProductId.values.mapNotNull { jsonToCloudGame(it) }

		val catalogFetchWarning = if (failedLists.isEmpty()) null
			else "Some catalog lists failed (${failedLists.joinToString()}). Catalog may be incomplete."

		Log.i(TAG, "  Imagic rows scanned: $totalGames")
		Log.i(TAG, "  Streaming games (deduped by edition key): ${browseGames.size}")
		Log.i(TAG, "  Plus library supplement (streamingSupported=false): ${plusLibrarySupplement.size}")
		Log.i(TAG, "  Product ID aliases (same edition): ${productIdAliases.size}")
		if (catalogFetchWarning != null)
			Log.w(TAG, "  $catalogFetchWarning")

		Ps5CloudCatalogResult(browseGames, plusLibrarySupplement, productIdAliases, catalogFetchWarning)
	}

	private suspend fun fetchImagicCategoryList(locale: String, categoryList: String): JSONArray
	{
		val url = "$IMAGIC_GAMESLIST_BASE?locale=$locale&categoryList=$categoryList"
		val response = HttpClient.get(
			url = url,
			headers = mapOf(
				"Content-Type" to "application/json",
				"Accept" to "application/json",
				"User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
			)
		)

		if (response.statusCode != 200)
		{
			Log.e(TAG, "Imagic list '$categoryList' error: ${response.statusCode}")
			throw Exception("Failed to fetch imagic list $categoryList: HTTP ${response.statusCode}")
		}

		return JSONArray(response.body)
	}

	private fun mergeImagicCategoryIntoMap(
		categoryList: String,
		jsonArray: JSONArray,
		byEditionKey: LinkedHashMap<String, JSONObject>,
		plusSupplementByProductId: LinkedHashMap<String, JSONObject>,
		productIdAliases: LinkedHashMap<String, String>,
	): Int
	{
		val plusCatalog = isPlusCatalogList(categoryList)
		var rows = 0
		for (i in 0 until jsonArray.length())
		{
			val games = jsonArray.getJSONObject(i).optJSONArray("games") ?: continue
			rows += games.length()
			for (j in 0 until games.length())
			{
				val gameObj = games.getJSONObject(j)
				// Accept PS4 and PS5 — the old PS5-only gate dropped PS4-only PS Plus titles
				if (!isCloudDeviceGame(gameObj))
					continue

				// Subscription titles with streamingSupported=false → library supplement only.
				// They can still be streamed by subscribers but don't appear in the browse catalog.
				if (plusCatalog && !gameObj.optBoolean("streamingSupported", false))
				{
					val productId = gameObj.optString("productId", "")
					if (productId.isNotEmpty())
					{
						gameObj.put("plusCatalog", true)
						plusSupplementByProductId.putIfAbsent(productId, gameObj)
					}
					continue
				}

				if (!isCloudStreamingGame(gameObj))
					continue

				val key = editionKey(gameObj)
				val productId = gameObj.optString("productId", "")
				if (key.isEmpty() || productId.isEmpty())
					continue

				if (byEditionKey.containsKey(key))
				{
					val existing = byEditionKey[key]
					val canonicalProductId = existing?.optString("productId", "") ?: ""
					if (canonicalProductId.isNotEmpty() && productId != canonicalProductId
						&& !productIdAliases.containsKey(productId))
					{
						productIdAliases[productId] = canonicalProductId
					}
					if (plusCatalog && existing != null && !existing.optBoolean("plusCatalog", false))
						existing.put("plusCatalog", true)
					continue
				}

				gameObj.put("plusCatalog", plusCatalog)
				byEditionKey[key] = gameObj
			}
		}
		return rows
	}

	// Subscription catalog lists — NOT the full streamable universe (all-ps5-list is)
	private fun isPlusCatalogList(categoryList: String): Boolean =
		categoryList == "plus-games-list" || categoryList == "plus-classics-list" ||
			categoryList == "ubisoft-classics-list" || categoryList == "plus-monthly-games-list"

	private fun isCloudDeviceGame(gameObj: JSONObject): Boolean
	{
		val devices = gameObj.optJSONArray("device") ?: return false
		for (i in 0 until devices.length())
		{
			val d = devices.optString(i)
			if (d == "PS5" || d == "PS4") return true
		}
		return false
	}

	private fun isCloudStreamingGame(gameObj: JSONObject): Boolean
	{
		if (!gameObj.optBoolean("streamingSupported", false)) return false
		return isCloudDeviceGame(gameObj)
	}

	private fun conceptKey(gameObj: JSONObject): String
	{
		if (gameObj.has("conceptId") && !gameObj.isNull("conceptId"))
		{
			when (val raw = gameObj.get("conceptId"))
			{
				is Number -> return raw.toLong().toString()
				is String -> if (raw.isNotEmpty()) return raw
			}
		}
		return gameObj.optString("productId", "")
	}

	private fun platformTokenFromProductId(productId: String): String = when
	{
		productId.contains("PPSA") -> "ps5"
		productId.contains("CUSA") -> "ps4"
		else -> ""
	}

	// One entry per game per platform: cross-gen PS4/PS5 editions each get their own card
	private fun editionKey(gameObj: JSONObject): String
	{
		val c = conceptKey(gameObj)
		if (c.isEmpty()) return ""
		return c + "|" + platformTokenFromProductId(gameObj.optString("productId", ""))
	}

	private fun jsonToCloudGame(gameObj: JSONObject): CloudGame?
	{
		val productId = gameObj.optString("productId", "")
		if (productId.isEmpty()) return null

		val gameName = gameObj.optString("name", "Unknown")
		var conceptUrl = gameObj.optString("conceptUrl", "")
		if (conceptUrl.isEmpty()) conceptUrl = gameObj.optString("concept_url", "")
		if (conceptUrl.isEmpty()) conceptUrl = gameObj.optString("url", "")
		if (conceptUrl.isEmpty()) conceptUrl = gameObj.optString("storeUrl", "")
		if (conceptUrl.isEmpty()) conceptUrl = gameObj.optString("psStoreUrl", "")
		if (conceptUrl.isEmpty()) conceptUrl = gameObj.optString("concept", "")
		if (conceptUrl.isEmpty())
		{
			val links = gameObj.optJSONObject("links")
			if (links != null)
			{
				conceptUrl = links.optString("conceptUrl", "")
					.ifEmpty { links.optString("concept_url", "") }
					.ifEmpty { links.optString("url", "") }
			}
		}
		if (conceptUrl.isEmpty())
		{
			val concept = gameObj.optJSONObject("concept")
			if (concept != null)
			{
				conceptUrl = concept.optString("url", "")
					.ifEmpty { concept.optString("href", "") }
			}
		}

		val imageUrl = gameObj.optString("imageUrl", "")
		val (coverUrl, landscapeUrl) = if (imageUrl.isNotEmpty())
			Pair(imageUrl, imageUrl)
		else
			extractImageUrls(gameObj)

		fun https(url: String) = if (url.startsWith("http://")) url.replace("http://", "https://") else url

		return CloudGame(
			productId = productId,
			name = gameName,
			imageUrl = https(coverUrl),
			landscapeImageUrl = https(landscapeUrl),
			platform = platformTokenFromProductId(productId).ifEmpty { "ps5" },
			serviceType = "pscloud",
			conceptUrl = conceptUrl,
			conceptId = conceptKey(gameObj),
			isOwned = false,
			plusCatalog = gameObj.optBoolean("plusCatalog", false)
		)
	}

	/**
	 * Fetch owned PS5 games for a user (library view).
	 * Fetches the full catalog then cross-references with the user's entitlements.
	 */
	suspend fun fetchOwnedPs5Games(npssoToken: String, locale: String): List<CloudGame>
	{
		if (npssoToken.isEmpty())
			throw Exception("NPSSO token is required for cloud play.")

		Log.i(TAG, "=== Fetching Owned PS5 Games ===")
		Log.i(TAG, "  Locale: $locale")

		val catalog = fetchPs5CloudCatalog(locale)
		val ownedGames = getOwnedPs5CloudGames(
			npssoToken,
			catalog.browseGames,
			catalog.plusLibrarySupplement,
			catalog.productIdAliases
		).filter { it.platform == "ps5" }

		Log.i(TAG, "  Owned PS5 streaming games: ${ownedGames.size}")
		return ownedGames
	}

	suspend fun getOwnedPs5CloudGames(
		npssoToken: String,
		publicCatalog: List<CloudGame>,
		plusLibrarySupplement: List<CloudGame> = emptyList(),
		productIdAliases: Map<String, String> = emptyMap(),
	): List<CloudGame>
	{
		if (npssoToken.isEmpty()) return emptyList()

		val oauthToken = fetchOwnedGamesOAuthToken(npssoToken)
		kotlinx.coroutines.delay(PsCloudOwnership.PAGE_COOLDOWN_MS)

		val rawEntitlements = fetchEntitlementsPaginated(oauthToken)
		val filtered = PsCloudOwnership.filterOwnedPs5Games(rawEntitlements)

		Log.i(TAG, "  Raw entitlements: ${rawEntitlements.size}, after feature_type filter: ${filtered.size}")

		val componentIds = mutableMapOf<String, MutableList<String>>()
		for (ent in rawEntitlements)
			if (ent.productId.isNotEmpty() && ent.id.isNotEmpty())
				componentIds.getOrPut(ent.productId) { mutableListOf() }.add(ent.id)

		return PsCloudOwnership.crossReferenceOwnedGames(
			filtered, publicCatalog, plusLibrarySupplement, productIdAliases, componentIds
		)
	}

	/**
	 * Cross-reference public catalog with owned entitlements to mark ownership status.
	 * Used for the "all games" view (shows everything, marks which are owned).
	 */
	suspend fun crossReferenceOwnedGamesForCatalog(
		npssoToken: String,
		locale: String,
		publicCatalog: List<CloudGame>
	): List<CloudGame>
	{
		if (npssoToken.isEmpty()) return publicCatalog.map { it.copy(isOwned = false) }

		try
		{
			val oauthToken = fetchOwnedGamesOAuthToken(npssoToken)
			kotlinx.coroutines.delay(PsCloudOwnership.PAGE_COOLDOWN_MS)

			val rawEntitlements = fetchEntitlementsPaginated(oauthToken)
			val filtered = PsCloudOwnership.filterOwnedPs5Games(rawEntitlements)

			val componentIds = mutableMapOf<String, MutableList<String>>()
			for (ent in rawEntitlements)
				if (ent.productId.isNotEmpty() && ent.id.isNotEmpty())
					componentIds.getOrPut(ent.productId) { mutableListOf() }.add(ent.id)

			val ownedGames = PsCloudOwnership.crossReferenceOwnedGames(
				filtered, publicCatalog, emptyList(), emptyMap(), componentIds
			)

			return PsCloudOwnership.mergeOwnedIntoBrowseCatalog(
				publicCatalog, ownedGames, addUnmatched = false
			)
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Failed to cross-reference ownership, returning games as not owned", e)
			return publicCatalog.map { it.copy(isOwned = false) }
		}
	}

	private suspend fun fetchOwnedGamesOAuthToken(npssoToken: String): String
	{
		Log.i(TAG, "=== Fetching OAuth token for owned games ===")

		val scope = "kamaji:get_internal_entitlements user:account.attributes.validate"
		val redirectUri = PsnApiConstants.REDIRECT_URI

		val url = java.net.URL("$ACCOUNT_BASE/v1/oauth/authorize")
		val query = "response_type=token&scope=${java.net.URLEncoder.encode(scope, "UTF-8")}&client_id=dc523cc2-b51b-4190-bff0-3397c06871b3&redirect_uri=${java.net.URLEncoder.encode(redirectUri, "UTF-8")}&service_entity=urn:service-entity:psn&prompt=none"
		val fullUrl = "$url?$query"

		val response = HttpClient.get(
			url = fullUrl,
			headers = mapOf(
				"Cookie" to "npsso=$npssoToken",
				"User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
			),
			followRedirects = false
		)

		if (response.statusCode != 302)
		{
			Log.e(TAG, "OAuth token fetch failed: ${response.statusCode}")
			throw Exception("Failed to fetch OAuth token: HTTP ${response.statusCode}")
		}

		val location = response.headers["Location"]?.firstOrNull()
			?: response.headers["location"]?.firstOrNull()
			?: ""

		if (location.isEmpty())
			throw Exception("No Location header in OAuth redirect")

		val tokenPattern = Regex("[#&]access_token=([^&]+)")
		val match = tokenPattern.find(location)
			?: throw Exception("Failed to extract OAuth token from response")

		val token = match.groupValues[1]
		Log.i(TAG, "✓ OAuth token obtained: ${token.take(20)}...")
		return token
	}

	private suspend fun fetchEntitlementsPaginated(oauthToken: String): List<PsCloudOwnership.Entitlement>
	{
		Log.i(TAG, "=== Fetching entitlements (paginated) ===")

		val all = mutableListOf<PsCloudOwnership.Entitlement>()
		var start = 0

		while (true)
		{
			val url = "https://commerce.api.np.km.playstation.net/commerce/api/v1/users/me/internal_entitlements?fields=game_meta&entitlement_type=5&start=$start&size=${PsCloudOwnership.PAGE_SIZE}"

			val response = HttpClient.get(
				url = url,
				headers = mapOf(
					"Authorization" to "Bearer $oauthToken",
					"Accept" to "application/json"
				)
			)

			if (response.statusCode != 200)
			{
				Log.e(TAG, "Entitlements fetch failed: ${response.statusCode}")
				throw Exception("Failed to fetch entitlements: HTTP ${response.statusCode}")
			}

			val jsonObj = JSONObject(response.body)
			val entitlementsArray = jsonObj.optJSONArray("entitlements") ?: JSONArray()
			val pageSize = entitlementsArray.length()

			for (i in 0 until pageSize)
			{
				PsCloudOwnership.parseEntitlement(entitlementsArray.getJSONObject(i))?.let { all.add(it) }
			}

			if (pageSize < PsCloudOwnership.PAGE_SIZE) break
			start += pageSize
			kotlinx.coroutines.delay(PsCloudOwnership.PAGE_COOLDOWN_MS)
		}

		Log.i(TAG, "  Entitlements count: ${all.size}")
		return all
	}

	private fun extractImageUrls(gameObj: JSONObject): Pair<String, String>
	{
		val imagesArray = gameObj.optJSONArray("images") ?: return Pair("", "")

		var coverUrl = ""
		var landscapeUrl = ""

		for (i in 0 until imagesArray.length())
		{
			val image = imagesArray.getJSONObject(i)
			val type = image.optInt("type", -1)
			val url = image.optString("url", "")

			if (url.isEmpty()) continue

			when (type)
			{
				10 -> if (coverUrl.isEmpty()) coverUrl = url
				12 -> if (landscapeUrl.isEmpty()) landscapeUrl = url
				13 -> if (landscapeUrl.isEmpty()) landscapeUrl = url
			}
		}

		if (landscapeUrl.isEmpty() && coverUrl.isNotEmpty()) landscapeUrl = coverUrl
		if (coverUrl.isEmpty() && landscapeUrl.isNotEmpty()) coverUrl = landscapeUrl

		return Pair(coverUrl, landscapeUrl)
	}
}
