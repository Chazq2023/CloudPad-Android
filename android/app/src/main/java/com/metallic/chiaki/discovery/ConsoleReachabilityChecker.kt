// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.discovery

import android.os.SystemClock
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Local UDP discovery reports a console as READY as soon as its network stack answers a simple
 * broadcast probe — which, confirmed on-device, can happen well before its actual Remote Play
 * session-request TCP listener is accepting connections (a fresh "session request connect
 * failed: No route to host" right after waking a console, followed by success a minute or so
 * later on plain retry, is exactly that gap). Discovery alone can't tell the two apart.
 *
 * This checks the real thing: whether a TCP connection to the session-request port actually
 * succeeds, by attempting one directly. Used to drive the "Waking console" tile state — shown
 * from the moment a wake is requested (or noticed) until this confirms the console can actually
 * be connected to.
 */
class ConsoleReachabilityChecker
{
	companion object
	{
		private const val TAG = "ConsoleReachabilityChecker"

		/** SESSION_PORT in lib/src/session.c — the TCP port the actual "Starting session
		 *  request" HTTP-over-TCP handshake connects to, same for both PS4 and PS5. */
		private const val SESSION_REQUEST_PORT = 9295

		private const val PROBE_TIMEOUT_MS = 2000
		private const val PROBE_INTERVAL_MS = 3000L

		/** Give up and let the caller fall back to whatever discovery already says, rather than
		 *  showing "Waking console" forever if something's actually wrong (console didn't
		 *  actually wake, or a firewall blocks this port specifically). */
		private const val MAX_WAIT_MS = 2 * 60_000L
	}

	/** Runs a background probe loop against [host], calling [onResult] on a background thread
	 *  once a connection succeeds (true) or [MAX_WAIT_MS] elapses without one (false). Fire and
	 *  forget — callers are responsible for not stacking duplicate loops for the same host (see
	 *  MainViewModel.markWaking). Only opens and immediately closes a bare TCP connection; never
	 *  sends the actual session-request payload, so a console that's still asleep or a genuinely
	 *  unreachable one just sees (and safely ignores) a connection attempt that never completes
	 *  or gets closed right away. */
	fun probeUntilReachable(host: String, onResult: (Boolean) -> Unit)
	{
		if(host.isBlank())
		{
			onResult(false)
			return
		}
		Thread {
			val deadline = SystemClock.elapsedRealtime() + MAX_WAIT_MS
			var reachable = false
			while(SystemClock.elapsedRealtime() < deadline)
			{
				reachable = isReachable(host)
				if(reachable)
					break
				Thread.sleep(PROBE_INTERVAL_MS)
			}
			Log.i(TAG, "probeUntilReachable($host) -> $reachable")
			onResult(reachable)
		}.apply { name = "ConsoleReachabilityProbe" }.start()
	}

	private fun isReachable(host: String): Boolean = try
	{
		Socket().use { socket ->
			socket.connect(InetSocketAddress(host, SESSION_REQUEST_PORT), PROBE_TIMEOUT_MS)
		}
		true
	}
	catch(e: Exception)
	{
		false
	}
}
