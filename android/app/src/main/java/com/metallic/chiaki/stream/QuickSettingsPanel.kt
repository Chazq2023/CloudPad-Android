// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.session.ControllerAction
import com.metallic.chiaki.session.ControllerRemapCapture
import com.metallic.chiaki.session.PhysicalInput
import com.metallic.chiaki.session.StreamInput
import com.metallic.chiaki.settings.RemapAdapter
import com.metallic.chiaki.settings.RemapItem
import com.pylux.stream.R
import com.pylux.stream.databinding.StreamQuickSettingsPanelBinding

/**
 * In-stream "Quick Settings" slide-in panel. Opened by pressing back (replacing the old
 * bottom overlay bar entirely). A left-hand tab rail splits the scrollable body into two
 * sections, only one of which is visible at a time: a Controller tab (Remap Controller) and
 * a General tab (Performance Overlay, On-Screen Controls, Touchpad Only, Window Size, Motion,
 * Touch Haptics, Picture-in-Picture). Disconnect is the power icon pinned bottom-left below
 * the tab rail, always tinted with the app's theme colour regardless of tab. There is no
 * Save button — every control applies immediately: switches
 * write straight to [viewModel]/[preferences] and apply live in the same listener that flips
 * them, the Window Size toggle calls [onDisplayModeChanged] as soon as a button is checked, and
 * remap edits both persist immediately and call [StreamInput.reloadMapping] right away so the
 * live session picks up the new mapping without waiting for anything else.
 *
 * Hosted in its own [Dialog] (a separate window) rather than a View inside
 * activity_stream.xml. It used to share the activity's window with the video SurfaceView,
 * which continuously receives new decoded frames — animating/toggling a plain View there
 * proved unreliable to composite correctly (confirmed via logging: the animation completed
 * with the correct end state, but nothing visibly updated), even after forcing a hardware
 * layer. A separate window is composited above the activity deterministically by the OS,
 * sidestepping that whole class of problem.
 */
