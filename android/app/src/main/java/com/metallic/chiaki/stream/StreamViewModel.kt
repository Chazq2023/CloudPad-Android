// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metallic.chiaki.cloudplay.CloudConnectInfoBuilder
import com.metallic.chiaki.cloudplay.api.CloudStreamingBackend
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.session.StreamInput
import com.metallic.chiaki.session.StreamSession
import com.metallic.chiaki.session.StreamStateConnected
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import com.pylux.stream.R
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class OverlayData(
	val metrics: SessionMetrics,
	val jitter: Double,
	val header: String,
	val fpsHistory: List<Float>
)

/** State of an in-stream settings-driven session restart (Quick Settings panel's Apply button).
 *  Only covers the pre-reconnect phase, which looks different per session type: for Cloud Play
 *  it's the server-side Gaikai re-allocation (can take a while, reports progress via InProgress's
 *  message); for Remote Play there's no allocation step, but it's still held InProgress for the
 *  brief window where tearing down the old session synchronously fires a QuitEvent that would
 *  otherwise be indistinguishable from a real disconnect (see both
 *  [StreamViewModel.restartRemotePlaySession] and [StreamSession.restartWithNewConnectInfo]'s
 *  doc comments). Once a new [ConnectInfo] is actually handed to
 *  [StreamSession.restartWithNewConnectInfo], this goes back to [Idle] and the ordinary
 *  [com.metallic.chiaki.session.StreamState] machinery (Connecting/Connected/error) takes over,
 *  reusing the same dialogs StreamActivity already shows for any other connection failure. */
sealed class SessionRestartState
{
	object Idle: SessionRestartState()
	data class InProgress(val message: String): SessionRestartState()
	data class Failed(val message: String): SessionRestartState()
}

