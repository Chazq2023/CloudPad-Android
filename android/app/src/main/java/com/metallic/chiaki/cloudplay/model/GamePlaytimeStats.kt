// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.model

/**
 * Accumulated playtime for a single game, keyed by [CloudGame.productId] in [com.metallic.chiaki.common.Preferences].
 * Updated once per stream disconnect with the duration of the session that just ended.
 */
data class GamePlaytimeStats(
	val totalPlaytimeMs: Long = 0L,
	val lastPlayedMs: Long = 0L,
	val longestSessionMs: Long = 0L
)
