// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.metallic.chiaki.lib.ControllerState
import com.pylux.stream.databinding.PresentationTouchpadBinding
import io.reactivex.Observable

/**
 * Shows a touchpad on a dual-screen device's second panel. Unlike the main screen's
 * touch-to-reveal touchpad overlay, this one is meant to be the only thing on that panel, so
 * it stays drawn ([TouchpadView.alwaysVisible]) rather than fading in only while touched.
 *
 * Kept alive for as long as [DualScreenController] reports a secondary display — visibility of
 * the touchpad itself tracks [touchpadOnlyEnabled] so toggling "Touchpad Only" off just blanks
 * the second screen instead of tearing down and recreating the presentation window.
 */
class TouchpadPresentation(
	context: Context,
	display: Display,
	private val touchpadOnlyEnabled: LiveData<Boolean>
) : Presentation(context, display)
{
	private lateinit var binding: PresentationTouchpadBinding

	val controllerState: Observable<ControllerState> get() = binding.touchpadView.controllerState

	private val visibilityObserver = Observer<Boolean> { enabled ->
		binding.touchpadView.visibility = if(enabled == true) View.VISIBLE else View.GONE
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = PresentationTouchpadBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.touchpadView.alwaysVisible = true
		touchpadOnlyEnabled.observeForever(visibilityObserver)
	}

	override fun dismiss()
	{
		touchpadOnlyEnabled.removeObserver(visibilityObserver)
		super.dismiss()
	}
}
