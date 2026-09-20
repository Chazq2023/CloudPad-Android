// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.repository

import android.content.Context
import android.util.Log
import com.metallic.chiaki.cloudplay.api.PsCloudCatalogService
import com.metallic.chiaki.cloudplay.api.PsPlusCache
import com.metallic.chiaki.cloudplay.api.PsStorePlusService
import com.metallic.chiaki.cloudplay.api.PsnCatalogService
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.cloudplay.model.PsnResult
import com.metallic.chiaki.cloudplay.model.StreamableStatus
import com.metallic.chiaki.cloudplay.model.excludingLibrary
import com.metallic.chiaki.cloudplay.model.notInLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Outcome of [CloudGameRepository.loadPsPlusKeys]. */
sealed class PsPlusResult
{
	/** PPSA numbers of the PS5 titles included with PS Plus. [isFresh] is false when a newer
	 *  lookup was wanted (expired, or a forced refresh) but failed, so an older saved one is used.
	 *  [refreshSkipped] is true when a forced refresh was ignored because the lookup was refreshed
	 *  within the last hour (see [PsPlusCache.REFRESH_COOLDOWN_MS]). */
	data class Ready(val keys: Set<String>, val isFresh: Boolean = true, val refreshSkipped: Boolean = false) : PsPlusResult()
	data class Failed(val message: String) : PsPlusResult()
}

