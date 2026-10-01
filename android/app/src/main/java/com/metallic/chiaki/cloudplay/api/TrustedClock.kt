// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.api

import android.os.SystemClock
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Current time as Sony's servers see it, for checks the user mustn't be able to get around by
 * changing the device clock (see PsCloudOwnership.isReleased). Every HTTP response from a Sony
 * host carries a Date header; HttpClient feeds them in here, and the time is carried forward with
 * elapsedRealtime, which counts from boot and doesn't move when the wall clock is changed.
 */
object TrustedClock
{
	private val SONY_HOST_SUFFIXES = listOf("playstation.net", "playstation.com", "sony.com", "gaikai.com")

	private class Anchor(val serverMs: Long, val elapsedMs: Long)

	@Volatile private var anchor: Anchor? = null

	/** Swappable for unit tests, where SystemClock isn't available. */
	internal var elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }

	fun isSonyHost(host: String): Boolean =
		SONY_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }

	fun recordResponse(host: String, headers: Map<String?, List<String>>)
	{
		if (!isSonyHost(host)) return
		val date = headers.entries.firstOrNull { it.key.equals("Date", ignoreCase = true) }?.value?.firstOrNull() ?: return
		val serverMs = parseHttpDate(date) ?: return
		anchor = Anchor(serverMs, elapsedRealtime())
	}

	/** Sony's current time, or null if no Sony response has been seen since the app started. */
	fun nowOrNull(): Long?
	{
		val a = anchor ?: return null
		return a.serverMs + (elapsedRealtime() - a.elapsedMs)
	}

	/** RFC 7231 IMF-fixdate, e.g. "Thu, 01 Oct 2026 17:00:00 GMT". */
	fun parseHttpDate(value: String): Long?
	{
		return try
		{
			SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
				.apply { timeZone = TimeZone.getTimeZone("GMT") }
				.parse(value)?.time
		}
		catch (e: ParseException) { null }
	}

	internal fun reset() { anchor = null }
}
