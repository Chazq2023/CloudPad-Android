// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.api

import android.content.Context
import android.util.Log
import com.pylux.stream.R
import com.metallic.chiaki.cloudplay.DuidUtil
import com.metallic.chiaki.cloudplay.PsnApiConstants
import com.metallic.chiaki.cloudplay.model.CloudStreamSession
import com.metallic.chiaki.common.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CloudStreamingBackend - Orchestrates PlayStation Plus Cloud Gaming flow
 * 
 * This class is the main entry point for cloud gaming. It:
 * - Holds shared configuration (CloudConfig namespace)
 * - Orchestrates Kamaji authentication (PSKamajiSession) 
 * - Orchestrates Gaikai allocation (PSGaikaiStreaming)
 * - Provides a single unified API for the frontend
 * 
 * Architecture:
 *   CloudStreamingBackend (orchestrator)
 *     └─> PSKamajiSession (Steps 1-6: Kamaji auth)
 *     └─> PSGaikaiStreaming (Steps 7-13: Gaikai allocation)
 *     
 * Mirrors: gui/src/cloudstreamingbackend.cpp
 */
class CloudStreamingBackend(
	private val context: Context,
	private val preferences: com.metallic.chiaki.common.Preferences
)
{
	companion object
	{
		private const val TAG = "CloudStreamingBackend"
		// Gaikai hands each session one regional batch of datacenters and doesn't always pick
		// the user's region (e.g. a UK user alternately gets lon/par/fra/mil or iad/lga/atl/ord/dfw),
		// so a distant batch is retried in a fresh session before giving up.
		private const val MAX_DATACENTER_BATCH_ATTEMPTS = 4
		// Attempts that may probe for another region when none has been measured yet
		private const val MAX_REGION_PROBE_ATTEMPTS = 2
	}
	
	/**
	 * Configuration - Shared settings and values used by multiple classes
	 * Mirrors: CloudConfig namespace in cloudstreamingbackend.h
	 */
	object CloudConfig
	{
		const val ACCOUNT_BASE = "https://ca.account.sony.com/api"
	}
	
	/**
	 * MAIN ENTRY POINT - Single method to complete entire flow (Steps 1-13)
	 * 
	 * Parameters:
	 *   serviceType: "psnow" or "pscloud"
	 *   gameIdentifier: Product ID (PSNOW) or Entitlement ID (PSCLOUD)
	 * Platform is automatically detected from API response for PSNOW, or hardcoded to "ps5" for PSCLOUD
	 * 
	 * Mirrors: CloudStreamingBackend::startCompleteCloudSession()
	 */
	suspend fun startCompleteCloudSession(
		serviceType: String,
		gameIdentifier: String,
		gameName: String,
		npssoToken: String,
		ownedEntitlementId: String = "",
		ownedPlatform: String = "",
		onProgress: ((String) -> Unit)? = null,
		isCancelled: () -> Boolean = { false }
	): Result<CloudStreamSession> = withContext(Dispatchers.IO)
	{
		try
		{
			Log.i(TAG, "=== Starting Complete Cloud Streaming Session ===")
			Log.i(TAG, "Service Type: $serviceType")
			Log.i(TAG, "Game Identifier: $gameIdentifier")
			Log.i(TAG, "Game Name: $gameName")
			
			// Normalize service type to lowercase
			val normalizedServiceType = serviceType.lowercase()
			
			// Validate parameters
			if (normalizedServiceType != "psnow" && normalizedServiceType != "pscloud")
			{
				Log.e(TAG, "Invalid serviceType: $normalizedServiceType. Must be 'psnow' or 'pscloud'")
				return@withContext Result.failure(Exception("Invalid serviceType: $normalizedServiceType"))
			}
			
			// Generated once ever (persisted) and reused for every subsequent Cloud Play
			// authorization check and session creation from this install — not regenerated per
			// attempt. A DUID is supposed to be a *device* identifier: sending Sony a brand-new
			// random one on every single session-start call made the same account look like it
			// was authorizing from a different device each time, which is exactly the pattern
			// anti-fraud systems are built to flag and eventually reject outright — a rejection
			// that then surfaces to the user as "your NPSSO token is likely expired, please
			// re-login" even though the token was fine, and that no amount of reinstalling the
			// app can fix, since the account-side flag lives on Sony's servers, not on the
			// device. Mirrors psnDuid's existing generate-once-and-persist pattern for the
			// separate Remote Play auth flow.
			var sharedDuid = preferences.cloudDuid
			if (sharedDuid.isEmpty())
			{
				sharedDuid = DuidUtil.generateDuid()
				preferences.cloudDuid = sharedDuid
				Log.i(TAG, "Generated new Cloud DUID: ${sharedDuid.take(20)}...")
			}
			else
			{
				Log.i(TAG, "Using persisted DUID: ${sharedDuid.take(20)}...")
			}
			
			// Centralized authorization check for both PSNOW and PSCLOUD (Qt lines 91-119)
			val authSuccess = checkAuthorization(normalizedServiceType, npssoToken, sharedDuid)
			if (!authSuccess)
			{
				Log.e(TAG, "Authorization check failed - NPSSO token likely expired")
				return@withContext Result.failure(AuthorizationFailedException("Your NPSSO token is likely expired. Please re-login to continue using cloud streaming."))
			}
			
			Log.i(TAG, "✓ Authorization check passed")
			
			// Continue with cloud session setup
			val result = continueCloudSessionAfterAuth(
				serviceType = normalizedServiceType,
				gameIdentifier = gameIdentifier,
				gameName = gameName,
				npssoToken = npssoToken,
				sharedDuid = sharedDuid,
				ownedEntitlementId = ownedEntitlementId,
				ownedPlatform = ownedPlatform,
				onProgress = onProgress,
				isCancelled = isCancelled
			)
			
			result
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Complete cloud session error", e)
			Result.failure(e)
		}
	}
	
	/**
	 * Continue cloud session after successful authorization
	 * Mirrors: CloudStreamingBackend::continueCloudSessionAfterAuth()
	 */
	/**
	 * Runs the Gaikai allocation, starting a fresh session whenever the datacenter batch Gaikai
	 * offered is unusable — every datacenter over the auto-select ping limit, or the manually
	 * selected datacenter missing from it. Each new session gets a newly chosen batch.
	 */
	private suspend fun allocateWithDatacenterRetry(
		entitlementId: String,
		sharedDuid: String,
		serviceType: String,
		platform: String,
		npssoToken: String,
		onProgress: ((String) -> Unit)?,
		isCancelled: () -> Boolean
	): PSGaikaiStreaming.AllocationResult
	{
		return retryUnusableDatacenterBatches(
			maxAttempts = MAX_DATACENTER_BATCH_ATTEMPTS,
			isCancelled = isCancelled,
			onNotOfferedExhausted = {
				PSGaikaiStreaming.AllocationResult(false, preferences.getString(R.string.gaikai_error_no_datacenters))
			}
		) { attempt, isLastAttempt ->
			PSGaikaiStreaming(
				duid = sharedDuid,
				serviceType = serviceType,
				platform = platform,
				npssoToken = npssoToken,
				preferences = preferences,
				onProgress = onProgress,
				isCancelled = isCancelled,
				acceptDistantBatch = isLastAttempt,
				probeUnseenRegions = attempt <= MAX_REGION_PROBE_ATTEMPTS && !isLastAttempt
			).startAllocationFlow(entitlementId)
		}
	}

	private suspend fun continueCloudSessionAfterAuth(
		serviceType: String,
		gameIdentifier: String,
		gameName: String,
		npssoToken: String,
		sharedDuid: String,
		ownedEntitlementId: String = "",
		ownedPlatform: String = "",
		forceFullEntitlementFlow: Boolean = false,
		onProgress: ((String) -> Unit)? = null,
		isCancelled: () -> Boolean = { false }
	): Result<CloudStreamSession> = withContext(Dispatchers.IO)
	{
		try
		{
			// Determine service-specific configuration
			val redirectUri: String
			val userAgent: String
			val oauthApiPath: String
			
			if (serviceType == "pscloud")
			{
				redirectUri = GaikaiConsts.REDIRECT_URI
				userAgent = GaikaiConsts.USER_AGENT
				oauthApiPath = "/authz/v3"  // ACCOUNT_BASE already includes /api
			}
			else // psnow
			{
				redirectUri = PsnApiConstants.REDIRECT_URI
				userAgent = PsnApiConstants.USER_AGENT
				oauthApiPath = "/v1"  // ACCOUNT_BASE already includes /api
			}
			
			// Determine ChiakiTarget (device/console type used by Chiaki core).
			// PSCLOUD should be treated as PS5.
			// PSNOW target will be determined after platform is detected from API response.
			val initialPlatform = if (serviceType == "pscloud") "ps5" else "ps4"
			
			Log.i(TAG, "Determined initial platform: $initialPlatform")
			
			// For PSNOW: Create Kamaji session handler (Steps 0.5a-0.5d)
			// For PSCLOUD: Skip Kamaji entirely
			var finalEntitlementId = gameIdentifier
			var finalPlatform = initialPlatform
			var kamajiDiag = ""
			var usedFastPath = false

			if (serviceType == "psnow")
			{
				Log.i(TAG, "=== PSNOW Flow: Starting Kamaji Session ===")

			// Create Kamaji session with productId (will be converted to entitlementId)
			// Platform will be automatically detected from the API response
			val kamajiSession = PSKamajiSession(
				duid = sharedDuid,
				productId = gameIdentifier,
				accountBaseUrl = CloudConfig.ACCOUNT_BASE,
				redirectUri = redirectUri,
				userAgent = userAgent,
				preferences = preferences
			)

			if (!forceFullEntitlementFlow && ownedEntitlementId.isNotEmpty())
			{
				Log.i(
					TAG,
					"PSNOW owned fast-path: catalog entitlementId=$ownedEntitlementId platform=$ownedPlatform"
				)
				kamajiSession.setOwnedEntitlementFastPath(
					ownedEntitlementId = ownedEntitlementId,
					ownedPlatform = ownedPlatform
				)
			}
			else if (forceFullEntitlementFlow)
			{
				Log.i(TAG, "PSNOW: forcing full entitlement flow (fast-path retry fallback)")
			}

			// Start Kamaji session creation
			val kamajiResult = kamajiSession.startSessionCreation(npssoToken)
			usedFastPath = kamajiSession.usedEntitlementFastPath

				if (!kamajiResult.success)
				{
					Log.e(TAG, "Kamaji session creation failed: ${kamajiResult.message}")
					return@withContext Result.failure(Exception("Kamaji session failed: ${kamajiResult.message}"))
				}

				finalEntitlementId = kamajiResult.entitlementId
				finalPlatform = kamajiResult.platform
				kamajiDiag = kamajiResult.message

				Log.i(TAG, "✓ Kamaji session complete")
				Log.i(TAG, "  Entitlement ID: $finalEntitlementId")
				Log.i(TAG, "  Platform: $finalPlatform")
			}
			else
			{
				// PSCLOUD: Skip Kamaji, start directly with Gaikai (Qt lines 231-237)
				// PSCLOUD always uses PS5 platform, gameIdentifier is already an entitlementId
				Log.i(TAG, "=== PSCLOUD Flow: Skipping Kamaji, Starting Gaikai Directly ===")
				Log.i(TAG, "PSCLOUD primary id: $finalEntitlementId  fallback ent.id: $ownedEntitlementId")
			}

			// Start Gaikai allocation (Steps 7-13)
			Log.i(TAG, "=== Starting Gaikai Allocation ===")

			val allocationResult = allocateWithDatacenterRetry(
				entitlementId = finalEntitlementId,
				sharedDuid = sharedDuid,
				serviceType = serviceType,
				platform = finalPlatform,
				npssoToken = npssoToken,
				onProgress = onProgress,
				isCancelled = isCancelled
			)

			if (!allocationResult.success)
			{
				Log.e(TAG, "Gaikai allocation failed: ${allocationResult.message}")

				if (
					usedFastPath &&
					!forceFullEntitlementFlow &&
					isEntitlementRejectedError(allocationResult.message)
				)
				{
					Log.w(
						TAG,
						"Owned fast-path entitlement rejected by Gaikai; retrying once with the full entitlement flow"
					)

					return@withContext continueCloudSessionAfterAuth(
						serviceType = serviceType,
						gameIdentifier = gameIdentifier,
						gameName = gameName,
						npssoToken = npssoToken,
						sharedDuid = sharedDuid,
						ownedEntitlementId = "",
						ownedPlatform = "",
						forceFullEntitlementFlow = true,
						onProgress = onProgress,
						isCancelled = isCancelled
					)
				}

				// PSCLOUD: retry with the raw ent.id if the catalog productId is rejected.
				// Gaikai indexes games by the entitlement's id field, which can differ from
				// the imagic catalog productId or the entitlement's product_id field.
				if (serviceType == "pscloud" &&
					isEntitlementRejectedError(allocationResult.message) &&
					ownedEntitlementId.isNotEmpty() &&
					ownedEntitlementId != finalEntitlementId)
				{
					Log.w(TAG, "PSCLOUD: '$finalEntitlementId' rejected by Gaikai; retrying with ent.id '$ownedEntitlementId'")
					return@withContext continueCloudSessionAfterAuth(
						serviceType = serviceType,
						gameIdentifier = ownedEntitlementId,
						gameName = gameName,
						npssoToken = npssoToken,
						sharedDuid = sharedDuid,
						ownedEntitlementId = "",
						ownedPlatform = ownedPlatform,
						forceFullEntitlementFlow = forceFullEntitlementFlow,
						onProgress = onProgress,
						isCancelled = isCancelled
					)
				}

				val detail = if (kamajiDiag.isNotEmpty()) "${allocationResult.message} | Kamaji: $kamajiDiag" else allocationResult.message
				if (isEntitlementRejectedError(allocationResult.message))
					return@withContext Result.failure(GameNotStreamableException(detail))
				return@withContext Result.failure(Exception("Gaikai allocation failed: $detail"))
			}
			
			Log.i(TAG, "✓ Gaikai allocation complete")
			Log.i(TAG, "  Server IP: ${allocationResult.serverIp}")
			Log.i(TAG, "  Session ID: ${allocationResult.sessionId}")
			
			// Create cloud stream session
			val streamSession = CloudStreamSession(
				serverIp = allocationResult.serverIp,
				serverPort = allocationResult.serverPort,
				handshakeKey = allocationResult.handshakeKey,
				launchSpec = allocationResult.launchSpec,
				sessionId = allocationResult.sessionId,
				entitlementId = finalEntitlementId,
				gameName = gameName,
				platform = finalPlatform,
				psnWrapperType = allocationResult.psnWrapperType,
				mtuIn = allocationResult.mtuIn,
				mtuOut = allocationResult.mtuOut,
				rttMs = allocationResult.rttMs,
				serviceType = serviceType,
				datacenter = allocationResult.datacenter
			)
			
			Log.i(TAG, "=== Cloud Streaming Session Ready ===")
			Result.success(streamSession)
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Cloud session continuation error", e)
			Result.failure(e)
		}
	}
	
	private fun isEntitlementRejectedError(error: String): Boolean =
		error.contains("noGameForEntitlement", ignoreCase = true)

	/**
	 * Centralized Authorization Check (used by both PSNOW and PSCLOUD)
	 * Mirrors: CloudStreamingBackend::checkAuthorization() (Qt lines 543-613)
	 */
	private suspend fun checkAuthorization(
		serviceType: String,
		npssoToken: String,
		duid: String
	): Boolean = withContext(Dispatchers.IO)
	{
		if (npssoToken.isEmpty())
		{
			Log.w(TAG, "Authorization check: NPSSO token is empty")
			return@withContext false
		}
		
		// Determine configuration based on service type
		val kamajiClientId: String
		val scopesStr: String
		val redirectUri: String
		val userAgent: String
		
		if (serviceType == "psnow")
		{
			// PSNOW configuration (matching PSKamajiSession)
			kamajiClientId = PsnApiConstants.CLIENT_ID
			scopesStr = PsnApiConstants.PS4_SCOPES
			redirectUri = PsnApiConstants.REDIRECT_URI
			userAgent = PsnApiConstants.USER_AGENT
		}
		else // pscloud
		{
			// PSCLOUD configuration (Qt lines 563-569)
			kamajiClientId = "19ae39c4-3f88-4d11-a792-94e4f52c996d"
			scopesStr = "id_token:psn.basic_claims kamaji:s2s.subscriptionsPremium.get id_token:duid id_token:online_id openid psn:s2s"
			redirectUri = GaikaiConsts.REDIRECT_URI
			userAgent = GaikaiConsts.USER_AGENT
		}
		
		try
		{
			Log.i(TAG, "=== Centralized Authorization Check ===")
			Log.i(TAG, "  Service Type: $serviceType")
			Log.i(TAG, "  Client ID: $kamajiClientId")
			
			// Create authorization check request (matching PSKamajiSession::step0_5a_AuthorizeCheck)
			val url = "${CloudConfig.ACCOUNT_BASE}/authz/v3/oauth/authorizeCheck"
			
			val body = org.json.JSONObject()
			body.put("client_id", kamajiClientId)
			body.put("scope", scopesStr)
			body.put("redirect_uri", redirectUri)
			body.put("response_type", "code")
			body.put("service_entity", "urn:service-entity:psn")
			body.put("duid", duid)
			
			val response = HttpClient.post(
				url = url,
				headers = mapOf(
					"Content-Type" to "application/json; charset=UTF-8",
					"User-Agent" to userAgent,
					"Cookie" to "npsso=$npssoToken"
				),
				body = body.toString()
			)
			
			if (response.statusCode == 200 || response.statusCode == 204)
			{
				Log.i(TAG, "✓ Authorization check passed (${response.statusCode})")
				return@withContext true
			}
			else
			{
				Log.w(TAG, "Authorization check failed: ${response.statusCode}")
				Log.w(TAG, "Response: ${response.body}")
				return@withContext false
			}
		}
		catch (e: Exception)
		{
			Log.e(TAG, "Authorization check error", e)
			return@withContext false
		}
	}
}

