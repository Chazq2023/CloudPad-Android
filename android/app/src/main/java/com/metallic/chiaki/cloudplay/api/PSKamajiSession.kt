// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.api

import android.util.Log
import com.metallic.chiaki.cloudplay.PsnApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * PSKamajiSession - Handles PlayStation Cloud Gaming Kamaji Authentication (Steps 1-6)
 * 
 * Kamaji is Sony's authentication layer for cloud gaming. This class:
 * - Creates and manages cookie-based sessions
 * - Handles OAuth2 authorization flow
 * - Integrates with Sony's account system
 * 
 * Mirrors: gui/src/cloudstreaming/pskamajisession.cpp
 */
class PSKamajiSession(
	private val duid: String,
	private val productId: String,
	private val accountBaseUrl: String,
	private val redirectUri: String,
	private val userAgent: String,
	private val preferences: com.metallic.chiaki.common.Preferences,
	private val preKnownEntitlementId: String = ""
)
{
	companion object
	{
		private const val TAG = "PSKamajiSession"
	}
	
	// Configuration
	private val kamajiBase = PsnApiConstants.KAMAJI_BASE
	private val storeBase = PsnApiConstants.STORE_BASE
	private val commerceBase = PsnApiConstants.COMMERCE_BASE
	private val kamajiClientId = PsnApiConstants.CLIENT_ID
	private var platform = "ps4" // Default, will be detected from API response
	private var scopesStr = PsnApiConstants.PS4_SCOPES // Default to PS4 scopes
	
	// State tracking
	private var anonAuthCode: String? = null      // OAuth code for anonymous session
	private var authorizationCode: String? = null // OAuth code for authenticated session
	private var jsessionId: String? = null        // JSESSIONID from anonymous session
	private var entitlementId: String? = null     // Converted from productId
	private var streamingSku: String? = null      // SKU from product ID conversion
	private var productLookupDiag: String = ""    // Diagnostic info from step0_5d for error messages
	private var isEntitlementDerived = false      // True when entitlement ID was guessed via IV0000 prefix
	private var commerceSearchDiag: String = ""  // Result of step0_5e.1b search, for error surfacing
	
	/**
	 * Data class for session result
	 */
	data class SessionResult(
		val success: Boolean,
		val message: String,
		val entitlementId: String = "",
		val platform: String = ""
	)
	
	/**
	 * Start the complete Kamaji session creation flow (Steps 0.5a-0.5d, 5-6)
	 * Mirrors: PSKamajiSession::startSessionCreation()
	 */
	suspend fun startSessionCreation(npssoToken: String): SessionResult = withContext(Dispatchers.IO)
	{
		try
		{
			Log.i(TAG, "=== Starting Kamaji Session Creation ===")
			Log.i(TAG, "Product ID: $productId")
			Log.i(TAG, "DUID: ${duid.take(20)}...")
			
			if (npssoToken.isEmpty())
			{
				return@withContext SessionResult(false, "NPSSO token is empty")
			}
			
			// Step 0.5b: Get Anonymous Auth Code
			val anonCode = step0_5b_GetAnonymousAuthCode(npssoToken)
				?: return@withContext SessionResult(false, "Failed to get anonymous auth code")
			anonAuthCode = anonCode
			Log.i(TAG, "✓ Step 0.5b complete - Got anonymous auth code")
			
			// Step 0.5c: Create Anonymous Session
			val sessionId = step0_5c_CreateAnonymousSession(anonCode)
				?: return@withContext SessionResult(false, "Failed to create anonymous session")
			jsessionId = sessionId
			Log.i(TAG, "✓ Step 0.5c complete - Got JSESSIONID: ${sessionId.take(10)}...")
			
			// Step 0.5d: Convert Product ID to Entitlement ID
			val conversionResult = step0_5d_ConvertProductId(sessionId)
				?: return@withContext SessionResult(false, "Failed to convert product ID: $productLookupDiag".take(200))
		entitlementId = conversionResult.first
		platform = conversionResult.second
		streamingSku = conversionResult.third
		Log.i(TAG, "✓ Step 0.5d complete - Entitlement ID: $entitlementId, Platform: $platform")
		
		// Update scopes if PS3
		if (platform == "ps3")
		{
			scopesStr = "kamaji:commerce_native" // PS3_SCOPES
		}
		
		// Step 0.5e: Check and acquire entitlement if needed
		val entitlementCheckResult = step0_5e_CheckAndAcquireEntitlement(npssoToken, sessionId)
		if (!entitlementCheckResult)
		{
			return@withContext SessionResult(false, "Failed to check/acquire entitlement")
		}
		Log.i(TAG, "✓ Step 0.5e complete - Entitlement check/acquisition successful")
		
		// Step 5: Get Auth Code
		val authCode = step5_GetAuthCode(npssoToken)
			?: return@withContext SessionResult(false, "Failed to get auth code")
		authorizationCode = authCode
		Log.i(TAG, "✓ Step 5 complete - Got auth code")
			
			// Step 6: Create Auth Session
			val authSession = step6_CreateAuthSession(authCode)
				?: return@withContext SessionResult(false, "Failed to create authenticated session")
			Log.i(TAG, "✓ Step 6 complete - Authenticated session created")
			
			// Session complete
			Log.i(TAG, "=== Kamaji Session Complete ===")
			Log.i(TAG, "Entitlement ID: $entitlementId")
			Log.i(TAG, "Platform: $platform")
			
		SessionResult(true, "eid=${entitlementId} search=${commerceSearchDiag.take(80)}", entitlementId!!, platform)
	}
	catch (e: PsPlusSubscriptionException)
	{
		// Re-throw subscription exceptions so they bubble up to UI
		Log.e(TAG, "Kamaji session PS Plus subscription error", e)
		throw e
	}
	catch (e: Exception)
	{
		Log.e(TAG, "Kamaji session error", e)
		SessionResult(false, "Exception: ${e.message}")
	}
	}
	
	/**
	 * Step 0.5b: Get Anonymous Auth Code
	 * GET /oauth/authorize (for anonymous session code)
	 * Mirrors: PSKamajiSession::step0_5b_GetAnonymousAuthCode()
	 */
	private fun step0_5b_GetAnonymousAuthCode(npssoToken: String): String?
	{
		try
		{
			// Build URL with query parameters (manual encoding)
			val params = listOf(
				"smcid" to "pc:psnow",
				"applicationId" to "psnow",
				"response_type" to "code",
				"scope" to scopesStr,
				"client_id" to kamajiClientId,
				"redirect_uri" to redirectUri,
				"service_entity" to "urn:service-entity:psn",
				"prompt" to "none",
				"renderMode" to "mobilePortrait",
				"hidePageElements" to "forgotPasswordLink",
				"displayFooter" to "none",
				"disableLinks" to "qriocityLink",
				"mid" to "PSNOW",
				"duid" to duid,
				"layout_type" to "popup",
				"service_logo" to "ps",
				"tp_psn" to "true",
				"noEVBlock" to "true"
			)
			
			val query = params.joinToString("&") { (key, value) ->
				"$key=${java.net.URLEncoder.encode(value, "UTF-8")}"
			}
			
			val url = "$accountBaseUrl/v1/oauth/authorize?$query"
			
			Log.d(TAG, "Step 0.5b: GET /oauth/authorize (anonymous)")
			Log.d(TAG, "URL: $url")
			
			val headers = mapOf(
				"User-Agent" to userAgent,
				"Cookie" to "npsso=$npssoToken"
			)
			
			val response = HttpClient.get(url, headers, followRedirects = false)
			
			Log.d(TAG, "Step 0.5b Response: ${response.statusCode}")
			
			if (response.statusCode != 302)
			{
				Log.e(TAG, "Expected 302 redirect, got ${response.statusCode}")
				return null
			}
			
			val location = HttpClient.extractLocation(response.headers)
			if (location == null)
			{
				Log.e(TAG, "No Location header in redirect")
				return null
			}
			
			Log.d(TAG, "Redirect location: $location")
			
			val codeRegex = Regex("[?&]code=([^&]+)")
			val match = codeRegex.find(location)
			val code = match?.groupValues?.get(1)
			
			if (code.isNullOrEmpty())
			{
				Log.e(TAG, "No code parameter in redirect URL")
				return null
			}
			
			return code
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Step 0.5b error", e)
			return null
		}
	}
	
	/**
	 * Step 0.5c: Create Anonymous Session
	 * POST /user/session (anonymous, with OAuth code)
	 * Mirrors: PSKamajiSession::step0_5c_CreateAnonymousSession()
	 */
	private fun step0_5c_CreateAnonymousSession(authCode: String): String?
	{
		try
		{
			val url = "$kamajiBase/user/session"
			val body = "code=$authCode&client_id=$kamajiClientId&duid=$duid"
			
			Log.d(TAG, "Step 0.5c: POST /user/session (anonymous)")
			Log.d(TAG, "URL: $url")
			Log.d(TAG, "Body: $body")
			
			val headers = mapOf(
				"Content-Type" to "text/plain;charset=UTF-8",
				"User-Agent" to userAgent,
				"X-Alt-Referer" to redirectUri,
				"Accept" to "*/*",
				"Origin" to PsnApiConstants.ORIGIN,
				"Referer" to PsnApiConstants.REFERER
			)
			
			val response = HttpClient.post(url, body, headers)
			
		Log.d(TAG, "Step 0.5c Response: ${response.statusCode}")
		Log.d(TAG, "Response body: ${response.body.take(200)}")
		
		if (response.statusCode != 200)
		{
			Log.e(TAG, "Anonymous session failed: ${response.statusCode}")
			return null
		}
		
		// Extract JSESSIONID from Set-Cookie header
		val jsessionId = HttpClient.extractCookie(response.headers, "JSESSIONID")
		if (jsessionId.isNullOrEmpty())
		{
			Log.e(TAG, "No JSESSIONID in response")
			return null
		}
		
		// Save country and language from session response to settings (Qt CloudCatalogBackend lines 432-440)
		try
		{
			val json = JSONObject(response.body)
			val data = json.optJSONObject("data")
			if (data != null)
			{
				val sessionCountry = data.optString("country")
				val sessionLanguage = data.optString("language")
				
				if (!sessionCountry.isNullOrEmpty() && !sessionLanguage.isNullOrEmpty())
				{
					preferences.setCloudLanguageFromSession(sessionLanguage, sessionCountry)
					Log.i(TAG, "Saved locale from session: ${preferences.getCloudLanguage()}")
				}
			}
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Could not parse/save locale from session response", e)
		}
		
		return jsessionId
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Step 0.5c error", e)
			return null
		}
	}
	
	/**
	 * Step 0.5d: Convert Product ID
	 * GET /store/api/pcnow/.../container/.../{PRODUCT_ID}
	 * Mirrors: PSKamajiSession::step0_5d_ConvertProductId()
	 * Returns: Triple<EntitlementID, Platform, StreamingSKU>
	 */
	private fun step0_5d_ConvertProductId(sessionId: String): Triple<String, String, String>?
	{
		// If the entitlement was already extracted from the catalog, skip the store lookup.
		if (preKnownEntitlementId.isNotEmpty())
		{
			Log.i(TAG, "Step 0.5d: Using pre-known entitlement '$preKnownEntitlementId' for '$productId'")
			// Detect platform from the 4-letter title ID prefix embedded in the product ID.
			// PS4 = CUSA, PS5 = PPSA; everything else (BLES, BLUS, BCES, NPEA, NPUB, etc.) = PS3.
			// Platform matters: PSGaikaiStreaming uses it to pick the correct OAuth scope in step 8b
			// (PS3 requires kamaji:commerce_native without duid; PS4/PS5 use sso:none with duid).
			val titlePrefix = Regex("-([A-Z]{4})\\d").find(productId)?.groupValues?.get(1)
			val detectedPlatform = when (titlePrefix) {
				"PPSA" -> "ps5"
				"CUSA" -> "ps4"
				else -> "ps3"
			}
			Log.i(TAG, "Step 0.5d: Detected platform '$detectedPlatform' from product ID")
			return Triple(preKnownEntitlementId, detectedPlatform, "")
		}

		try
		{
		// Get locale from unified language setting (Qt line 321: GetCloudLanguagePSCloud)
		// Qt uses ONE setting for both PSNow and PSCloud
		val localeSetting = preferences.getCloudLanguage() // Default "en-US"
		val locale = localeSetting.lowercase() // Convert "en-US" to "en-us"
		
		// Extract country and language from locale (e.g., "en-us" -> "US", "en")
		val localeParts = locale.split("-")
		val country = if (localeParts.size > 1) localeParts[1].uppercase() else "US"
		val language = if (localeParts.isNotEmpty()) localeParts[0].lowercase() else "en"
		
		Log.i(TAG, "Using locale from settings: $localeSetting -> country=$country, language=$language")
		
		val url = "$storeBase/container/$country/$language/19/$productId?useOffers=true&gkb=1&gkb2=1"
			
			Log.i(TAG, "Step 0.5d: Convert Product ID '$productId' -> URL: $url")
			
			val headers = mapOf(
				"Accept" to "application/json",
				"User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
				"Cookie" to "JSESSIONID=$sessionId"
			)

			val response = HttpClient.get(url, headers)
			
			Log.i(TAG, "Step 0.5d Response: ${response.statusCode}")
			
			if (response.statusCode == 404)
			{
				Log.e(TAG, "Product ID not found (404)")
				productLookupDiag = "HTTP 404 — product '$productId' not found in PSNow store"
				return null
			}

			if (response.statusCode != 200)
			{
				Log.e(TAG, "Product lookup failed: ${response.statusCode}")
				productLookupDiag = "HTTP ${response.statusCode} from PSNow store for '$productId'"
				return null
			}
			
			val json = JSONObject(response.body)

			val topLevelKeys = json.keys().asSequence().toList()
			val linksCount = json.optJSONArray("links")?.length() ?: 0
			Log.i(TAG, "Step 0.5d response top-level keys: $topLevelKeys")
			Log.i(TAG, "Step 0.5d response links count: $linksCount")
			Log.i(TAG, "Step 0.5d response body (first 1500): ${response.body.take(1500)}")

			// Extract entitlement ID and SKU
			var streamingEntitlementId = ""
			var sku = ""
			var detectedPlatform = "ps4" // Default

			// The PSNow store API may return the product at root level or nested inside links[0].
			// Build a list of JSON objects to search for entitlements.
			val candidates = mutableListOf<JSONObject>()
			candidates.add(json)
			json.optJSONArray("links")?.let { links ->
				for (i in 0 until links.length())
				{
					links.optJSONObject(i)?.let { candidates.add(it) }
				}
			}

			for (candidate in candidates)
			{
				if (streamingEntitlementId.isNotEmpty()) break

				// Look for streaming entitlement - check default_sku first, then skus array
				// Streaming entitlements have license_type == 4
				if (candidate.has("default_sku"))
				{
					val defaultSku = candidate.getJSONObject("default_sku")
					if (defaultSku.has("entitlements"))
					{
						val entitlements = defaultSku.getJSONArray("entitlements")
						for (i in 0 until entitlements.length())
						{
							val ent = entitlements.getJSONObject(i)
							val licenseType = ent.optInt("license_type", -1)

							if (licenseType == 4)
							{
								val entId = ent.optString("id", "")
								if (entId.isNotEmpty())
								{
									streamingEntitlementId = entId
									sku = defaultSku.optString("id", "")
									Log.i(TAG, "Found streaming Entitlement ID from default_sku: $streamingEntitlementId")
									Log.i(TAG, "License Type: $licenseType")
									Log.i(TAG, "SKU: $sku")
									break
								}
							}
						}
					}
				}

				// If not found in default_sku, check all SKUs in the skus array
				if (streamingEntitlementId.isEmpty() && candidate.has("skus"))
				{
					val skus = candidate.getJSONArray("skus")
					for (i in 0 until skus.length())
					{
						val skuObj = skus.getJSONObject(i)
						if (skuObj.has("entitlements"))
						{
							val entitlements = skuObj.getJSONArray("entitlements")
							for (j in 0 until entitlements.length())
							{
								val ent = entitlements.getJSONObject(j)
								val licenseType = ent.optInt("license_type", -1)

								if (licenseType == 4)
								{
									val entId = ent.optString("id", "")
									if (entId.isNotEmpty())
									{
										streamingEntitlementId = entId
										sku = skuObj.optString("id", "")
										Log.i(TAG, "Found streaming Entitlement ID from skus array: $streamingEntitlementId")
										Log.i(TAG, "License Type: $licenseType")
										Log.i(TAG, "SKU: $sku")
										break
									}
								}
							}
						}
						if (streamingEntitlementId.isNotEmpty()) break
					}
				}
			}
			
			// Fallback: for PSNow container products the links[] items are shallow (no default_sku).
			// Make a follow-up request for each link that could be the base game.
			if (streamingEntitlementId.isEmpty())
			{
				val links = json.optJSONArray("links")
				if (links != null)
				{
					val triedLinkIds = mutableListOf<String>()

					subLoop@ for (i in 0 until minOf(links.length(), 6))
					{
						val link = links.optJSONObject(i) ?: continue
						val linkId = link.optString("id", "")
						if (linkId.isEmpty() || linkId == productId) continue

						// Skip obvious non-game content
						val gcType = link.optString("game_content_type", "").lowercase()
						val linkName = link.optString("name", "").lowercase()
						if (gcType == "addons" || gcType == "addon" || gcType == "themes" || gcType == "theme") continue
						if (linkName.contains("add-on") || linkName.contains("theme") || linkName.contains(" dlc")) continue

						triedLinkIds.add(linkId)
						Log.i(TAG, "Step 0.5d: Container fallback — querying sub-product: $linkId")

						val subUrl = "$storeBase/container/$country/$language/19/$linkId?useOffers=true&gkb=1&gkb2=1"
						val subResp = HttpClient.get(subUrl, headers)
						if (subResp.statusCode != 200) continue

						val subJson = JSONObject(subResp.body)
						val subCandidates = mutableListOf<JSONObject>()
						subCandidates.add(subJson)
						val subLinks = subJson.optJSONArray("links")
						if (subLinks != null)
						{
							for (j in 0 until subLinks.length())
								subLinks.optJSONObject(j)?.let { subCandidates.add(it) }
						}

						for (c in subCandidates)
						{
							if (streamingEntitlementId.isNotEmpty()) break@subLoop

							if (c.has("default_sku"))
							{
								val dsku = c.optJSONObject("default_sku")
								if (dsku != null && dsku.has("entitlements"))
								{
									val ents = dsku.getJSONArray("entitlements")
									for (j in 0 until ents.length())
									{
										val ent = ents.optJSONObject(j) ?: continue
										if (ent.optInt("license_type", -1) == 4)
										{
											val entId = ent.optString("id", "")
											if (entId.isNotEmpty())
											{
												streamingEntitlementId = entId
												sku = dsku.optString("id", "")
												Log.i(TAG, "Found entitlement via sub-product '$linkId': $streamingEntitlementId")
												break@subLoop
											}
										}
									}
								}
							}

							if (streamingEntitlementId.isEmpty() && c.has("skus"))
							{
								val skusArr = c.optJSONArray("skus")
								if (skusArr != null)
								{
									skuLoop@ for (j in 0 until skusArr.length())
									{
										val skuObj = skusArr.optJSONObject(j) ?: continue
										val ents = skuObj.optJSONArray("entitlements") ?: continue
										for (k in 0 until ents.length())
										{
											val ent = ents.optJSONObject(k) ?: continue
											if (ent.optInt("license_type", -1) == 4)
											{
												val entId = ent.optString("id", "")
												if (entId.isNotEmpty())
												{
													streamingEntitlementId = entId
													sku = skuObj.optString("id", "")
													Log.i(TAG, "Found entitlement via sub-product skus '$linkId': $streamingEntitlementId")
													break@subLoop
												}
											}
										}
									}
								}
							}
						}
					}

					if (streamingEntitlementId.isEmpty() && triedLinkIds.isNotEmpty())
						Log.i(TAG, "Step 0.5d: No entitlement found in sub-products: $triedLinkIds")
				}
			}

			// Fallback: query using the timestamp suffix from the store response.
			// The PSNow catalog's canonical URL for a product includes the item's timestamp as a
			// path segment: .../container/{country}/{lang}/19/{productId}/{timestamp_seconds}
			// Some products (e.g. Bloodborne EU base game vs GOTY container) only return a proper
			// product-detail page with a license_type==4 streaming entitlement when this suffix is present.
			if (streamingEntitlementId.isEmpty())
			{
				val timestamp = json.optLong("timestamp", 0L)
				if (timestamp > 0L)
				{
					val timestampSecs = timestamp / 1000L
					val tsUrl = "$storeBase/container/$country/$language/19/$productId/$timestampSecs"
					Log.i(TAG, "Step 0.5d: Retrying with timestamp-suffixed URL: $tsUrl")
					val tsResp = HttpClient.get(tsUrl, headers)
					if (tsResp.statusCode == 200)
					{
						Log.i(TAG, "Step 0.5d: Timestamp URL response (first 1000): ${tsResp.body.take(1000)}")
						val tsJson = JSONObject(tsResp.body)
						val tsResult = findEntitlementRecursive(tsJson, 0)
						if (tsResult != null)
						{
							streamingEntitlementId = tsResult.first
							sku = tsResult.second
							Log.i(TAG, "Step 0.5d: Found streaming entitlement via timestamp URL: $streamingEntitlementId")
						}
					}
				}
			}

			// Fallback: retry the store URL without gkb/useOffers params — for products that
			// return a guided-browse container view with those params but product-detail without them.
			if (streamingEntitlementId.isEmpty())
			{
				val retryUrl = "$storeBase/container/$country/$language/19/$productId?start=0&size=30"
				Log.i(TAG, "Step 0.5d: Retrying without gkb params: $retryUrl")
				val retryResp = HttpClient.get(retryUrl, headers)
				if (retryResp.statusCode == 200)
				{
					val retryJson = JSONObject(retryResp.body)
					Log.i(TAG, "Step 0.5d retry body (first 500): ${retryResp.body.take(500)}")
					val retryCandidates = mutableListOf<JSONObject>(retryJson)
					retryJson.optJSONArray("links")?.let { rl ->
						for (i in 0 until rl.length()) rl.optJSONObject(i)?.let { retryCandidates.add(it) }
					}
					for (c in retryCandidates)
					{
						if (streamingEntitlementId.isNotEmpty()) break
						if (c.has("default_sku"))
						{
							val dsku = c.optJSONObject("default_sku")
							if (dsku != null && dsku.has("entitlements"))
							{
								val ents = dsku.getJSONArray("entitlements")
								for (j in 0 until ents.length())
								{
									val ent = ents.optJSONObject(j) ?: continue
									if (ent.optInt("license_type", -1) == 4)
									{
										val entId = ent.optString("id", "")
										if (entId.isNotEmpty()) { streamingEntitlementId = entId; sku = dsku.optString("id", "") }
									}
								}
							}
						}
						if (streamingEntitlementId.isEmpty() && c.has("skus"))
						{
							val skusArr = c.optJSONArray("skus")
							if (skusArr != null)
							{
								for (j in 0 until skusArr.length())
								{
									val skuObj = skusArr.optJSONObject(j) ?: continue
									val ents = skuObj.optJSONArray("entitlements") ?: continue
									for (k in 0 until ents.length())
									{
										val ent = ents.optJSONObject(k) ?: continue
										if (ent.optInt("license_type", -1) == 4)
										{
											val entId = ent.optString("id", "")
											if (entId.isNotEmpty()) { streamingEntitlementId = entId; sku = skuObj.optString("id", "") }
										}
									}
									if (streamingEntitlementId.isNotEmpty()) break
								}
							}
						}
					}
				}
			}

			// Fallback: the PSNow store container page includes a top_category facet.  When
			// "Game Content" appears there it means the base streamable game is a child of this
			// container.  Re-query the same URL with ?top_category=game_content to retrieve that
			// child product page, which should carry the license_type==4 entitlement and the
			// correct checkout SKU.
			if (streamingEntitlementId.isEmpty())
			{
				val gameContentUrl = "$storeBase/container/$country/$language/19/$productId?top_category=game_content&start=0&size=10"
				Log.i(TAG, "Step 0.5d: Querying container children with top_category=game_content: $gameContentUrl")
				val gcResp = HttpClient.get(gameContentUrl, headers)
				if (gcResp.statusCode == 200)
				{
					val gcJson = JSONObject(gcResp.body)
					// Log top-level keys and link count to diagnose response structure
					val gcTopKeys = gcJson.keys().asSequence().toList()
					val gcLinks = gcJson.optJSONArray("links")
					Log.i(TAG, "Step 0.5d: game_content top-level keys: $gcTopKeys")
					Log.i(TAG, "Step 0.5d: game_content links count: ${gcLinks?.length() ?: "null"}")
					Log.i(TAG, "Step 0.5d: game_content response (first 2000): ${gcResp.body.take(2000)}")
					if (gcLinks != null)
					{
						for (i in 0 until gcLinks.length())
						{
							if (streamingEntitlementId.isNotEmpty()) break
							val item = gcLinks.optJSONObject(i) ?: continue
							val dsku = item.optJSONObject("default_sku")
							if (dsku != null)
							{
								val ents = dsku.optJSONArray("entitlements")
								if (ents != null)
								{
									for (j in 0 until ents.length())
									{
										val ent = ents.optJSONObject(j) ?: continue
										if (ent.optInt("license_type", -1) == 4)
										{
											val entId = ent.optString("id", "")
											if (entId.isNotEmpty())
											{
												streamingEntitlementId = entId
												sku = dsku.optString("id", "")
												Log.i(TAG, "Step 0.5d: Found entitlement via game_content child: $entId (sku=$sku)")
											}
										}
									}
								}
							}
							// Also check skus[]
							if (streamingEntitlementId.isEmpty() && item.has("skus"))
							{
								val skusArr = item.optJSONArray("skus")
								if (skusArr != null)
								{
									for (j in 0 until skusArr.length())
									{
										val skuObj = skusArr.optJSONObject(j) ?: continue
										val ents = skuObj.optJSONArray("entitlements") ?: continue
										for (k in 0 until ents.length())
										{
											val ent = ents.optJSONObject(k) ?: continue
											if (ent.optInt("license_type", -1) == 4)
											{
												val entId = ent.optString("id", "")
												if (entId.isNotEmpty())
												{
													streamingEntitlementId = entId
													sku = skuObj.optString("id", "")
													Log.i(TAG, "Step 0.5d: Found entitlement via game_content child skus: $entId (sku=$sku)")
												}
											}
										}
										if (streamingEntitlementId.isNotEmpty()) break
									}
								}
							}
						}
					}
				}
			}

			// Last-resort: deep recursive search for license_type==4 anywhere in the initial response.
			// Catches entitlements at non-standard JSON paths (e.g. inside nested subscription objects).
			if (streamingEntitlementId.isEmpty())
			{
				val result = findEntitlementRecursive(json, 0)
				if (result != null)
				{
					streamingEntitlementId = result.first
					sku = result.second
					Log.i(TAG, "Found entitlement via recursive search: $streamingEntitlementId")
				}
			}

			// Try to extract platform from playable_platform — check all candidates
			outer@ for (candidate in candidates)
			{
				if (candidate.has("playable_platform"))
				{
					val playablePlatform = candidate.getJSONArray("playable_platform")
					var hasPS4 = false
					var hasPS3 = false
					for (i in 0 until playablePlatform.length())
					{
						val platformStr = playablePlatform.getString(i)
						if (platformStr.contains("PS4", ignoreCase = true)) hasPS4 = true
						else if (platformStr.contains("PS3", ignoreCase = true)) hasPS3 = true
					}
					detectedPlatform = when
					{
						hasPS4 -> "ps4"
						hasPS3 -> "ps3"
						else -> "ps4"
					}
					Log.i(TAG, "Detected platform from playable_platform: $detectedPlatform")
					break@outer
				}
				else if (candidate.has("metadata"))
				{
					val metadata = candidate.getJSONObject("metadata")
					if (metadata.has("playable_platform"))
					{
						val playablePlatformObj = metadata.getJSONObject("playable_platform")
						if (playablePlatformObj.has("values"))
						{
							val values = playablePlatformObj.getJSONArray("values")
							var hasPS4 = false
							var hasPS3 = false
							for (i in 0 until values.length())
							{
								val platformStr = values.getString(i)
								if (platformStr.contains("PS4", ignoreCase = true)) hasPS4 = true
								else if (platformStr.contains("PS3", ignoreCase = true)) hasPS3 = true
							}
							detectedPlatform = when
							{
								hasPS4 -> "ps4"
								hasPS3 -> "ps3"
								else -> "ps4"
							}
							break@outer
						}
					}
				}
			}
			
			// Final fallback: PSNow streaming entitlements keep the publisher prefix but replace
			// the game-specific content suffix with the fixed string PSRSVD0000000000.
			// Confirmed by God of War III Remastered (EP9000-CUSA01715_00-0000GODOFWAR3PS4
			// → EP9000-CUSA01715_00-PSRSVD0000000000, license_type==4 from store skus array).
			// For games whose store page returns a container/facets result (e.g. Bloodborne EU)
			// the entitlement cannot be read from the store, so we derive it here.
			if (streamingEntitlementId.isEmpty())
			{
				val psrsvdDerived = Regex("^(.+_\\d{2})-[^-]+$").find(productId)?.let { "${it.groupValues[1]}-PSRSVD0000000000" }
				if (psrsvdDerived != null)
				{
					// When the store page returns a container (no playable_platform), detectedPlatform
					// stays at the "ps4" default. Refine it from the 4-letter title ID prefix in the
					// product ID: PS3 discs use BLES/BLUS/BCES/BCUS/BLAS, PSN PS3 uses NPEA/NPUB etc.
					// Only CUSA = PS4 and PPSA = PS5; everything else is PS3.
					if (detectedPlatform == "ps4") {
						val titlePrefix = Regex("-([A-Z]{4})\\d").find(productId)?.groupValues?.get(1)
						if (titlePrefix != null && titlePrefix != "CUSA" && titlePrefix != "PPSA") {
							detectedPlatform = "ps3"
							Log.i(TAG, "Step 0.5d: Refined platform to 'ps3' from title prefix '$titlePrefix' (store gave no playable_platform)")
						}
					}
					Log.i(TAG, "Step 0.5d: Store URL returned container page — trying PSRSVD entitlement: $psrsvdDerived")
					isEntitlementDerived = true
					return Triple(psrsvdDerived, detectedPlatform, productId)
				}

				Log.e(TAG, "Could not determine Entitlement ID for '$productId'")
				productLookupDiag = "No entitlement found for '$productId' (store returned container page, no pattern match)"
				return null
			}
			
			Log.i(TAG, "Converted Product ID: $productId -> Entitlement: $streamingEntitlementId, Platform: $detectedPlatform")
			
			return Triple(streamingEntitlementId, detectedPlatform, sku)
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Step 0.5d error", e)
			return null
		}
	}
	
	// ============================================================================
	// Step 0.5e: Check and Acquire Entitlement (entitlement_check.py flow)
	// ============================================================================
	
	private var commerceOAuthToken: String? = null
	
	/**
	 * Step 0.5e: Check and acquire entitlement if needed
	 * Mirrors: PSKamajiSession::step0_5e_CheckEntitlement()
	 */
	private fun step0_5e_CheckAndAcquireEntitlement(npssoToken: String, sessionId: String): Boolean
	{
		try
		{
			Log.i(TAG, "Kamaji Step 0.5e: Starting entitlement check/acquisition flow")
			Log.i(TAG, "  Entitlement ID: $entitlementId")
			if (!streamingSku.isNullOrEmpty())
			{
				Log.i(TAG, "  SKU: $streamingSku")
			}
			
			// Step 0.5e.1: Get Commerce OAuth token
			val commerceToken = step0_5e1_GetCommerceOAuthToken(npssoToken)
				?: return false
			commerceOAuthToken = commerceToken
			Log.i(TAG, "✓ Step 0.5e.1 complete - Got Commerce OAuth token")
			
			// Step 0.5e.1b: When entitlement was derived (container product), search the commerce
			// API for the correct entitlement ID by NP Title ID before resorting to guessing.
			if (isEntitlementDerived)
			{
				val searched = step0_5e1b_SearchEntitlementByTitleId()
				if (searched != null)
				{
					entitlementId = searched
					isEntitlementDerived = false
					Log.i(TAG, "✓ Step 0.5e.1b: Found correct entitlement via commerce search: $searched")
				}
				else
				{
					Log.i(TAG, "Step 0.5e.1b: Commerce search unhelpful, continuing with derived ID")
				}
			}

			// Step 0.5e.2: Check if entitlement exists
			val hasEntitlement = step0_5e2_CheckEntitlementExists()
			if (hasEntitlement == null)
			{
				return false // Error occurred
			}
			else if (hasEntitlement)
			{
				// User has entitlement, continue
				Log.i(TAG, "✓ Step 0.5e.2 complete - User has entitlement")
				return true
			}
			
		// User doesn't have entitlement (404), try to acquire it
		Log.i(TAG, "Kamaji Step 0.5e.2 - Entitlement not found (404), will attempt to acquire")

		// When the entitlement ID was derived via IV0000 prefix substitution (because the PSNow
		// store returned a container/facets page instead of a product detail), the derived suffix
		// may be wrong (e.g. "EU" regional suffix vs the numeric suffix the Gaikai server uses).
		// Probe numeric variants using the commerce API before attempting streaming.
		if (isEntitlementDerived)
		{
			val currentDerived = entitlementId ?: ""
			val contentSuffix = currentDerived.substringAfterLast("-")  // e.g. BLOODBORNE0000EU
			val trailingAlpha = Regex("[A-Z]+$").find(contentSuffix)?.value ?: ""

			if (trailingAlpha.isNotEmpty())
			{
				val basePart = currentDerived.dropLast(trailingAlpha.length)  // prefix + content without trailing letters
				// Generate numeric variants: replace trailing letters with same-length numerics (01, 00)
				// and a one-digit-longer variant (001)
				val alphaLen = trailingAlpha.length
				val variants = listOf(
					basePart + "0".repeat(alphaLen - 1) + "1",          // e.g. BLOODBORNE000001
					basePart + "0".repeat(alphaLen),                     // e.g. BLOODBORNE000000
					basePart + "0".repeat(alphaLen) + "1"                // e.g. BLOODBORNE0000001
				).distinct().filter { it != currentDerived }

				for (variant in variants)
				{
					entitlementId = variant
					Log.i(TAG, "Step 0.5e: Probing numeric variant: $variant")
					val probe = step0_5e2_CheckEntitlementExists()
					if (probe == true)
					{
						Log.i(TAG, "Step 0.5e: Found correct entitlement via numeric variant: $variant")
						return true
					}
				}

				// No numeric variant was confirmed by the commerce API. A 404 here typically means
				// the game is included via PS Now subscription rather than as an individual purchase,
				// so the check is not reliable for determining the correct ID format.
				// Revert to the original IV0000 derivation (regional suffix intact) rather than
				// guessing a numeric suffix that Gaikai may not recognise.
				entitlementId = currentDerived
				Log.i(TAG, "Step 0.5e: No variant confirmed by commerce API, reverting to original derived ID: $currentDerived")
			}
			else
			{
				Log.i(TAG, "Step 0.5e: Derived suffix is already numeric — proceeding with: $currentDerived")
			}

			Log.i(TAG, "Step 0.5e: Skipping acquisition for derived entitlement, attempting streaming directly")
			return true
		}

		// Pre-known entitlements are catalog-extracted license_type==4 SKUs — they are
		// confirmed streaming entitlements. A 404 from commerce means the game is included
		// via PS Plus Premium subscription (no individual purchase record), not that the
		// entitlement is wrong. Skip checkout and proceed directly to streaming.
		if (preKnownEntitlementId.isNotEmpty())
		{
			Log.i(TAG, "Step 0.5e: Pre-known entitlement not in commerce (subscription access), skipping acquisition")
			return true
		}

		// Step 0.5e.3: Checkout preview
		// Throws PsPlusSubscriptionException if user doesn't have required subscription
		val previewOk = step0_5e3_CheckoutPreview(sessionId)
		if (!previewOk)
		{
			return false
		}
		Log.i(TAG, "✓ Step 0.5e.3 complete - Game is free, proceeding to checkout")
			
			// Step 0.5e.4: Complete checkout
			val checkoutOk = step0_5e4_CheckoutBuynow(sessionId)
			if (!checkoutOk)
			{
				return false
			}
		Log.i(TAG, "✓ Step 0.5e.4 complete - Entitlement successfully acquired!")
		
		return true
	}
	catch (e: PsPlusSubscriptionException)
	{
		// Re-throw subscription exceptions so they bubble up to UI
		Log.e(TAG, "Step 0.5e subscription error", e)
		throw e
	}
	catch (e: Exception)
	{
		Log.e(TAG, "Step 0.5e error", e)
		return false
	}
}
	
	/**
	 * Step 0.5e.1: Get Commerce OAuth token
	 * Mirrors: PSKamajiSession::step0_5e_GetCommerceOAuthToken()
	 */
	private fun step0_5e1_GetCommerceOAuthToken(npssoToken: String): String?
	{
		try
		{
			Log.i(TAG, "Kamaji Step 0.5e.1: Getting OAuth token for Commerce API...")
			
			// Build URL - Uses Commerce API client ID and scopes (Qt lines 551-572)
			val params = listOf(
				"smcid" to "pc:psnow",
				"applicationId" to "psnow",
				"response_type" to "token", // Returns access_token in URL fragment, not code
				"scope" to "kamaji:get_internal_entitlements user:account.attributes.validate kamaji:get_privacy_settings user:account.settings.privacy.get kamaji:s2s.subscriptionsPremium.get",
				"client_id" to "dc523cc2-b51b-4190-bff0-3397c06871b3", // Commerce API client ID
				"redirect_uri" to redirectUri,
				"grant_type" to "authorization_code",
				"service_entity" to "urn:service-entity:psn",
				"prompt" to "none",
				"renderMode" to "mobilePortrait",
				"hidePageElements" to "forgotPasswordLink",
				"displayFooter" to "none",
				"disableLinks" to "qriocityLink",
				"mid" to "PSNOW",
				"duid" to duid,
				"layout_type" to "popup",
				"service_logo" to "ps",
				"tp_psn" to "true",
				"noEVBlock" to "true"
			)
			
			val queryString = params.joinToString("&") { (k, v) ->
				"$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
			}
			// accountBaseUrl already has "/api", just add "/v1/oauth/authorize"
			val url = "${accountBaseUrl}/v1/oauth/authorize?$queryString"
			
			Log.d(TAG, "Step 0.5e.1: GET /oauth/authorize (commerce)")
			Log.d(TAG, "URL: $url")
			
			val response = HttpClient.get(
				url,
				headers = mapOf(
					"User-Agent" to userAgent,
					"Cookie" to "npsso=$npssoToken" // Only NPSSO, NOT JSESSIONID
				),
				followRedirects = false
			)
			
			Log.d(TAG, "Step 0.5e.1 Response: ${response.statusCode}")
			
			if (response.statusCode != 302)
			{
				Log.e(TAG, "Step 0.5e.1 failed: expected 302, got ${response.statusCode}")
				return null
			}
			
			// Extract access_token from redirect URL fragment (#access_token=...)
			val location = response.headers["Location"]?.firstOrNull()
				?: response.headers["location"]?.firstOrNull()
			
			if (location == null)
			{
				Log.e(TAG, "Step 0.5e.1: No Location header in redirect")
				return null
			}
			
			Log.d(TAG, "Redirect location: $location")
			
			// Extract access_token from URL fragment (Qt lines 625-633)
			// Try fragment first (#access_token=...)
			var tokenMatch = Regex("#access_token=([^&]+)").find(location)
			if (tokenMatch == null)
			{
				// Fallback to query string
				tokenMatch = Regex("[?&#]access_token=([^&]+)").find(location)
			}
			
			if (tokenMatch == null)
			{
				Log.e(TAG, "Could not extract access_token from redirect URL")
				Log.e(TAG, "Redirect URL: $location")
				return null
			}
			
			val accessToken = tokenMatch.groupValues[1]
			Log.i(TAG, "✓ Step 0.5e.1 complete - Got Commerce OAuth token: ${accessToken.take(30)}...")
			
			return accessToken
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Step 0.5e.1 error", e)
			return null
		}
	}
	
	/**
	 * Step 0.5e.1b: Page through the user's commerce entitlements to find the one matching
	 * the NP Title ID of the current game, then convert its prefix to the IV0000- format
	 * that the Gaikai streaming server expects.
	 *
	 * Background: the PSNow store returns a container/facets page for some products (e.g.
	 * Bloodborne EU) with no license_type==4 entitlement, so step0_5d cannot find the correct
	 * streaming ID. The commerce API lists subscription entitlements with an EP0001- prefix;
	 * those share the same title ID and content suffix as the IV0000- Gaikai entitlements, so
	 * substituting the prefix gives us the correct ID to send to the streaming server.
	 *
	 * The np_title_ids query parameter is silently ignored by the API, so we fetch all pages
	 * and filter locally.
	 */
	private fun step0_5e1b_SearchEntitlementByTitleId(): String?
	{
		try
		{
			// Extract the NP Title ID from the product ID (e.g. CUSA00207 from EP9000-CUSA00207_00-...)
			val titleId = Regex("[A-Z]{2}\\d{4}-([A-Z]{4}\\d+)_").find(productId)?.groupValues?.get(1)
				?: run {
					Log.w(TAG, "Step 0.5e.1b: Cannot extract title ID from productId: $productId")
					return null
				}

			Log.i(TAG, "Step 0.5e.1b: Paging through commerce entitlements to find titleId=$titleId")

			val headers = mapOf(
				"Authorization" to "Bearer $commerceOAuthToken",
				"User-Agent" to userAgent,
				"Accept" to "application/json"
			)

			var start = 0
			val pageSize = 100
			val maxItems = 500   // safety cap — 5 pages of 100

			while (start < maxItems)
			{
				val url = "$commerceBase/users/me/internal_entitlements?fields=game_meta&start=$start&size=$pageSize"
				val response = HttpClient.get(url = url, headers = headers)

				if (response.statusCode != 200)
				{
					commerceSearchDiag = "HTTP ${response.statusCode} at start=$start"
					Log.w(TAG, "Step 0.5e.1b: $commerceSearchDiag")
					return null
				}

				val json = JSONObject(response.body)
				val arr = json.optJSONArray("entitlements") ?: break

				Log.i(TAG, "Step 0.5e.1b: Page start=$start — ${arr.length()} entitlements")

				for (i in 0 until arr.length())
				{
					val obj = arr.optJSONObject(i) ?: continue
					val eid = obj.optString("id").takeIf { it.isNotEmpty() }
						?: obj.optString("entitlement_id").takeIf { it.isNotEmpty() }
						?: continue

					if (eid.contains(titleId, ignoreCase = true))
					{
						// The commerce API uses EP0001- prefix; Gaikai uses IV0000-.
						// Both share the same title ID and content suffix, so substitute the prefix.
						val gaikaiId = eid.replace(Regex("^[A-Z]{2}\\d{4}-"), "IV0000-")
						Log.i(TAG, "Step 0.5e.1b: Found commerce eid=$eid → Gaikai eid=$gaikaiId")
						commerceSearchDiag = "found $gaikaiId"
						return gaikaiId
					}
				}

				if (arr.length() < pageSize) break   // last page reached
				start += pageSize
			}

			commerceSearchDiag = "not found after $start items"
			Log.i(TAG, "Step 0.5e.1b: No match for $titleId after scanning $start entitlements")
			return null
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Step 0.5e.1b error", e)
			return null
		}
	}

	/**
	 * Step 0.5e.2: Check if entitlement exists
	 * Mirrors: PSKamajiSession::step0_5e_CheckEntitlementExists()
	 * Returns: true if exists, false if doesn't exist (404), null on error
	 */
	private fun step0_5e2_CheckEntitlementExists(): Boolean?
	{
		try
		{
		Log.i(TAG, "Kamaji Step 0.5e.2: Checking if entitlement exists...")
		
		val url = "$commerceBase/users/me/internal_entitlements/$entitlementId?fields=game_meta"
			
			val response = HttpClient.get(
				url,
				headers = mapOf(
					"Authorization" to "Bearer $commerceOAuthToken",
					"User-Agent" to userAgent,
					"Accept" to "application/json"
				)
			)
			
			Log.d(TAG, "Step 0.5e.2 Response: ${response.statusCode}")
			
			if (response.statusCode == 200)
			{
				// User has entitlement
				try
				{
					val json = JSONObject(response.body)
					val gameMeta = json.optJSONObject("game_meta")
					val gameName = gameMeta?.optString("name")
					if (gameName != null)
					{
						Log.i(TAG, "  Game Name: $gameName")
					}
				}
				catch (e: Exception)
				{
					Log.w(TAG, "Could not parse game meta", e)
				}
				
				return true
			}
			else if (response.statusCode == 404)
			{
				// User doesn't have entitlement
				return false
			}
			else
			{
				Log.e(TAG, "Step 0.5e.2 failed: ${response.statusCode}")
				Log.e(TAG, "Response body: ${response.body}")
				return null
			}
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Step 0.5e.2 error", e)
			return null
		}
	}
	
	/**
	 * Step 0.5e.3: Checkout preview (verify game is free/available)
	 * Mirrors: PSKamajiSession::step0_5e_CheckoutPreview()
	 */
	private fun step0_5e3_CheckoutPreview(sessionId: String): Boolean
	{
		try
		{
		Log.i(TAG, "Kamaji Step 0.5e.3: Checking checkout preview...")
		
		if (streamingSku.isNullOrEmpty())
		{
			Log.w(TAG, "No SKU available for checkout preview, using entitlement ID")
			streamingSku = entitlementId
		}
		
		val url = "$kamajiBase/user/checkout/buynow/preview"
			
			// Build form data
			val formData = "sku=$streamingSku"
			
			val response = HttpClient.post(
				url,
				body = formData,
				headers = mapOf(
					"Content-Type" to "application/x-www-form-urlencoded",
					"User-Agent" to userAgent,
					"Accept" to "application/json",
					"Authorization" to "Bearer $commerceOAuthToken",
					"Sec-Fetch-Site" to "same-origin",
					"Sec-Fetch-Mode" to "cors",
					"Sec-Fetch-Dest" to "empty",
					"Referer" to "https://psnow.playstation.com/app/2.2.0/133/5cdcc037d/",
					"Accept-Encoding" to "identity",
					"Accept-Language" to preferences.getCloudLanguage(),
					"Cookie" to "JSESSIONID=$sessionId"
				)
			)
			
		Log.d(TAG, "Step 0.5e.3 Response: ${response.statusCode}")
		
		// Parse response to check for API errors first
		try
		{
			val json = JSONObject(response.body)
			val header = json.getJSONObject("header")
			val statusCode = header.optString("status_code")
			
			// Check API status code - non-zero indicates subscription/entitlement issue
			// Matches Qt: pskamajisession.cpp lines 934-944
			if (statusCode != "0x0000")
			{
				val message = header.optString("message_key", "Unknown error")
				Log.e(TAG, "Preview failed with API status: $statusCode")
				Log.e(TAG, "Message: $message")
				// Checkout preview errors indicate PS Plus Premium subscription required
				throw PsPlusSubscriptionException("PlayStation Plus Premium subscription is required to stream this game")
			}
		}
		catch (e: PsPlusSubscriptionException)
		{
			// Re-throw subscription exceptions
			throw e
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Failed to parse preview response", e)
			// If we can't parse, fall through to HTTP status check
		}
		
		// Check HTTP status code
		// Matches Qt: pskamajisession.cpp lines 948-953
		if (response.statusCode != 200)
		{
			Log.e(TAG, "Step 0.5e.3 failed with HTTP status: ${response.statusCode}")
			Log.e(TAG, "Response body: ${response.body}")
			// Checkout preview HTTP errors indicate PS Plus Premium subscription issue
			throw PsPlusSubscriptionException("PlayStation Plus Premium subscription is required to stream this game")
		}
		
		// Parse successful response
		try
		{
			val json = JSONObject(response.body)
			val header = json.getJSONObject("header")
			val statusCode = header.optString("status_code")
				
			val data = json.getJSONObject("data")
			// Qt lines 988-991: Parse cart.total_price_value (integer)
			val cart = data.getJSONObject("cart")
			val totalPriceValue = cart.optInt("total_price_value")
			val totalPrice = cart.optString("total_price")
			
			Log.i(TAG, "  Total Price Value: $totalPriceValue")
			Log.i(TAG, "  Total Price: $totalPrice")
			
			if (totalPriceValue != 0)
			{
				Log.e(TAG, "Game is not free! Price: $totalPrice")
				return false
			}
				
			// Extract actual SKU from response (Qt lines 1002-1009: cart.items[0].sku_id)
			val items = cart.optJSONArray("items")
			if (items != null && items.length() > 0)
			{
				val firstItem = items.getJSONObject(0)
				val actualSku = firstItem.optString("sku_id")
				if (!actualSku.isNullOrEmpty() && actualSku != streamingSku)
				{
					Log.i(TAG, "Using SKU from preview response: $actualSku")
					streamingSku = actualSku
				}
			}
				
				return true
			}
			catch (e: Exception)
			{
				Log.e(TAG, "Failed to parse preview response", e)
			return false
		}
	}
	catch (e: PsPlusSubscriptionException)
	{
		// Re-throw subscription exceptions so they bubble up to UI
		Log.e(TAG, "Step 0.5e.3 subscription error", e)
		throw e
	}
	catch (e: Exception)
	{
		Log.e(TAG, "Step 0.5e.3 error", e)
		return false
	}
}
	
	/**
	 * Step 0.5e.4: Complete checkout to acquire entitlement
	 * Mirrors: PSKamajiSession::step0_5e_CheckoutBuynow()
	 */
	private fun step0_5e4_CheckoutBuynow(sessionId: String): Boolean
	{
		try
		{
		Log.i(TAG, "Kamaji Step 0.5e.4: Completing checkout to acquire entitlement...")
		
		val url = "$kamajiBase/user/checkout/buynow"
			
			// Build form data
			val formData = "sku=$streamingSku"
			
			val response = HttpClient.post(
				url,
				body = formData,
				headers = mapOf(
					"Content-Type" to "application/x-www-form-urlencoded",
					"User-Agent" to userAgent,
					"Accept" to "application/json",
					"Authorization" to "Bearer $commerceOAuthToken",
					"Cookie" to "JSESSIONID=$sessionId"
				)
			)
			
			Log.d(TAG, "Step 0.5e.4 Response: ${response.statusCode}")
			
			if (response.statusCode != 200)
			{
				Log.e(TAG, "Step 0.5e.4 failed: ${response.statusCode}")
				Log.e(TAG, "Response body: ${response.body}")
				return false
			}
			
			// Parse response
			try
			{
				val json = JSONObject(response.body)
				val header = json.getJSONObject("header")
				val statusCode = header.optString("status_code")
				
				if (statusCode != "0x0000")
				{
					Log.e(TAG, "Checkout failed with status: $statusCode")
					val messageKey = header.optString("message_key")
					Log.e(TAG, "Message: $messageKey")
					return false
				}
				
				val data = json.getJSONObject("data")
				val transactionId = data.optString("transaction_id")
				
				Log.i(TAG, "  Transaction ID: $transactionId")
				
				return true
			}
			catch (e: Exception)
			{
				Log.e(TAG, "Failed to parse buynow response", e)
				return false
			}
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Step 0.5e.4 error", e)
			return false
		}
	}
	
	/**
	 * Step 5: Get Auth Code
	 * GET /oauth/authorize (for authenticated session code)
	 * Mirrors: PSKamajiSession::step5_GetAuthCode()
	 */
	private fun step5_GetAuthCode(npssoToken: String): String?
	{
		// Same as step0_5b but for authenticated session
		return step0_5b_GetAnonymousAuthCode(npssoToken)
	}
	
	/**
	 * Step 6: Create Auth Session
	 * POST /user/session (authenticated, with OAuth code)
	 * Mirrors: PSKamajiSession::step6_CreateAuthSession()
	 */
	private fun step6_CreateAuthSession(authCode: String): String?
	{
		// Same as step0_5c but using the authenticated auth code
		return step0_5c_CreateAnonymousSession(authCode)
	}

	// Recursively search a JSONObject tree for any entitlement with license_type == 4.
	// Returns Pair(entitlementId, skuId) or null.  Depth-limited to avoid stack overflow.
	private fun findEntitlementRecursive(obj: JSONObject, depth: Int): Pair<String, String>?
	{
		if (depth > 12) return null

		// If this object itself looks like an entitlement with license_type == 4, capture it.
		if (obj.optInt("license_type", -1) == 4)
		{
			val entId = obj.optString("id", "")
			if (entId.isNotEmpty()) return Pair(entId, "")
		}

		val keys = obj.keys()
		while (keys.hasNext())
		{
			val key = keys.next()
			when (val value = obj.opt(key))
			{
				is JSONObject ->
				{
					val found = findEntitlementRecursive(value, depth + 1)
					if (found != null) return found
				}
				is JSONArray ->
				{
					for (i in 0 until value.length())
					{
						val item = value.optJSONObject(i) ?: continue
						val found = findEntitlementRecursive(item, depth + 1)
						if (found != null) return found
					}
				}
			}
		}
		return null
	}
}
