// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

/**
 * Endpoints for PSN friends list, presence and 1:1 messaging. These sit under the same account
 * (`m.np.playstation.com`) OAuth client/scope [com.metallic.chiaki.trophy.PsnTrophyConstants]
 * already uses for Trophies, so this feature reuses [com.metallic.chiaki.trophy.PsnTrophyTokenManager]
 * rather than minting a separate token.
 */
object PsnFriendsConstants
{
	const val PROFILE_BASE = "https://m.np.playstation.com/api/userProfile/v1/internal/users"
	const val PROFILE_BASE_V2 = "https://m.np.playstation.com/api/userProfile/v2/internal/users"
	const val GAMING_LOUNGE_BASE = "https://m.np.playstation.com/api/gamingLoungeGroups/v1"

	// Different API family (device-account lookup) — the only call here that isn't under
	// m.np.playstation.com, needed to resolve our own accountId so incoming chat messages can be
	// told apart from the friend's (see FriendsRepository.resolveMyAccountId).
	const val MY_ACCOUNT_URL = "https://dms.api.playstation.com/api/v1/devices/accounts/me"
}
