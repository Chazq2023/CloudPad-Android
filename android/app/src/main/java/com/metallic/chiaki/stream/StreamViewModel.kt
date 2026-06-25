// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.session.StreamInput
import com.metallic.chiaki.session.StreamSession
import com.metallic.chiaki.session.StreamStateConnected
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

data class OverlayData(
	val metrics: SessionMetrics,
	val jitter: Double,
	val header: String,
	val fpsHistory: List<Float>
)

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
}