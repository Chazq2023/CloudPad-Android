// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
 * In-stream "Quick Settings" slide-in panel (opened via the cog icon in the stream overlay).
 * Lets the user change Theme Colour, Remap Controller, Motion, Touch Haptics and
 * Picture-in-Picture without leaving/closing the active stream.
 */
class QuickSettingsPanel(
	private val activity: StreamActivity,
	binding: ActivityStreamBinding,
	private val preferences: Preferences,
	private val streamInput: StreamInput
) {
	private val panel = binding.quickSettingsPanel
	private val snackbarAnchor = binding.root

	private val currentMapping: MutableMap<ControllerAction, PhysicalInput> =
		PhysicalInput.resolveMapping(preferences.loadControllerMapping()).toMutableMap()

	private val remapAdapter: RemapAdapter
	private val capture: ControllerRemapCapture

	private var isOpen = false

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

		val themeValues = activity.resources.getStringArray(R.array.theme_colour_values)
		val themeEntries = activity.resources.getStringArray(R.array.theme_colour_entries)
		panel.quickSettingsThemeSpinner.adapter = ArrayAdapter(
			activity, android.R.layout.simple_spinner_dropdown_item, themeEntries
		)
		val currentThemeIndex = themeValues.indexOf(preferences.getThemeColour()).coerceAtLeast(0)
		panel.quickSettingsThemeSpinner.setSelection(currentThemeIndex, false)
		panel.quickSettingsThemeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				// Saved immediately, but StreamActivity is not recreated: the new colour
				// takes effect next time an activity is (re)created, same as MainActivity.
				preferences.setThemeColour(themeValues[position])
			}
			override fun onNothingSelected(parent: AdapterView<*>?) {}
		}

		panel.quickSettingsMotionSwitch.isChecked = preferences.motionEnabled
		panel.quickSettingsMotionSwitch.setOnCheckedChangeListener { _, isChecked ->
			preferences.motionEnabled = isChecked
			streamInput.setMotionEnabled(isChecked)
		}

		panel.quickSettingsHapticsSwitch.isChecked = preferences.buttonHapticEnabled
		panel.quickSettingsHapticsSwitch.setOnCheckedChangeListener { _, isChecked ->
			preferences.buttonHapticEnabled = isChecked
		}

		panel.quickSettingsPipSwitch.isChecked = preferences.pipEnabled
		panel.quickSettingsPipSwitch.setOnCheckedChangeListener { _, isChecked ->
			preferences.pipEnabled = isChecked
		}

		panel.quickSettingsCloseButton.setOnClickListener { close() }
		panel.quickSettingsSaveButton.setOnClickListener {
			// The one setting that's deferred until Save: rebuild StreamInput's mapping
			// lookup tables so the live session picks up remap edits immediately.
			streamInput.reloadMapping()
			Snackbar.make(snackbarAnchor, R.string.quick_settings_saved, Snackbar.LENGTH_SHORT).show()
			close()
		}
	}

	fun open()
	{
		if(isOpen) return
		isOpen = true
		val root = panel.root
		root.isVisible = true
		root.translationX = if(root.width > 0) root.width.toFloat() else 320f
		root.animate().translationX(0f).setDuration(220L).setListener(null).start()
	}

	fun close()
	{
		if(!isOpen)
		{
			panel.root.isVisible = false
			return
		}
		isOpen = false
		val root = panel.root
		root.animate().translationX(root.width.toFloat()).setDuration(220L)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					root.isVisible = false
				}
			}).start()
	}

	fun toggle() = if(isOpen) close() else open()

	fun handleCaptureKeyEvent(event: KeyEvent): Boolean = capture.handleCaptureKeyEvent(event)
	fun handleCaptureMotionEvent(event: MotionEvent): Boolean = capture.handleCaptureMotionEvent(event)

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
