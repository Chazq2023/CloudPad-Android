// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.session.ControllerAction
import com.metallic.chiaki.session.ControllerRemapCapture
import com.metallic.chiaki.session.PhysicalInput
import com.metallic.chiaki.session.StreamInput
import com.metallic.chiaki.settings.RemapAdapter
import com.metallic.chiaki.settings.RemapItem
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityStreamBinding

/**
 * In-stream "Quick Settings" slide-in panel. Opened by pressing back (replacing the old
 * bottom overlay bar entirely). Disconnect, Stats, Microphone, On-Screen Controls,
 * Touchpad Only and Window Size are staged here: switching them only updates the panel's
 * own UI state, and is only applied (via [onSaveClicked]) when Save is pressed — pressing
 * back again while the panel is open discards any changes. Motion, Touch Haptics and
 * Picture-in-Picture remain live (applied immediately, as before). Remap Controller edits
 * persist immediately but only take effect on the live session once Save reloads
 * [StreamInput]'s mapping tables.
 */
class QuickSettingsPanel(
	private val activity: StreamActivity,
	binding: ActivityStreamBinding,
	private val preferences: Preferences,
	private val streamInput: StreamInput,
	private val viewModel: StreamViewModel,
	private val getDisplayMode: () -> TransformMode,
	private val onSaveDisplayMode: (TransformMode) -> Unit
) {
	private val panel = binding.quickSettingsPanel
	private val snackbarAnchor = binding.root

	private val currentMapping: MutableMap<ControllerAction, PhysicalInput> =
		PhysicalInput.resolveMapping(preferences.loadControllerMapping()).toMutableMap()

	private val remapAdapter: RemapAdapter
	private val capture: ControllerRemapCapture

	var isOpen = false
		private set

	/** True only while actively listening for the next remap input. StreamActivity checks
	 *  this before forwarding key/motion events to the live game, so a button press made
	 *  while remapping isn't also sent to the console. */
	val isCapturingInput: Boolean get() = capture.isListening

	init {
		capture = ControllerRemapCapture(
			context = activity,
			onInputDetected = { action, input ->
				currentMapping[action] = input
				saveMappingAndRefresh()
			},
			onCleared = { action ->
				currentMapping.remove(action)
				saveMappingAndRefresh()
			}
		)

		remapAdapter = RemapAdapter(buildRemapItems()) { action -> capture.startListeningFor(action) }
		panel.quickSettingsRemapRecyclerView.layoutManager = LinearLayoutManager(activity)
		panel.quickSettingsRemapRecyclerView.adapter = remapAdapter

		panel.quickSettingsStatsRow.quickSettingsRowLabel.text = activity.getString(R.string.quick_settings_stats_title)
		panel.quickSettingsMicRow.quickSettingsRowLabel.text = activity.getString(R.string.preferences_mic_enabled_title)
		panel.quickSettingsOscRow.quickSettingsRowLabel.text = activity.getString(R.string.quick_settings_osc_title)
		panel.quickSettingsTouchpadRow.quickSettingsRowLabel.text = activity.getString(R.string.quick_settings_touchpad_title)
		panel.quickSettingsMotionRow.quickSettingsRowLabel.text = activity.getString(R.string.preferences_motion_enabled_title)
		panel.quickSettingsHapticsRow.quickSettingsRowLabel.text = activity.getString(R.string.preferences_button_haptic_enabled_title)
		panel.quickSettingsPipRow.quickSettingsRowLabel.text = activity.getString(R.string.preferences_pip_enabled_title)

		// On-Screen Controls / Touchpad Only are mutually exclusive within the panel's own
		// staged switches — this enforcement is immediate UI behaviour, independent of Save.
		panel.quickSettingsOscRow.quickSettingsRowSwitch.setOnCheckedChangeListener { _, checked ->
			if(checked) panel.quickSettingsTouchpadRow.quickSettingsRowSwitch.isChecked = false
		}
		panel.quickSettingsTouchpadRow.quickSettingsRowSwitch.setOnCheckedChangeListener { _, checked ->
			if(checked) panel.quickSettingsOscRow.quickSettingsRowSwitch.isChecked = false
		}

		// Motion / Touch Haptics / Picture-in-Picture keep their existing live-apply behaviour.
		panel.quickSettingsMotionRow.quickSettingsRowSwitch.setOnCheckedChangeListener { _, isChecked ->
			preferences.motionEnabled = isChecked
			streamInput.setMotionEnabled(isChecked)
		}
		panel.quickSettingsHapticsRow.quickSettingsRowSwitch.setOnCheckedChangeListener { _, isChecked ->
			preferences.buttonHapticEnabled = isChecked
		}
		panel.quickSettingsPipRow.quickSettingsRowSwitch.setOnCheckedChangeListener { _, isChecked ->
			preferences.pipEnabled = isChecked
		}

		panel.quickSettingsCloseButton.setOnClickListener { discardAndClose() }
		panel.quickSettingsDisconnectButton.setOnClickListener { activity.finish() }
		panel.quickSettingsSaveButton.setOnClickListener { onSaveClicked() }
	}

	fun open()
	{
		if(isOpen) return
		isOpen = true

		// Stats / On-Screen Controls / Touchpad Only / Microphone / Window Size are staged:
		// (re)seed every time the panel opens from the current live values, so a previously
		// discarded edit never leaks into the next time the panel is shown.
		panel.quickSettingsStatsRow.quickSettingsRowSwitch.isChecked = viewModel.showPerformanceOverlay.value ?: false
		panel.quickSettingsOscRow.quickSettingsRowSwitch.isChecked = viewModel.onScreenControlsEnabled.value ?: false
		panel.quickSettingsTouchpadRow.quickSettingsRowSwitch.isChecked = viewModel.touchpadOnlyEnabled.value ?: false
		panel.quickSettingsDisplayModeToggle.check(buttonIdFor(getDisplayMode()))

		val micPermissionGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
		panel.quickSettingsMicRow.quickSettingsRowSwitch.isEnabled = micPermissionGranted
		panel.quickSettingsMicRow.quickSettingsRowSwitch.isChecked = micPermissionGranted && (viewModel.micEnabled.value ?: false)

		panel.quickSettingsMotionRow.quickSettingsRowSwitch.isChecked = preferences.motionEnabled
		panel.quickSettingsHapticsRow.quickSettingsRowSwitch.isChecked = preferences.buttonHapticEnabled
		panel.quickSettingsPipRow.quickSettingsRowSwitch.isChecked = preferences.pipEnabled

		val root = panel.root
		// Cancel any in-flight close animation first — otherwise a rapid open/close/open in
		// quick succession can leave a stale onAnimationEnd callback pending, which would hide
		// the panel again right after this reopens it (it looked like the panel "got stuck").
		root.animate().cancel()
		root.isVisible = true
		root.translationX = if(root.width > 0) root.width.toFloat() else 320f
		root.animate().translationX(0f).setDuration(220L).setListener(null).start()
	}

	/** Hides the panel without applying any staged (Stats/OSC/Touchpad/Mic/Window Size) edits. */
	fun discardAndClose()
	{
		if(!isOpen)
		{
			panel.root.isVisible = false
			return
		}
		isOpen = false
		val root = panel.root
		root.animate().cancel()
		root.animate().translationX(root.width.toFloat()).setDuration(220L)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					// Guard against a stale callback firing after a subsequent open() call
					// already flipped the panel back to open.
					if(!isOpen)
						root.isVisible = false
				}
			}).start()
	}

	fun toggle() = if(isOpen) discardAndClose() else open()

	fun handleCaptureKeyEvent(event: KeyEvent): Boolean = capture.handleCaptureKeyEvent(event)
	fun handleCaptureMotionEvent(event: MotionEvent): Boolean = capture.handleCaptureMotionEvent(event)

	private fun onSaveClicked()
	{
		viewModel.setShowPerformanceOverlay(panel.quickSettingsStatsRow.quickSettingsRowSwitch.isChecked)
		viewModel.setOnScreenControlsEnabled(panel.quickSettingsOscRow.quickSettingsRowSwitch.isChecked)
		viewModel.setTouchpadOnlyEnabled(panel.quickSettingsTouchpadRow.quickSettingsRowSwitch.isChecked)
		if(panel.quickSettingsMicRow.quickSettingsRowSwitch.isEnabled)
			viewModel.setMicEnabled(panel.quickSettingsMicRow.quickSettingsRowSwitch.isChecked)
		onSaveDisplayMode(TransformMode.fromButton(panel.quickSettingsDisplayModeToggle.checkedButtonId))

		// The one setting that's deferred until Save: rebuild StreamInput's mapping lookup
		// tables so the live session picks up remap edits immediately.
		streamInput.reloadMapping()

		Snackbar.make(snackbarAnchor, R.string.quick_settings_saved, Snackbar.LENGTH_SHORT).show()
		discardAndClose()
	}

	private fun buttonIdFor(mode: TransformMode) = when(mode)
	{
		TransformMode.ZOOM -> R.id.quickSettingsDisplayModeZoom
		TransformMode.STRETCH -> R.id.quickSettingsDisplayModeStretch
		TransformMode.FIT -> R.id.quickSettingsDisplayModeNormal
	}

	private fun saveMappingAndRefresh()
	{
		preferences.saveControllerMapping(currentMapping)
		remapAdapter.updateItems(buildRemapItems())
	}

	private fun buildRemapItems(): List<RemapItem>
	{
		val items = mutableListOf<RemapItem>()
		var lastGroup = ""
		for(action in ControllerAction.values())
		{
			if(action.group != lastGroup)
			{
				items.add(RemapItem.Header(action.group))
				lastGroup = action.group
			}
			items.add(RemapItem.ActionItem(action, currentMapping[action]))
		}
		return items
	}
}
