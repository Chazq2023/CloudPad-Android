// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Activity
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.metallic.chiaki.common.ext.secondaryPresentationDisplay
import kotlinx.coroutines.launch

/**
 * Detects whether this device has somewhere to put a persistent second-screen touchpad:
 *
 * - [secondaryDisplay] is a true secondary [Display] (an LG Dual Screen accessory, ZTE Axon M,
 *   etc.) that content can actually be drawn on via [android.app.Presentation]. This is what
 *   [StreamActivity] uses to decide whether to show [TouchpadPresentation] and whether the
 *   main screen's touch-to-reveal touchpad overlay should stand down.
 *
 * - [isDualScreen] additionally considers a separating [FoldingFeature] reported by Jetpack
 *   WindowManager (foldables like Surface Duo/Fold). Those devices have no independent Display
 *   to present on, so this signal is only used to default "Touchpad Only" to on for them —
 *   rendering still falls back to the ordinary single-window overlay.
 */
class DualScreenController(private val activity: Activity)
{
	private val displayManager = activity.getSystemService(Activity.DISPLAY_SERVICE) as DisplayManager

	private val _secondaryDisplay = MutableLiveData(activity.secondaryPresentationDisplay())
	val secondaryDisplay: LiveData<Display?> get() = _secondaryDisplay

	private var hasSeparatingFold = false

	private val _isDualScreen = MutableLiveData(computeIsDualScreen())
	val isDualScreen: LiveData<Boolean> get() = _isDualScreen

	private val displayListener = object : DisplayManager.DisplayListener
	{
		override fun onDisplayAdded(displayId: Int) = refresh()
		override fun onDisplayRemoved(displayId: Int) = refresh()
		override fun onDisplayChanged(displayId: Int) = refresh()
	}

	private fun computeIsDualScreen() = _secondaryDisplay.value != null || hasSeparatingFold

	private fun refresh()
	{
		_secondaryDisplay.value = activity.secondaryPresentationDisplay()
		_isDualScreen.value = computeIsDualScreen()
	}

	fun start(owner: LifecycleOwner)
	{
		displayManager.registerDisplayListener(displayListener, null)

		owner.lifecycleScope.launch {
			owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
					hasSeparatingFold = info.displayFeatures
						.filterIsInstance<FoldingFeature>()
						.any { it.isSeparating }
					_isDualScreen.value = computeIsDualScreen()
				}
			}
		}
	}

	fun stop()
	{
		displayManager.unregisterDisplayListener(displayListener)
	}
}
