// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
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

	// The panel is kept permanently View.VISIBLE and only ever moved via translationX — never
	// toggled GONE/VISIBLE. Toggling visibility on a view overlapping a SurfaceView (the video
	// surface, which composites on its own layer) can leave the view stuck un-rendered until
	// something forces a full window redraw (e.g. pulling down the notification shade) — this
	// sidesteps that entirely, since translationX is a pure render-time transform that doesn't
	// remove the view from the layout/draw tree.
	private val panelWidthPx = 320f * activity.resources.displayMetrics.density

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

		// Start off-screen (closed) without ever having gone through a GONE state.
		panel.root.translationX = panelWidthPx
	}

	fun open()
	{
		Log.i("QuickSettingsPanel", "open() called, isOpen=$isOpen, translationX=${panel.root.translationX}")
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

		animateTranslationTo(0f) { Log.i("QuickSettingsPanel", "open animation ended: translationX=${panel.root.translationX}, isOpen=$isOpen") }
	}

	/** Hides the panel without applying any staged (Stats/OSC/Touchpad/Mic/Window Size) edits. */
	fun discardAndClose()
	{
		// Logging the stack trace here (not just a message) so logcat reveals exactly which
		// caller (back-press, Save, PiP entry, etc.) triggered this — needed to track down
		// reports of the panel appearing to open then immediately close again.
		Log.w("QuickSettingsPanel", "discardAndClose() called, isOpen=$isOpen", Exception("call site"))
		if(!isOpen)
		{
			panel.root.translationX = panelWidthPx
			return
		}
		isOpen = false
		animateTranslationTo(panelWidthPx)
	}

	fun toggle()
	{
		Log.i("QuickSettingsPanel", "toggle() called, isOpen=$isOpen")
		if(isOpen) discardAndClose() else open()
	}

	/**
	 * Slides the panel to [targetX]. The panel sits above a SurfaceView (the video surface,
	 * which composites on its own hardware layer); a transform-only change on a plain view can
	 * occasionally fail to trigger a proper recomposite against it, leaving the animation's
	 * *state* correct (translationX/isOpen end up right) while the actual displayed frame
	 * doesn't move — confirmed via logging where the animation completed successfully but
	 * nothing visibly appeared. Forcing a hardware layer for the duration of the animation,
	 * plus an explicit invalidate() on every frame, is the standard fix for this class of
	 * SurfaceView/compositing issue.
	 */
	private fun animateTranslationTo(targetX: Float, onEnd: (() -> Unit)? = null)
	{
		val root = panel.root
		root.animate().cancel()
		root.setLayerType(View.LAYER_TYPE_HARDWARE, null)
		root.animate().translationX(targetX).setDuration(220L)
			.setUpdateListener { root.invalidate() }
			.withEndAction {
				root.setLayerType(View.LAYER_TYPE_NONE, null)
				root.invalidate()
				onEnd?.invoke()
			}
			.start()
	}

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