class CloudGameRepository(
	private val context: Context,
	private val preferences: com.metallic.chiaki.common.Preferences,
	// Fetches the PS Plus lookup for a store locale; swapped out in tests.
	private val fetchPsPlusKeys: suspend (storeLocale: String) -> Set<String> = PsStorePlusService::fetchPlusKeys
)
{
	companion object
	{
		private const val TAG = "CloudGameRepository"
		private const val CACHE_DIR = "cloud_catalog_cache"
		private const val PSNOW_CACHE_FILE = "psnow_catalog.json"
		private const val PSCLOUD_CACHE_FILE = "pscloud_catalog.json"
		private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

		/** True for a full-catalog cache written before psCatalog/freeToPlay were stored (see
		 *  loadCachedGames). Other cache files never carry the tags, so they never count as stale. */
		internal fun lacksCatalogTags(cacheFileName: String, cachedGames: JSONArray): Boolean =
			cacheFileName == PSCLOUD_CACHE_FILE && cachedGames.length() > 0 &&
				!cachedGames.getJSONObject(0).has("psCatalog")

		private const val PS_PLUS_CACHE_DIR = "ps_plus_cache"
		private const val PS_PLUS_CACHE_FILE = "ps_plus_keys.json"
		private const val PS_PLUS_FAILURE_FILE = "last_failure.txt"

		/** After a failed PS Plus lookup, don't try Sony's store API again for this long, however
		 *  often the page is opened or Refresh is tapped — a broken or blocking API shouldn't be
		 *  retried on every visit. */
		internal const val PS_PLUS_FAILURE_COOLDOWN_MS = 30L * 60 * 1000

		fun invalidateCatalogCache(context: Context, reason: String = "")
		{
			try
			{
				val dir = File(context.cacheDir, CACHE_DIR)
				// Delete only the cached files, not the directory itself — a repository instance's
				// `cacheDir` property creates the directory once (lazily) and never recreates it, so
				// removing the directory entry here would leave every later write failing silently
				// with ENOENT for the rest of that instance's lifetime.
				dir.listFiles()?.forEach { it.delete() }
				Log.i(TAG, "Catalog cache invalidated" + if (reason.isNotEmpty()) " ($reason)" else "")
			}
			catch (e: Exception)
			{
				Log.w(TAG, "Error invalidating catalog cache", e)
			}
		}
	}

	private val psnowCatalogService = PsnCatalogService(preferences)
	private val pscloudCatalogService = PsCloudCatalogService()
	private val cacheDir: File by lazy {
		File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }
	}

	suspend fun fetchPsnowCatalog(npssoToken: String, forceRefresh: Boolean = false): PsnResult<List<CloudGame>>
	{
		return withContext(Dispatchers.IO)
		{
			if (!forceRefresh)
			{
				val cachedGames = loadCachedGames(PSNOW_CACHE_FILE)
				if (cachedGames != null)
				{
					Log.i(TAG, "Returning ${cachedGames.size} PSNow games from cache")
					return@withContext PsnResult.Success(cachedGames)
				}
			}

			Log.i(TAG, "Fetching fresh PSNow catalog from network")
			val result = psnowCatalogService.fetchPsnowCatalog(npssoToken)

			if (result is PsnResult.Success)
				cacheGames(result.data, PSNOW_CACHE_FILE)

			result
		}
	}

	/**
	 * Fetch PS5 Cloud catalog (all games view) with ownership cross-reference.
	 */
	suspend fun fetchPs5CloudCatalog(npssoToken: String, forceRefresh: Boolean = false): PsnResult<List<CloudGame>>
	{
		return withContext(Dispatchers.IO)
		{
			if (!forceRefresh)
			{
				val cachedGames = loadCachedGames(PSCLOUD_CACHE_FILE)
				if (cachedGames != null)
				{
					Log.i(TAG, "Returning ${cachedGames.size} PS5 games from cache")
					return@withContext PsnResult.Success(cachedGames)
				}
			}

			Log.i(TAG, "Fetching fresh PS5 Cloud catalog from network")
			try
			{
				val localeSetting = preferences.getCloudStoreLocale()
				val locale = localeSetting.lowercase()

				val catalogResult = pscloudCatalogService.fetchPs5CloudCatalog(locale)
				val browseGames = catalogResult.browseGames

				val gamesWithOwnership = pscloudCatalogService.crossReferenceOwnedGamesForCatalog(
					npssoToken = npssoToken,
					locale = locale,
					publicCatalog = browseGames
				)

				cacheGames(gamesWithOwnership, PSCLOUD_CACHE_FILE)
				PsnResult.Success(gamesWithOwnership)
			}
			catch (e: Exception)
			{
				Log.e(TAG, "Failed to fetch PS5 catalog", e)
				PsnResult.Error("Failed to fetch PS5 catalog: ${e.message}", e)
			}
		}
	}

	/**
	 * Every known streamable PS5 game the user has NOT added to their library — backs the
	 * "add a game to library" page. Takes the full-catalog fetch (which cross-references the
	 * user's entitlements for isOwned) and additionally removes anything matching the library
	 * list itself, since the catalog flag misses titles whose names differ between the sources.
	 * The library is read first (cache unless it has to be fetched — a network fetch also drops
	 * the catalog cache, so the catalog below is then rebuilt with fresh ownership); if it can't
	 * be loaded, the catalog's own isOwned flag is all that's applied.
	 */
	suspend fun fetchPs5GamesNotInLibrary(npssoToken: String, forceRefresh: Boolean = false): PsnResult<List<CloudGame>>
	{
		val library = (fetchOwnedPs5Games(npssoToken, forceRefresh = false) as? PsnResult.Success)?.data ?: emptyList()
		return when (val result = fetchPs5CloudCatalog(npssoToken, forceRefresh))
		{
			is PsnResult.Success -> PsnResult.Success(result.data.notInLibrary().excludingLibrary(library))
			is PsnResult.Error -> result
		}
	}

	/**
	 * Which PS5 titles are included with PS Plus (as PPSA numbers), for the Add Game page's PS Plus
	 * filter. The lookup is a ~25 MB download, so it's cached for a week (per store locale, in its own
	 * directory so clearing the catalog cache doesn't discard it) unless [forceRefresh] asks for a new
	 * one (at most once an hour). If a new one can't be fetched, the saved lookup is still returned (not fresh) rather than nothing.
	 */
	suspend fun loadPsPlusKeys(forceRefresh: Boolean = false): PsPlusResult = withContext(Dispatchers.IO)
	{
		val storeLocale = preferences.getCloudStoreLocale()
		val cacheFile = File(File(context.cacheDir, PS_PLUS_CACHE_DIR).apply { mkdirs() }, PS_PLUS_CACHE_FILE)
		val cached = try { PsPlusCache.decode(cacheFile.readText()) } catch (e: Exception) { null }
			?.takeIf { it.storeLocale == storeLocale }

		val nowMs = System.currentTimeMillis()
		if (cached != null && forceRefresh && PsPlusCache.isInRefreshCooldown(cached, nowMs))
			return@withContext PsPlusResult.Ready(cached.keys, refreshSkipped = true)
		if (cached != null && !forceRefresh && PsPlusCache.isFresh(cached, nowMs))
			return@withContext PsPlusResult.Ready(cached.keys)

		val failureFile = File(cacheFile.parentFile, PS_PLUS_FAILURE_FILE)
		val lastFailureMs = try { failureFile.readText().trim().toLong() } catch (e: Exception) { null }
		if (lastFailureMs != null && nowMs - lastFailureMs in 0 until PS_PLUS_FAILURE_COOLDOWN_MS)
		{
			Log.i(TAG, "PS Plus lookup failed recently; not asking again yet")
			return@withContext cached?.let { PsPlusResult.Ready(it.keys, isFresh = false) }
				?: PsPlusResult.Failed("The PlayStation Store didn't respond a moment ago; try again in a few minutes.")
		}

		try
		{
			val keys = fetchPsPlusKeys(storeLocale)
			try { cacheFile.writeText(PsPlusCache.encode(PsPlusCache.Entry(storeLocale, System.currentTimeMillis(), keys))) }
			catch (e: Exception) { Log.w(TAG, "Could not cache the PS Plus lookup", e) }
			failureFile.delete()
			PsPlusResult.Ready(keys)
		}
		catch (e: Exception)
		{
			Log.w(TAG, "PS Plus lookup failed", e)
			try { failureFile.writeText(nowMs.toString()) } catch (io: Exception) { Log.w(TAG, "Could not record the failure time", io) }
			cached?.let { PsPlusResult.Ready(it.keys, isFresh = false) } ?: PsPlusResult.Failed(e.message ?: "unknown error")
		}
	}

	/**
	 * Fetch owned PS5 games (user's library) — only games the user can stream.
	 */
	suspend fun fetchOwnedPs5Games(npssoToken: String, forceRefresh: Boolean = false): PsnResult<List<CloudGame>>
	{
		return withContext(Dispatchers.IO)
		{
			val OWNED_CACHE_FILE = "pscloud_owned.json"

			if (!forceRefresh)
			{
				val cachedGames = loadCachedGames(OWNED_CACHE_FILE)
				if (cachedGames != null)
				{
					Log.i(TAG, "Returning ${cachedGames.size} owned PS5 games from cache")
					// A launch attempt may have recorded a confirmed override since this cache was
					// written — reconcile so it still takes effect without needing a full refetch.
					val overrides = preferences.getConfirmedStreamableOverrides()
					val reconciled = if (overrides.isEmpty()) cachedGames else cachedGames.map { game ->
						overrides[game.productId]?.let { streamable ->
							game.copy(streamableStatus = if (streamable) StreamableStatus.STREAMABLE else StreamableStatus.NOT_STREAMABLE)
						} ?: game
					}
					return@withContext PsnResult.Success(reconciled)
				}
			}

			Log.i(TAG, "Fetching owned PS5 games from network")
			try
			{
				val locale = preferences.getCloudStoreLocale().lowercase()
				val overrides = preferences.getConfirmedStreamableOverrides()
				val games = pscloudCatalogService.fetchOwnedPs5Games(npssoToken, locale, overrides)
				cacheGames(games, OWNED_CACHE_FILE)
				// Ownership just changed (e.g. a game was added on Sony's site), so the full-catalog
				// cache's isOwned flags are stale — drop it so the add-a-game list rebuilds from
				// fresh entitlements instead of still offering a game that's now in the library.
				File(cacheDir, PSCLOUD_CACHE_FILE).delete()
				PsnResult.Success(games)
			}
			catch (e: Exception)
			{
				Log.e(TAG, "Failed to fetch owned PS5 games", e)
				PsnResult.Error("Failed to fetch owned PS5 games: ${e.message}", e)
			}
		}
	}

	private fun loadCachedGames(cacheFileName: String): List<CloudGame>?
	{
		try
		{
			val cacheFile = File(cacheDir, cacheFileName)

			if (!cacheFile.exists())
			{
				Log.d(TAG, "No cache file found: $cacheFileName")
				return null
			}

			val cacheAge = System.currentTimeMillis() - cacheFile.lastModified()
			if (cacheAge > CACHE_DURATION_MS)
			{
				Log.d(TAG, "Cache expired (age: ${cacheAge / 1000}s)")
				cacheFile.delete()
				return null
			}

			val json = cacheFile.readText()
			val jsonArray = JSONArray(json)

			// A full-catalog cache written before the psCatalog/freeToPlay tags existed would have
			// every game reading as untagged (so the add-a-game filters would come up empty) —
			// treat it as missing so it's rebuilt with them. Only the catalog file carries the tags.
			if(lacksCatalogTags(cacheFileName, jsonArray))
			{
				Log.i(TAG, "Discarding pre-psCatalog catalog cache")
				cacheFile.delete()
				return null
			}
			val games = mutableListOf<CloudGame>()

			for (i in 0 until jsonArray.length())
			{
				val obj = jsonArray.getJSONObject(i)
				val landscapeImageUrl = obj.optString("landscapeImageUrl", obj.getString("imageUrl"))

				// Only restore PSRSVD0000000000 entitlements from cache — old PSNow standalone
				// entitlements are rejected by Gaikai for PS Plus Premium users.
				val cachedEntitlementId = obj.optString("entitlementId", "")
				val entitlementId = if (cachedEntitlementId.endsWith("PSRSVD0000000000")) cachedEntitlementId else ""

				games.add(CloudGame(
					productId = obj.getString("productId"),
					name = obj.getString("name"),
					imageUrl = obj.getString("imageUrl"),
					landscapeImageUrl = landscapeImageUrl,
					thumbnailUrl = obj.optString("thumbnailUrl", obj.getString("imageUrl")),
					platform = obj.optString("platform", "ps4"),
					serviceType = obj.optString("serviceType", "psnow"),
					conceptUrl = obj.optString("conceptUrl", ""),
					conceptId = obj.optString("conceptId", ""),
					isOwned = obj.optBoolean("isOwned", false),
					entitlementId = entitlementId,
					storeProductId = obj.optString("storeProductId", ""),
					plusCatalog = obj.optBoolean("plusCatalog", false),
					psCatalog = obj.optBoolean("psCatalog", false),
					freeToPlay = obj.optBoolean("freeToPlay", false),
					featureType = obj.optInt("featureType", 0),
					streamableStatus = try {
						StreamableStatus.valueOf(obj.optString("streamableStatus", "UNKNOWN"))
					} catch (e: IllegalArgumentException) {
						StreamableStatus.UNKNOWN
					}
				))
			}

			Log.i(TAG, "Loaded ${games.size} games from cache: $cacheFileName")
			return games
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Error loading cache: $cacheFileName", e)
			return null
		}
	}

	private fun cacheGames(games: List<CloudGame>, cacheFileName: String)
	{
		try
		{
			val jsonArray = JSONArray()

			for (game in games)
			{
				val obj = JSONObject()
				obj.put("productId", game.productId)
				obj.put("name", game.name)
				obj.put("imageUrl", game.imageUrl)
				obj.put("landscapeImageUrl", game.landscapeImageUrl)
				obj.put("thumbnailUrl", game.thumbnailUrl)
				obj.put("platform", game.platform)
				obj.put("serviceType", game.serviceType)
				obj.put("conceptUrl", game.conceptUrl)
				obj.put("conceptId", game.conceptId)
				obj.put("isOwned", game.isOwned)
				obj.put("entitlementId", game.entitlementId)
				obj.put("storeProductId", game.storeProductId)
				obj.put("plusCatalog", game.plusCatalog)
				obj.put("psCatalog", game.psCatalog)
				obj.put("freeToPlay", game.freeToPlay)
				obj.put("featureType", game.featureType)
				obj.put("streamableStatus", game.streamableStatus.name)
				jsonArray.put(obj)
			}

			val cacheFile = File(cacheDir, cacheFileName)
			cacheFile.writeText(jsonArray.toString())

			Log.i(TAG, "Cached ${games.size} games to: ${cacheFile.absolutePath}")
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Error caching games to $cacheFileName", e)
		}
	}

	fun clearCache()
	{
		try
		{
			cacheDir.listFiles()?.forEach { it.delete() }
			Log.i(TAG, "Cache cleared")
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Error clearing cache", e)
		}
	}
}
