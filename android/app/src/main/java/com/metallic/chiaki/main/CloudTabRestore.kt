// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

/** What CloudPlayFragment should do when its view is recreated while the activity-scoped view model lives on. */
enum class CloudTabRestore
{
	/** Not signed in — onResume shows the login state, nothing to restore. */
	NONE,
	/** Nothing usable is loaded and nothing is loading (e.g. after process death) — load like a fresh start. */
	LOAD,
	/** The view model still has the games (or is fetching them) — just re-apply the selected tab's UI. */
	REAPPLY;

	companion object
	{
		fun decide(hasToken: Boolean, hasGames: Boolean, isLoading: Boolean): CloudTabRestore = when
		{
			!hasToken -> NONE
			!hasGames && !isLoading -> LOAD
			else -> REAPPLY
		}
	}
}
