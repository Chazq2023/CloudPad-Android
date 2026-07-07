// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.repository

import android.content.Context
import android.util.Log
import com.metallic.chiaki.cloudplay.api.PsCloudCatalogService
import com.metallic.chiaki.cloudplay.api.PsnCatalogService
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.cloudplay.model.PsnResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CloudGameRepository(
	private val context: Context,
	private val preferences: com.metallic.chiaki.common.Preferences
)
{
	companion object
	{
		private const val TAG = "CloudGameRepository"
		private const val CACHE_DIR = "cloud_catalog_cache"
		private const val PSNOW_CACHE_FILE = "psnow_catalog.json"
		private const val PSCLOUD_CACHE_FILE = "pscloud_catalog.json"
		private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

		fun invalidateCatalogCache(context: Context, reason: String = "")
		{
			try
			{
				val dir = File(context.cacheDir, CACHE_DIR)
				if (dir.exists()) dir.deleteRecursively()
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
					return@withContext PsnResult.Success(cachedGames)
				}
			}

			Log.i(TAG, "Fetching owned PS5 games from network")
			try
			{
				val localeSetting = preferences.getCloudStoreLocale()
				val locale = localeSetting.lowercase()

				val games = pscloudCatalogService.fetchOwnedPs5Games(npssoToken, locale)
				cacheGames(games, OWNED_CACHE_FILE)
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
					featureType = obj.optInt("featureType", 0)
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
				obj.put("featureType", game.featureType)
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
