// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

/**
 * Endpoints for PSN friends list and presence. These sit under the same account
 * (`m.np.playstation.com`) OAuth client/scope [com.metallic.chiaki.trophy.PsnTrophyConstants]
 * already uses for Trophies, so this feature reuses [com.metallic.chiaki.trophy.PsnTrophyTokenManager]
 * rather than minting a separate token.
 */
object PsnFriendsConstants
{
	const val PROFILE_BASE = "https://m.np.playstation.com/api/userProfile/v1/internal/users"
	const val PROFILE_BASE_V2 = "https://m.np.playstation.com/api/userProfile/v2/internal/users"
}
