// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.toLiveData
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.PsnDiscoveryManager
import com.metallic.chiaki.discovery.serverMac
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers

class MainViewModel(val database: AppDatabase, val preferences: Preferences): ViewModel()
{
	private val disposable = CompositeDisposable()

	val discoveryManager = DiscoveryManager().also {
		it.active = preferences.discoveryEnabled
		it.discoveryActive
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe { preferences.discoveryEnabled = it }
			.addTo(disposable)
	}

	val psnDiscoveryManager = PsnDiscoveryManager(preferences)

	/** Last known good discovered instance + when it was last actually seen, keyed by host name.
	 *  Local UDP discovery replies aren't perfectly reliable — a console can miss a beat and
	 *  briefly drop out of a single raw discoveredHosts batch even though it's still there and
	 *  about to reappear a moment later (confirmed on-device). Without this, combine() below
	 *  would immediately demote that host to the PSN-only fallback tile — a different DisplayHost
	 *  identity with a different status colour — every single time that happens, which is exactly
	 *  what showed up as the list row flickering between a green "Ready" and a theme-coloured one.
	 *  A host is only actually dropped from the discovered bucket once it's been missing
	 *  continuously for longer than [DISCOVERED_HOST_GRACE_MS]. */
	private val discoveredHostCache = mutableMapOf<String, Pair<DiscoveredDisplayHost, Long>>()

	/** Local discovered + manual hosts (without PSN) */
	private val localDisplayHosts by lazy {
		Observables.combineLatest(
			database.manualHostDao().getAll().toObservable(),
			database.registeredHostDao().getAll().toObservable(),
			discoveryManager.discoveredHosts)
			{ manualHosts, registeredHosts, discoveredHosts ->
				val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
				val idRegisteredHosts = registeredHosts.associateBy { it.id }
				Triple(
					discoveredHosts.map {
						DiscoveredDisplayHost(it.serverMac?.let { mac -> macRegisteredHosts[mac] }, it)
					},
					manualHosts.map {
						ManualDisplayHost(it.registeredHost?.let { id -> idRegisteredHosts[id] }, it)
					},
					registeredHosts
				)
			}
			.toLiveData()
	}

	/**
	 * Combined display hosts: local discovered + manual + PSN remote.
	 * PSN hosts are only shown if NOT already discovered locally (by nickname match),
	 * mimicking the Qt app's QmlBackend::hosts() logic.
	 */
	val displayHosts: LiveData<List<DisplayHost>> by lazy {
		val mediator = MediatorLiveData<List<DisplayHost>>()

		fun combine()
		{
			val localData = localDisplayHosts.value
			val psnHosts = psnDiscoveryManager.psnHosts.value ?: emptyList()

			if(localData == null) return

			val (discoveredRaw, manual, registeredHosts) = localData

			// Build PSN nickname -> duid map for enriching discovered hosts
			// Matches Qt: psn_nickname_hosts lookup at qmlbackend.cpp line 855-858
			val psnNicknameDuids = psnHosts.associateBy({ it.name }, { it.duid })

			// Enrich discovered hosts with PSN DUID if nickname matches
			Log.i(TAG, "psnNicknameDuids: ${psnNicknameDuids.keys}")
			val discoveredThisCycle = discoveredRaw.map { host ->
				val matchedDuid = host.name?.let { psnNicknameDuids[it] }
				Log.i(TAG, "Enriching discovered host '${host.name}': matchedDuid=${matchedDuid?.take(16)}")
				if(matchedDuid != null)
					DiscoveredDisplayHost(host.registeredHost, host.discoveredHost, psnDuid = matchedDuid)
				else
					host
			}

			// Refresh the cache with anything actually seen this cycle, then drop anything that's
			// been missing too long — see discoveredHostCache's doc comment for why this exists.
			val now = SystemClock.elapsedRealtime()
			for(host in discoveredThisCycle)
			{
				val key = host.name ?: continue
				discoveredHostCache[key] = host to now
			}
			discoveredHostCache.entries.removeAll { (_, entry) -> now - entry.second > DISCOVERED_HOST_GRACE_MS }
			val discovered = discoveredHostCache.values.map { it.first }

			// Build a set of locally discovered nicknames
			val discoveredNicknames = discovered.mapNotNull { it.name }.toSet()

			// Map registered hosts by nickname for matching PSN hosts
			val nicknameRegisteredHosts = registeredHosts.associateBy { it.serverNickname }

			// Count registered PS4 hosts (non-PS5 targets)
			// Matches Qt's GetPS4RegisteredHostsRegistered()
			val registeredPS4Count = registeredHosts.count { !it.target.isPS5 }

			// Count locally discovered PS4 hosts that are registered
			val discoveredRegisteredPS4Count = discovered.count {
				it.registeredHost != null && !it.isPS5
			}

			// Only show PSN hosts not already discovered locally
			// For the PS4 placeholder: only show if there are registered PS4s
			// not all discovered locally (matching Qt line 910-911 + 2992)
			val psnDisplayHosts = psnHosts
				.filter { psnHost ->
					// Filter out locally discovered hosts
					if(psnHost.name in discoveredNicknames) return@filter false
					// Filter out PS4 placeholder if no registered PS4 hosts,
					// or if all registered PS4s are discovered locally
					if(!psnHost.isPS5 && psnHost.name == "Main PS4 Console")
					{
						return@filter registeredPS4Count > 0 && discoveredRegisteredPS4Count < registeredPS4Count
					}
					true
				}
				.map { psnHost ->
					val registeredHost = nicknameRegisteredHosts[psnHost.name]
					PsnDisplayHost(registeredHost, psnHost)
				}

			Log.i(TAG, "combine(): discovered=${discovered.size}, manual=${manual.size}, psnRaw=${psnHosts.size}, psnFiltered=${psnDisplayHosts.size}, registered=${registeredHosts.size}")
			for(h in psnDisplayHosts)
				Log.i(TAG, "  PSN host: name=${h.name}, duid=${h.duid}, registered=${h.isRegistered}")

			mediator.value = discovered + manual + psnDisplayHosts
		}

		mediator.addSource(localDisplayHosts) { combine() }
		mediator.addSource(psnDiscoveryManager.psnHosts) { combine() }

		mediator
	}

	val discoveryActive by lazy {
		discoveryManager.discoveryActive.toLiveData()
	}

	fun deleteManualHost(manualHost: ManualHost)
	{
		database.manualHostDao()
			.delete(manualHost)
			.onErrorComplete()
			.subscribeOn(Schedulers.io())
			.subscribe()
			.addTo(disposable)
	}

	/** Trigger PSN host discovery refresh */
	fun refreshPsnHosts()
	{
		Log.i(TAG, "refreshPsnHosts() called")
		psnDiscoveryManager.refreshAsync()
	}

	companion object
	{
		private const val TAG = "MainViewModel"
		private const val DISCOVERED_HOST_GRACE_MS = 10_000L
	}

	override fun onCleared()
	{
		super.onCleared()
		disposable.dispose()
		discoveryManager.dispose()
	}
}