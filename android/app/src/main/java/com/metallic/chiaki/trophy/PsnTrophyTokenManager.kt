// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.util.Log
import com.metallic.chiaki.cloudplay.api.HttpClient
import com.metallic.chiaki.common.Preferences
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Manages a separately-scoped PSN OAuth token for the Trophies API, exchanged from the same
 * NPSSO cookie the app already stores — mirrors [com.metallic.chiaki.common.PsnTokenManager]'s
 * authorize-with-cookie -> code -> token shape, but with the trophy-capable client/scope from
 * [PsnTrophyConstants] instead of the Remote Play one.
 */
class PsnTrophyTokenManager(private val preferences: Preferences)
{
	companion object
	{
		private const val TAG = "PsnTrophyTokenManager"
	}

	/**
	 * Exchange the stored NPSSO cookie for a trophy-scoped access/refresh token pair.
	 * Blocking — run on a background thread/coroutine dispatcher.
	 */
	fun exchangeNpssoForTokens(npsso: String): Boolean
	{
		val authCode = getAuthorizationCode(npsso) ?: run {
			Log.e(TAG, "Failed to obtain authorization code")
			return false
		}

		val tokens = exchangeCodeForTokens(authCode) ?: run {
			Log.e(TAG, "Failed to exchange authorization code for tokens")
			return false
		}

		preferences.psnTrophyAuthToken = tokens.accessToken
		preferences.psnTrophyRefreshToken = tokens.refreshToken
		preferences.psnTrophyAuthTokenExpiry = System.currentTimeMillis() + (tokens.expiresIn * 1000L)
		Log.i(TAG, "Trophy tokens saved (expires in ${tokens.expiresIn}s)")
		return true
	}

	fun refreshToken(): Boolean
	{
		val refreshToken = preferences.psnTrophyRefreshToken
		if (refreshToken.isEmpty())
		{
			Log.w(TAG, "No refresh token available")
			return false
		}

		val body = buildString {
			append("grant_type=refresh_token")
			append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"))
			append("&scope=").append(URLEncoder.encode(PsnTrophyConstants.SCOPES, "UTF-8"))
			append("&client_id=").append(URLEncoder.encode(PsnTrophyConstants.CLIENT_ID, "UTF-8"))
			append("&client_secret=").append(URLEncoder.encode(PsnTrophyConstants.CLIENT_SECRET, "UTF-8"))
		}

		val response = HttpClient.post(
			url = PsnTrophyConstants.TOKEN_ENDPOINT,
			body = body,
			headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
		)

		if (response.statusCode != 200)
		{
			Log.e(TAG, "Token refresh failed: ${response.statusCode}")
			return false
		}

		val json = JSONObject(response.body)
		val accessToken = json.optString("access_token", "")
		val newRefreshToken = json.optString("refresh_token", "")
		val expiresIn = json.optInt("expires_in", 0)

		if (accessToken.isEmpty())
		{
			Log.e(TAG, "No access_token in refresh response")
			return false
		}

		preferences.psnTrophyAuthToken = accessToken
		if (newRefreshToken.isNotEmpty()) preferences.psnTrophyRefreshToken = newRefreshToken
		preferences.psnTrophyAuthTokenExpiry = System.currentTimeMillis() + (expiresIn * 1000L)
		Log.i(TAG, "Trophy token refreshed (expires in ${expiresIn}s)")
		return true
	}

	/** Returns a valid access token, refreshing or re-exchanging the NPSSO as needed. */
	fun getValidToken(): String?
	{
		if (!preferences.hasPsnTrophyTokens)
		{
			val npsso = preferences.getNpssoToken()
			if (npsso.isEmpty()) return null
			if (!exchangeNpssoForTokens(npsso)) return null
			return preferences.psnTrophyAuthToken.ifEmpty { null }
		}

		if (preferences.isPsnTrophyTokenExpired)
		{
			if (!refreshToken())
			{
				val npsso = preferences.getNpssoToken()
				if (npsso.isEmpty() || !exchangeNpssoForTokens(npsso)) return null
			}
		}

		return preferences.psnTrophyAuthToken.ifEmpty { null }
	}

	private fun getAuthorizationCode(npsso: String): String?
	{
		val query = buildString {
			append("client_id=").append(URLEncoder.encode(PsnTrophyConstants.CLIENT_ID, "UTF-8"))
			append("&redirect_uri=").append(URLEncoder.encode(PsnTrophyConstants.REDIRECT_URI, "UTF-8"))
			append("&response_type=code")
			append("&scope=").append(URLEncoder.encode(PsnTrophyConstants.SCOPES, "UTF-8"))
		}

		val response = HttpClient.get(
			url = "${PsnTrophyConstants.AUTHORIZE_ENDPOINT}?$query",
			headers = mapOf("Cookie" to "npsso=$npsso"),
			followRedirects = false
		)

		if (response.statusCode != 302)
		{
			Log.e(TAG, "Authorize request did not redirect: ${response.statusCode}")
			return null
		}

		val location = HttpClient.extractLocation(response.headers) ?: ""
		val match = Regex("[?&]code=([^&]+)").find(location)
		if (match == null)
		{
			Log.e(TAG, "No code in redirect location: $location")
			return null
		}
		return match.groupValues[1]
	}

	private fun exchangeCodeForTokens(code: String): TokenResponse?
	{
		val body = buildString {
			append("grant_type=authorization_code")
			append("&code=").append(URLEncoder.encode(code, "UTF-8"))
			append("&redirect_uri=").append(URLEncoder.encode(PsnTrophyConstants.REDIRECT_URI, "UTF-8"))
			append("&client_id=").append(URLEncoder.encode(PsnTrophyConstants.CLIENT_ID, "UTF-8"))
			append("&client_secret=").append(URLEncoder.encode(PsnTrophyConstants.CLIENT_SECRET, "UTF-8"))
			append("&scope=").append(URLEncoder.encode(PsnTrophyConstants.SCOPES, "UTF-8"))
		}

		val response = HttpClient.post(
			url = PsnTrophyConstants.TOKEN_ENDPOINT,
			body = body,
			headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
		)

		if (response.statusCode != 200)
		{
			Log.e(TAG, "Token exchange failed: ${response.statusCode} - ${response.body}")
			return null
		}

		val json = JSONObject(response.body)
		val accessToken = json.optString("access_token", "")
		if (accessToken.isEmpty())
		{
			Log.e(TAG, "No access_token in token response: ${response.body}")
			return null
		}

		return TokenResponse(
			accessToken = accessToken,
			refreshToken = json.optString("refresh_token", ""),
			expiresIn = json.optInt("expires_in", 0)
		)
	}

	private data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresIn: Int)
}