class QuickSettingsPanel(
	private val activity: StreamActivity,
	private val preferences: Preferences,
	private val streamInput: StreamInput,
	private val viewModel: StreamViewModel,
	private val getDisplayMode: () -> TransformMode,
	private val onDisplayModeChanged: (TransformMode) -> Unit
) {
	private val panel = StreamQuickSettingsPanelBinding.inflate(activity.layoutInflater)
	private val panelWidthPx = 320f * activity.resources.displayMetrics.density

	private val dialog: Dialog = Dialog(activity).apply {
		requestWindowFeature(Window.FEATURE_NO_TITLE)
		setContentView(panel.root)
		setCancelable(false)
		setCanceledOnTouchOutside(false)
		window?.apply {
			setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
			setDimAmount(0f)
			setLayout(panelWidthPx.toInt(), WindowManager.LayoutParams.MATCH_PARENT)
			setGravity(Gravity.END)
		}
		setOnKeyListener { _, keyCode, event ->
			if(keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP)
			{
				close()
				true
			}
			else false
		}
	}

	private val currentMapping: MutableMap<ControllerAction, PhysicalInput> =
		PhysicalInput.resolveMapping(preferences.loadControllerMapping()).toMutableMap()

	private val remapAdapter: RemapAdapter
	private val capture: ControllerRemapCapture

	var isOpen = false
		private set

	/** True only while actively listening for the next remap input. StreamActivity checks
	 *  this before forwarding key/motion events to the live game, so a button press made
	 *  while remapping isn't also sent to the console. Kept even though the panel's own
	 *  Dialog window already naturally intercepts input ahead of the activity while shown. */
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

		panel.quickSettingsStatsRow.quickSettingsRowLabel.text = activity.getString(R.string.quick_settings_performance_overlay_title)
		panel.quickSettingsOscRow.quickSettingsRowLabel.text = activity.getString(R.string.quick_settings_osc_title)
		panel.quickSettingsTouchpadRow.quickSettingsRowLabel.text = activity.getString(R.string.quick_settings_touchpad_title)
		panel.quickSettingsMotionRow.quickSettingsRowLabel.text = activity.getString(R.string.preferences_motion_enabled_title)
		panel.quickSettingsHapticsRow.quickSettingsRowLabel.text = activity.getString(R.string.preferences_button_haptic_enabled_title)
		panel.quickSettingsPipRow.quickSettingsRowLabel.text = activity.getString(R.string.preferences_pip_enabled_title)

		// Every switch applies immediately — there's no Save button. On-Screen Controls /
		// Touchpad Only additionally stay mutually exclusive with each other.
		panel.quickSettingsStatsRow.quickSettingsRowSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setShowPerformanceOverlay(isChecked)
		}
		panel.quickSettingsOscRow.quickSettingsRowSwitch.setOnCheckedChangeListener { _, checked ->
			if(checked) panel.quickSettingsTouchpadRow.quickSettingsRowSwitch.isChecked = false
			viewModel.setOnScreenControlsEnabled(checked)
		}
		panel.quickSettingsTouchpadRow.quickSettingsRowSwitch.setOnCheckedChangeListener { _, checked ->
			if(checked) panel.quickSettingsOscRow.quickSettingsRowSwitch.isChecked = false
			viewModel.setTouchpadOnlyEnabled(checked)
		}
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

		// Window Size applies immediately too, as soon as a new option is checked.
		panel.quickSettingsDisplayModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if(!isChecked) return@addOnButtonCheckedListener
			onDisplayModeChanged(TransformMode.fromButton(checkedId))
		}

		panel.quickSettingsCloseButton.setOnClickListener { close() }
		panel.quickSettingsDisconnectButton.setOnClickListener { activity.finish() }

		// Left-hand tab rail: Controller Mapping / General Settings. Only one section is
		// visible at a time; the toggle group's own checked-state colouring (theme colour
		// when selected, white otherwise) is handled entirely by QuickSettingsTabButton's
		// icon/stroke colour selector, so this listener only needs to swap section visibility.
		panel.quickSettingsTabToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if(!isChecked) return@addOnButtonCheckedListener
			showTab(checkedId)
		}
		showTab(panel.quickSettingsTabToggle.checkedButtonId)

		// Start off-screen (closed).
		panel.root.translationX = panelWidthPx
	}

	private fun showTab(checkedButtonId: Int)
	{
		val showController = checkedButtonId == R.id.quickSettingsTabController
		panel.quickSettingsControllerSection.visibility = if(showController) View.VISIBLE else View.GONE
		panel.quickSettingsGeneralScroll.visibility = if(showController) View.GONE else View.VISIBLE
	}

	fun open()
	{
		if(isOpen) return
		isOpen = true

		// Re-sync every switch/toggle to the current live value each time the panel opens —
		// state can change elsewhere while it's closed (e.g. PiP forces On-Screen Controls
		// and Touchpad Only off), so the panel must not show stale state from last time.
		panel.quickSettingsStatsRow.quickSettingsRowSwitch.isChecked = viewModel.showPerformanceOverlay.value ?: false
		panel.quickSettingsOscRow.quickSettingsRowSwitch.isChecked = viewModel.onScreenControlsEnabled.value ?: false
		panel.quickSettingsTouchpadRow.quickSettingsRowSwitch.isChecked = viewModel.touchpadOnlyEnabled.value ?: false
		panel.quickSettingsDisplayModeToggle.check(buttonIdFor(getDisplayMode()))

		panel.quickSettingsMotionRow.quickSettingsRowSwitch.isChecked = preferences.motionEnabled
		panel.quickSettingsHapticsRow.quickSettingsRowSwitch.isChecked = preferences.buttonHapticEnabled
		panel.quickSettingsPipRow.quickSettingsRowSwitch.isChecked = preferences.pipEnabled

		panel.root.translationX = panelWidthPx
		if(!dialog.isShowing) dialog.show()
		panel.root.animate().cancel()
		panel.root.animate().translationX(0f).setDuration(220L).start()
	}

	fun close()
	{
		if(!isOpen)
		{
			if(dialog.isShowing) dialog.dismiss()
			return
		}
		isOpen = false
		panel.root.animate().cancel()
		panel.root.animate().translationX(panelWidthPx).setDuration(220L)
			.withEndAction { if(dialog.isShowing) dialog.dismiss() }
			.start()
	}

	fun toggle()
	{
		if(isOpen) close() else open()
	}

	fun handleCaptureKeyEvent(event: KeyEvent): Boolean = capture.handleCaptureKeyEvent(event)
	fun handleCaptureMotionEvent(event: MotionEvent): Boolean = capture.handleCaptureMotionEvent(event)

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
		// Rebuild StreamInput's mapping lookup tables immediately so the live session picks
		// up the edit right away — there's no Save button to defer this to any more.
		streamInput.reloadMapping()
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
