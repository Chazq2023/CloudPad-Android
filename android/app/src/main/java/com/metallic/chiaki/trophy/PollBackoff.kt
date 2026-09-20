// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

/**
 * Delay between polls that stays at [baseMs] while polls succeed and doubles after each consecutive
 * failure, up to [maxMs], so a failing login or trophy lookup isn't retried against Sony every
 * few seconds for a whole session. A success resets it.
 */
class PollBackoff(private val baseMs: Long, private val maxMs: Long)
{
	private var consecutiveFailures = 0

	fun onSuccess() { consecutiveFailures = 0 }
	fun onFailure() { if (consecutiveFailures < 30) consecutiveFailures++ }

	fun nextDelayMs(): Long
	{
		var delay = baseMs
		repeat(consecutiveFailures) { delay = minOf(maxMs, delay * 2) }
		return delay
	}
}