class StreamViewModel(
	val application: Application,
	val connectInfo: ConnectInfo
) : ViewModel() {

	val preferences = Preferences(application)
	val logManager = LogManager(application)
	val input = StreamInput(application, preferences, isRemotePlay = connectInfo.cloudSessionId.isNullOrBlank())
	val session = StreamSession(connectInfo, logManager, preferences.logVerbose, input)

	private var _onScreenControlsEnabled = MutableLiveData(preferences.onScreenControlsEnabled)
	val onScreenControlsEnabled: LiveData<Boolean> get() = _onScreenControlsEnabled

	private var _touchpadOnlyEnabled = MutableLiveData(preferences.touchpadOnlyEnabled)
	val touchpadOnlyEnabled: LiveData<Boolean> get() = _touchpadOnlyEnabled

	private var _showPerformanceOverlay = MutableLiveData(preferences.showPerformanceOverlay)
	val showPerformanceOverlay: LiveData<Boolean> get() = _showPerformanceOverlay

	private var _overlayData = MutableLiveData<OverlayData>()
	val overlayData: LiveData<OverlayData> get() = _overlayData

	private var _sessionRestartState = MutableLiveData<SessionRestartState>(SessionRestartState.Idle)
	val sessionRestartState: LiveData<SessionRestartState> get() = _sessionRestartState

	private var metricsDisposable: Disposable? = null

	private val rttSamples = ArrayDeque<Double>()
	private val fpsHistory = ArrayDeque<Float>()

	private val maxRttSamples = 30
	private val maxFpsHistory = 60

	private val header: String = buildString {
		val isCloud = !connectInfo.cloudSessionId.isNullOrBlank()

		if (isCloud) {
			val serviceLabel = if (connectInfo.serviceType == "pscloud") {
				"Cloud Play"
			} else {
				"PS Now"
			}

			val server = connectInfo.host.ifBlank { "Cloud" }
			append("$serviceLabel • $server")
		} else {
			val consoleLabel = if (connectInfo.ps5) "PS5" else "PS4"
			append("Remote Play • $consoleLabel")
		}
	}

	private fun startMetricsPolling() {
		stopMetricsPolling()

		rttSamples.clear()
		fpsHistory.clear()

		metricsDisposable = Observable.interval(1, TimeUnit.SECONDS)
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe {
				session.session?.getMetrics()?.let { metrics ->
					rttSamples.addLast(metrics.ping)

					if (rttSamples.size > maxRttSamples) {
						rttSamples.removeFirst()
					}

					val jitter = computeJitter()

					fpsHistory.addLast(metrics.fps)

					if (fpsHistory.size > maxFpsHistory) {
						fpsHistory.removeFirst()
					}

					_overlayData.postValue(
						OverlayData(
							metrics = metrics,
							jitter = jitter,
							header = header,
							fpsHistory = fpsHistory.toList()
						)
					)
				}
			}
	}

	private fun computeJitter(): Double {
		if (rttSamples.size < 2) return 0.0

		val mean = rttSamples.average()
		val variance = rttSamples
			.map { (it - mean) * (it - mean) }
			.average()

		return sqrt(variance)
	}

	private fun stopMetricsPolling() {
		metricsDisposable?.dispose()
		metricsDisposable = null
	}

	init {
		session.state.observeForever { state ->
			if (state is StreamStateConnected) {
				startMetricsPolling()
			}
		}
	}

	override fun onCleared() {
		super.onCleared()
		stopMetricsPolling()
		session.shutdown()
	}

	fun setOnScreenControlsEnabled(enabled: Boolean) {
		preferences.onScreenControlsEnabled = enabled
		_onScreenControlsEnabled.value = enabled
	}

	fun setTouchpadOnlyEnabled(enabled: Boolean) {
		preferences.touchpadOnlyEnabled = enabled
		_touchpadOnlyEnabled.value = enabled
	}

	fun setShowPerformanceOverlay(show: Boolean) {
		preferences.showPerformanceOverlay = show
		_showPerformanceOverlay.value = show
	}

	/** Remote Play only. The console-identifying fields (host, registKey, PSN account/DUID) never
	 *  change mid-session — only the video profile does, built fresh from whatever Resolution/
	 *  FPS/Bitrate/Codec are currently in [preferences] (the Quick Settings rows already write
	 *  straight through on every change, same as every other row in that panel). Unlike Cloud
	 *  Play there's no separate allocation step to await here — but for a PSN connection (as
	 *  opposed to a direct-IP LAN one), StreamSession.resume() still has to redo the full
	 *  holepunch handshake from scratch to get a fresh NAT-punched session before it can even
	 *  start reconnecting, since the video profile is only ever sent once, in the same initial
	 *  handshake that creates the native Session. So this can take as long as the original
	 *  connection did — StreamSession.state's usual Connecting phase (already surfaced by both
	 *  StreamActivity's spinner and the Quick Settings panel's status text) is the only progress
	 *  signal for that, same as any other PSN reconnect.
	 *
	 *  Also stands sessionRestartState up around the call — not for progress reporting like
	 *  Cloud Play uses it for (there's no allocation step here to report on), but because
	 *  StreamSession.restartWithNewConnectInfo's teardown of the *old* session synchronously
	 *  fires a QuitEvent on it, and sessionRestartState being InProgress is exactly what
	 *  StreamActivity's suppressQuitDialogForRestart checks to recognize that as an expected
	 *  side effect of this restart rather than a real disconnect. See both of those doc comments
	 *  for the full story (a real, on-device crash without this). */
	fun restartRemotePlaySession() {
		_sessionRestartState.value = SessionRestartState.InProgress(application.getString(R.string.quick_settings_session_restarting))
		val newConnectInfo = session.connectInfo.copy(videoProfile = preferences.videoProfile)
		session.restartWithNewConnectInfo(newConnectInfo, onResuming = {
			_sessionRestartState.value = SessionRestartState.Idle
		})
	}

	/** Cloud Play only (PS Now Catalog / PS Cloud Library). Resolution/bitrate/datacenter are
	 *  baked server-side into the allocated session's launch spec, so picking up a change means
	 *  re-running the Gaikai allocation flow for a brand new session — there's no "modify in
	 *  place" API. Deliberately allocates the new session *before* touching the current one:
	 *  the old stream keeps running untouched until a replacement is actually ready, so a failed
	 *  allocation (bad network, game no longer streamable, etc.) just reports an error rather
	 *  than leaving the user disconnected. */
	fun restartCloudSession() {
		val serviceType = connectInfo.serviceType
		val gameIdentifier = connectInfo.cloudGameIdentifier
		if(serviceType == null || gameIdentifier == null) {
			_sessionRestartState.value = SessionRestartState.Failed("Missing cloud session info")
			return
		}
		_sessionRestartState.value = SessionRestartState.InProgress("Preparing…")
		viewModelScope.launch {
			val backend = CloudStreamingBackend(application, preferences)
			val result = backend.startCompleteCloudSession(
				serviceType = serviceType,
				gameIdentifier = gameIdentifier,
				gameName = connectInfo.cloudGameName ?: "",
				npssoToken = preferences.getNpssoToken(),
				ownedEntitlementId = connectInfo.cloudOwnedEntitlementId ?: "",
				ownedPlatform = connectInfo.cloudGamePlatform ?: "",
				onProgress = { message -> _sessionRestartState.postValue(SessionRestartState.InProgress(message)) }
			)
			result.onSuccess { cloudStreamSession ->
				val newConnectInfo = CloudConnectInfoBuilder.build(
					cloudStreamSession, preferences, gameIdentifier, connectInfo.cloudGameProductId
				)
				_sessionRestartState.value = SessionRestartState.Idle
				session.restartWithNewConnectInfo(newConnectInfo)
			}
			result.onFailure { error ->
				_sessionRestartState.value = SessionRestartState.Failed(error.message ?: "Failed to apply new settings")
			}
		}
	}

	/** Called once the Quick Settings panel has shown the user a [SessionRestartState.Failed]
	 *  message, so it doesn't linger and get re-shown (e.g. to a freshly-attached observer). */
	fun acknowledgeSessionRestartFailure() {
		if(_sessionRestartState.value is SessionRestartState.Failed)
			_sessionRestartState.value = SessionRestartState.Idle
	}
}