/**
 * Runs [allocate] up to [maxAttempts] times, starting over whenever the datacenter batch it was
 * offered is unusable ([PingTimeoutException] / [DatacenterNotOfferedException]) or distant
 * ([DistantDatacenterBatchException]; [allocate] gets the attempt number and whether it's the
 * last, so it can accept a distant batch instead). Once attempts run out (or the launch is cancelled) a ping
 * failure is rethrown and a missing manual datacenter resolves to [onNotOfferedExhausted]. Any
 * other outcome is returned or thrown immediately.
 */
internal suspend fun <T> retryUnusableDatacenterBatches(
	maxAttempts: Int,
	isCancelled: () -> Boolean,
	onNotOfferedExhausted: () -> T,
	allocate: suspend (attempt: Int, isLastAttempt: Boolean) -> T
): T
{
	var attempt = 1
	while (true)
	{
		val retryReason = try
		{
			return allocate(attempt, attempt >= maxAttempts)
		}
		catch (e: PingTimeoutException)
		{
			if (attempt >= maxAttempts || isCancelled()) throw e
			e.message
		}
		catch (e: DatacenterNotOfferedException)
		{
			if (attempt >= maxAttempts || isCancelled()) return onNotOfferedExhausted()
			e.message
		}
		catch (e: DistantDatacenterBatchException)
		{
			if (attempt >= maxAttempts || isCancelled()) throw e
			e.message
		}
		Log.w("CloudStreamingBackend", "Datacenter batch unusable ($retryReason); retrying in a new session (attempt ${attempt + 1}/$maxAttempts)")
		attempt++
	}
}
