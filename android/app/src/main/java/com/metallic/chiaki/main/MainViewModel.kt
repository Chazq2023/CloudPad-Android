// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.toLiveData
import com.metallic.chiaki.discovery.ConsoleReachabilityChecker
import com.metallic.chiaki.discovery.ConsoleSleepIntent
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.PsnDiscoveryManager
import com.metallic.chiaki.discovery.serverMac
import com.metallic.chiaki.lib.DiscoveryHost
import androidx.lifecycle.MutableLiveData
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

/** See MainViewModel.hostTransitions' doc comment. */
enum class HostTransitionState { WAKING, CONFIRMED_ON_WAITING }

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

	private val reachabilityChecker = ConsoleReachabilityChecker()

	/** Host IDs (see DisplayHost.id) currently mid-transition after a wake, driving the
	 *  "Waking console" / "Console on, please wait..." tile states. Two steps because confirmed
	 *  on-device that neither local discovery reporting READY nor even a successful raw TCP
	 *  connection to the Remote Play session-request port (ConsoleReachabilityChecker) means the
	 *  console can actually accept a real session request yet — connecting right after either of
	 *  those signals alone can still fail with a session-quit error. WAKING covers the wait until
	 *  either signal confirms the console is on; CONFIRMED_ON_WAITING covers a further fixed
	 *  grace period after that before finally calling it Ready. */
	private val _hostTransitions = MutableLiveData<Map<String, HostTransitionState>>(emptyMap())
	val hostTransitions: LiveData<Map<String, HostTransitionState>> get() = _hostTransitions

	private fun setTransition(id: String, state: HostTransitionState?)
	{
		val current = _hostTransitions.value ?: emptyMap()
		_hostTransitions.value = if(state == null) current - id else current + (id to state)
	}

	/** Explicit wake trigger (Wake Up tap) — starts a reachability probe on top of the WAKING
	 *  state; see confirmConsoleOn for what happens once that (or discovery on its own, via
	 *  combine() below) confirms the console is actually on. */
	fun markWaking(host: DisplayHost)
	{
		val id = host.id ?: return
		if(_hostTransitions.value?.get(id) != null) return // already mid-transition
		setTransition(id, HostTransitionState.WAKING)
		val mainHandler = Handler(Looper.getMainLooper())
		reachabilityChecker.probeUntilReachable(host.host) { reachable ->
			// Hop back to the main thread before touching anything else here — the probe itself
			// runs on a background thread, but discoveredHostCache below is only ever otherwise
			// touched from combine(), which always runs on the main thread (LiveData's own
			// dispatch guarantee), and MutableLiveData.value= (as opposed to postValue) also
			// requires it.
			mainHandler.post {
				if(reachable)
					confirmConsoleOn(id, host.name)
				else
					setTransition(id, null) // gave up; fall back to whatever discovery says
			}
		}
	}

	/** Organic wake (discovery noticing STANDBY -> READY on its own, e.g. via the console's own
	 *  controller) or a successful reachability probe from markWaking above — either way, moves
	 *  straight to the fixed post-confirmation wait rather than immediately calling it Ready.
	 *  Also corrects a stale discovery cache entry to READY right away (see markReachableNow) so
	 *  the underlying data is at least accurate once the wait finishes, even if discovery itself
	 *  is still lagging behind by then. */
	private fun confirmConsoleOn(id: String, hostName: String?)
	{
		setTransition(id, HostTransitionState.CONFIRMED_ON_WAITING)
		markReachableNow(hostName)
		Handler(Looper.getMainLooper()).postDelayed({
			setTransition(id, null)
		}, CONFIRMED_ON_WAIT_MS)
	}

	/** Confirmed on-device: local discovery can keep reporting a console as STANDBY for a good
	 *  while after it's actually finished waking and is genuinely reachable — without this, the
	 *  tile would fall back to a stale "Asleep" the moment the transition states above clear,
	 *  instead of the "Ready" they just confirmed. Our own signals are more trustworthy than
	 *  discovery's for this specific question ("can I actually connect right now"), so this
	 *  corrects the cached entry directly rather than waiting for discovery to eventually catch
	 *  up on its own. */
	private fun markReachableNow(hostName: String?)
	{
		val key = hostName ?: return
		val (cachedHost, _) = discoveredHostCache[key] ?: return
		if(cachedHost.discoveredHost.state == DiscoveryHost.State.READY)
			return
		val corrected = DiscoveredDisplayHost(
			cachedHost.registeredHost,
			cachedHost.discoveredHost.copy(state = DiscoveryHost.State.READY),
			cachedHost.psnDuid
		)
		discoveredHostCache[key] = corrected to SystemClock.elapsedRealtime()
	}

	/** Wraps DiscoveryManager.resume() — discovery is paused while streaming and while this
	 *  fragment isn't the visible tab (see RemotePlayFragment's onResume/onPause), which can add
	 *  up to anywhere from a couple of seconds (a quick peek at the Cloud Play tab) to a full
	 *  stream session. Deliberately does NOT clear discoveredHostCache/locallyConfirmedHostNames
	 *  here — used to, but that forced a full rediscovery from scratch on every single return to
	 *  this tab, even a two-second one, which is actively harmful for a console that's actually
	 *  asleep: a resting console only answers discovery sporadically (confirmed on-device — one
	 *  replied once right as it entered rest mode, then not again for over a minute), so wiping
	 *  the cache could show a confusing "Getting Console Status" for a while after returning even though
	 *  the console had been confirmed Asleep seconds earlier and nothing about it had changed.
	 *  discoveredHostCache's own grace-period pruning (see its doc comment) already handles
	 *  staleness correctly on its own: it compares against real elapsed time, which keeps
	 *  advancing while paused, so a gap longer than the grace period prunes the entry exactly as
	 *  it would if discovery had been running the whole time — no separate clear-on-resume is
	 *  needed to avoid showing stale data. */
	fun resumeDiscovery()
	{
		discoveryManager.resume()
	}

	/** Last known good discovered instance + when it was last actually seen, keyed by host name.
	 *  Local UDP discovery replies aren't perfectly reliable — a console can miss a beat and
	 *  briefly drop out of a single raw discoveredHosts batch even though it's still there and
	 *  about to reappear a moment later (confirmed on-device). Without this, combine() below
	 *  would immediately demote that host to the PSN-only fallback tile — a different DisplayHost
	 *  identity with a different status colour — every single time that happens, which is exactly
	 *  what showed up as the list row flickering between a green "Ready" and a theme-coloured one.
	 *  A host is only actually dropped from the discovered bucket once it's been missing
	 *  continuously for longer than [DISCOVERED_HOST_GRACE_MS] — or, for a host last seen in
	 *  STANDBY, [DISCOVERED_HOST_ASLEEP_GRACE_MS]: a console that's actually asleep is *expected*
	 *  to go quiet on discovery for long stretches (confirmed on-device — one replied with
	 *  STANDBY exactly once right as it entered rest mode, then didn't answer another discovery
	 *  ping for over a minute), unlike an awake console falling silent, which usually does mean
	 *  something's wrong. Using the short grace period for both meant a confirmed-asleep console
	 *  got demoted back to the less-certain "Getting Console Status" tile within seconds of correctly
	 *  showing "Asleep", just because it was doing exactly what a sleeping console should.
	 *  Entries are only actually pruned when combine() runs — see cacheExpiryTicker's doc
	 *  comment for why that alone isn't enough and needs a time-based nudge too. */
	private val discoveredHostCache = mutableMapOf<String, Pair<DiscoveredDisplayHost, Long>>()

	/** Host names local discovery has actually replied for at least once since the last
	 *  [resumeDiscovery] reset — unlike [discoveredHostCache], never pruned by a grace period, so
	 *  it still remembers a host after its cache entry has expired and it's fallen back to the
	 *  PSN-only tile. Used purely to recognise a cold power-on (a console that was fully off, not
	 *  just resting, so it never sent a STANDBY reply for the usual STANDBY -> READY organic-wake
	 *  check to catch — see the discoveredThisCycle loop below) as still deserving the same
	 *  post-wake settle time as any other wake, rather than being called "Ready" the instant it
	 *  answers a single ping. NOT used for the Offline/Finding distinction below — a console can
	 *  be legitimately, permanently off from the very first moment this app session ever looked
	 *  for it (confirmed on-device: a fresh app launch with the console already off sat in
	 *  "Getting Console Status" forever, since that host's name had never once been in this set), so that
	 *  distinction needs its own short, real-time-based grace instead (see firstMissingAt). */
	private val locallyConfirmedHostNames = mutableSetOf<String>()

	/** elapsedRealtime() a PSN-registered console was first noticed missing from local discovery
	 *  (i.e. first became a psnDisplayHosts fallback tile candidate), cleared the moment it's
	 *  discovered locally again. Drives the Offline/Finding split on that fallback tile: for the
	 *  first [FINDING_CONSOLE_GRACE_MS] after a console goes missing (or, equivalently, for that
	 *  long after the app starts looking for a console it's never once heard from), it reads as
	 *  "Getting Console Status" — plausibly just mid-boot or mid-lookup. Past that, it reads as "Console
	 *  Offline" instead, regardless of whether this session ever heard from it before; unlike
	 *  [locallyConfirmedHostNames], this is a fixed real-time window, not tied to session history,
	 *  so a console that's been off since before the app was ever opened still gets a definitive
	 *  Offline verdict instead of sitting in "Getting Console Status" indefinitely. */
	private val firstMissingAt = mutableMapOf<String, Long>()

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
				// Noticed the console wake up on its own (e.g. via its own controller), not
				// through this app's Wake Up button. Discovery has already told us it's on here,
				// so there's no need for markWaking's own reachability probe — go straight to
				// the fixed post-confirmation wait it shares with a successful probe. See
				// confirmConsoleOn's doc comment for why that wait exists at all.
				val previousState = discoveredHostCache[key]?.first?.discoveredHost?.state
				// Covers two organic-wake shapes: waking from rest mode (discovery reported
				// STANDBY last cycle, now READY), and a genuine cold power-on (the console was
				// fully off, not resting, so it never sent a STANDBY reply for the check above to
				// catch — its cache entry just aged out — but locallyConfirmedHostNames still
				// remembers we'd heard from it before, so this isn't the ordinary first-ever
				// sighting of a console that's simply been sat there Ready the whole time).
				val coldPowerOn = previousState == null && key in locallyConfirmedHostNames
				val id = host.id
				if((previousState == DiscoveryHost.State.STANDBY || coldPowerOn) && host.discoveredHost.state == DiscoveryHost.State.READY &&
					id != null && _hostTransitions.value?.get(id) == null)
					confirmConsoleOn(id, host.name)
				// Confirms a pending sleep request (see ConsoleSleepIntent) actually took —
				// nothing left to wait for once discovery itself reports STANDBY.
				if(host.discoveredHost.state == DiscoveryHost.State.STANDBY)
					ConsoleSleepIntent.clearPendingSleep(host.host)
				discoveredHostCache[key] = host to now
				locallyConfirmedHostNames.add(key)
			}
			discoveredHostCache.entries.removeAll { (_, entry) ->
				val (cachedHost, lastSeenAt) = entry
				val grace = if(cachedHost.discoveredHost.state == DiscoveryHost.State.STANDBY)
					DISCOVERED_HOST_ASLEEP_GRACE_MS
				else
					DISCOVERED_HOST_GRACE_MS
				now - lastSeenAt > grace
			}
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
					// See firstMissingAt's doc comment: a fixed real-time grace, not tied to
					// whether we've ever heard from this console locally before.
					val missingSince = firstMissingAt.getOrPut(psnHost.name) { now }
					val confirmedOffline = now - missingSince >= FINDING_CONSOLE_GRACE_MS
					PsnDisplayHost(registeredHost, psnHost, confirmedOffline = confirmedOffline)
				}
			firstMissingAt.keys.retainAll { it !in discoveredNicknames }

			Log.i(TAG, "combine(): discovered=${discovered.size}, manual=${manual.size}, psnRaw=${psnHosts.size}, psnFiltered=${psnDisplayHosts.size}, registered=${registeredHosts.size}")
			for(h in psnDisplayHosts)
				Log.i(TAG, "  PSN host: name=${h.name}, duid=${h.duid}, registered=${h.isRegistered}")

			mediator.value = discovered + manual + psnDisplayHosts
		}

		mediator.addSource(localDisplayHosts) { combine() }
		mediator.addSource(psnDiscoveryManager.psnHosts) { combine() }
		// discoveredHostCache's grace-period expiry (see its doc comment) is only actually
		// enforced as a side effect of combine() running — without this, a host that stops
		// replying to discovery *entirely* (no more raw emissions at all, e.g. a console in a
		// deep sleep state that never sends another STANDBY reply) would never trigger combine()
		// again, so its stale cached tile would never get re-evaluated and could sit there
		// indefinitely — well past its grace period — until something unrelated (a PSN poll)
		// happened to fire first. This ticks it on a fixed schedule instead.
		mediator.addSource(cacheExpiryTicker) { combine() }
		// confirmConsoleOn's completion may have just corrected a stale cache entry to READY
		// (markReachableNow) right before clearing its id from here — without this as a source
		// too, that correction would just sit in the cache unseen until some other unrelated
		// trigger next happened to call combine(), instead of the tile updating to "Ready" (from
		// "Console on, please wait...") right away.
		mediator.addSource(hostTransitions) { combine() }

		mediator
	}

	private val cacheExpiryTicker by lazy {
		Observable.interval(CACHE_EXPIRY_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)
			.observeOn(AndroidSchedulers.mainThread())
			.toLiveData()
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
		private const val DISCOVERED_HOST_ASLEEP_GRACE_MS = 5 * 60_000L
		private const val CACHE_EXPIRY_CHECK_INTERVAL_MS = 5_000L
		/** Confirmed on-device: even after local discovery reports READY *and* a raw TCP
		 *  connection to the Remote Play session-request port succeeds, the console can still
		 *  reject a real session request for a while longer — this is how long
		 *  confirmConsoleOn waits past either of those signals before finally calling it Ready. */
		private const val CONFIRMED_ON_WAIT_MS = 30_000L
		/** How long a PSN-registered console can go unseen by local discovery before its fallback
		 *  tile stops reading "Getting Console Status" and starts reading "Console Offline" — see
		 *  firstMissingAt's doc comment. */
		private const val FINDING_CONSOLE_GRACE_MS = 10_000L
	}

	override fun onCleared()
	{
		super.onCleared()
		disposable.dispose()
		discoveryManager.dispose()
	}
}