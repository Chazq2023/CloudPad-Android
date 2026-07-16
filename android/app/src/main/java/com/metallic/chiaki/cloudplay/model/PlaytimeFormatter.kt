// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.model

/** Pure formatting helpers for the Playtime dialog — split out from CloudPlayFragment so the
 *  duration math (day/hour/minute breakdown, rounding) can be unit tested without Robolectric. */
object PlaytimeFormatter
{
	/** e.g. "2d 4h 15m", or "0h 5m" for durations under a day, or "Not yet played" for 0/negative. */
	fun formatTotalPlaytime(ms: Long): String
	{
		if (ms <= 0L) return "Not yet played"
		val totalMinutes = ms / 60_000L
		val days = totalMinutes / (24 * 60)
		val hours = (totalMinutes % (24 * 60)) / 60
		val minutes = totalMinutes % 60
		return buildString {
			if (days > 0) append("${days}d ")
			if (days > 0 || hours > 0) append("${hours}h ")
			append("${minutes}m")
		}
	}

	/** e.g. "3h 20m", or "45m" for sessions under an hour, or "Not yet played" for 0/negative. */
	fun formatSessionDuration(ms: Long): String
	{
		if (ms <= 0L) return "Not yet played"
		val totalMinutes = ms / 60_000L
		val hours = totalMinutes / 60
		val minutes = totalMinutes % 60
		return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
	}
}
