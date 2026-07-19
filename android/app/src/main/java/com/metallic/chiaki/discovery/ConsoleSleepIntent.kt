// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.discovery

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tiny in-memory, process-wide signal for "the user just asked this console (by IP) to go to
 * sleep" — bridging StreamSession (in an active Remote Play stream, a completely different
 * Activity/ViewModel from the host list) back to MainViewModel's host list tile, which only
 * remembers per-host *discovery* state and has no other way to know a sleep request is in
 * flight until discovery eventually confirms STANDBY on its own. Written by
 * StreamSession.requestConsoleSleep() right before sending the request; read by
 * MainViewModel.combine() (typically moments later, once the disconnect that always follows a
 * sleep request lands the user back on the host list) to show "Console sleeping, please wait"
 * instead of either a stale "Ready" or the less specific "Getting Console Status" fallback.
 *
 * Deliberately just an in-memory signal, not persisted anywhere — this is transient UI state
 * for a request that's either already resolved or moot by the time the app would ever restart.
 */
object ConsoleSleepIntent
{
	private val pendingHosts = CopyOnWriteArraySet<String>()

	fun markPendingSleep(host: String)
	{
		if(host.isNotBlank())
			pendingHosts.add(host)
	}

	fun clearPendingSleep(host: String)
	{
		pendingHosts.remove(host)
	}

	fun isPendingSleep(host: String) = host.isNotBlank() && host in pendingHosts
}
