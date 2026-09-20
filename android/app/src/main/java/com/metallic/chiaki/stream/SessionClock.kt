package com.metallic.chiaki.stream

import android.os.SystemClock
import java.util.Locale

/**
 * How long the current stream has been active, for the performance overlay's Session row.
 * Starts at 00:00:00 when the stream first connects — not while a cloud game is still being
 * allocated — and keeps counting through in-stream restarts and reconnects, since those are the
 * same play session from the user's side. A new stream gets a new StreamViewModel and so a new
 * clock. Uses elapsedRealtime, so it keeps counting while the app is backgrounded or in PiP.
 */
class SessionClock(private val now: () -> Long = SystemClock::elapsedRealtime)
{
	private var startedAtMs: Long? = null

	/** Starts the clock the first time the stream connects; later connects (restart/resume) are ignored. */
	fun markConnected()
	{
		if(startedAtMs == null)
			startedAtMs = now()
	}

	fun elapsedSeconds(): Long =
		startedAtMs?.let { ((now() - it) / 1000).coerceAtLeast(0) } ?: 0L

	companion object
	{
		/** hh:mm:ss, hours growing past two digits rather than wrapping. */
		fun format(totalSeconds: Long): String
		{
			val s = totalSeconds.coerceAtLeast(0)
			return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
		}
	}
}
