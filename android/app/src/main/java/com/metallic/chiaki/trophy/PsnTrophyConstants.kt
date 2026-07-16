// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

/**
 * OAuth + API constants for Sony's Trophies API. Separate client/scope from
 * [com.metallic.chiaki.cloudplay.PsnAuthConstants] (that one is scoped to Remote Play/holepunch
 * only and cannot read trophy data) and from [com.metallic.chiaki.cloudplay.PsnApiConstants]
 * (PSNow/PSCloud catalog and commerce entitlements, also a different scope).
 *
 * The client id/secret/scope below are the widely-published values used by the open-source
 * PSN API tooling ecosystem (e.g. the "psn-api" project) for general account-scoped access
 * (profile, trophies) via the same NPSSO-cookie exchange this app already performs elsewhere.
 */
object PsnTrophyConstants
{
	const val ACCOUNT_BASE = "https://ca.account.sony.com"
	const val AUTHORIZE_ENDPOINT = "$ACCOUNT_BASE/api/authz/v3/oauth/authorize"
	const val TOKEN_ENDPOINT = "$ACCOUNT_BASE/api/authz/v3/oauth/token"

	const val CLIENT_ID = "09515159-7237-4370-9b40-3806e67c0891"
	const val CLIENT_SECRET = "ucPjka5tntB2KqsP"
	const val REDIRECT_URI = "com.scee.psxandroid.scecompcall://redirect"
	const val SCOPES = "psn:mobile.v2.core psn:clientapp"

	const val TROPHY_BASE = "https://m.np.playstation.com/api/trophy/v1"
}
