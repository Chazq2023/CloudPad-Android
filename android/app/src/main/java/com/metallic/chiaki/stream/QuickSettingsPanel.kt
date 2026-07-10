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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.StreamSessionType
import com.metallic.chiaki.lib.sessionType
import com.metallic.chiaki.session.ControllerAction
import com.metallic.chiaki.session.ControllerRemapCapture
import com.metallic.chiaki.session.PhysicalInput
import com.metallic.chiaki.session.StreamInput
import com.metallic.chiaki.settings.RemapAdapter
import com.metallic.chiaki.settings.RemapItem
import com.pylux.stream.R
import com.pylux.stream.databinding.ItemQuickSettingsDropdownBinding
import com.pylux.stream.databinding.ItemQuickSettingsEdittextBinding
import com.pylux.stream.databinding.ItemQuickSettingsSeekbarBinding
import com.pylux.stream.databinding.StreamQuickSettingsPanelBinding
import org.json.JSONArray

/**
 * In-stream "Quick Settings" slide-in panel. Opened by pressing back (replacing the old
 * bottom overlay bar entirely). A left-hand tab rail splits the scrollable body into three
 * sections, only one of which is visible at a time: a General tab (Performance Overlay,
 * On-Screen Controls, Touchpad Only, Window Size, Motion, Touch Haptics, Picture-in-Picture),
 * a Controller tab (Remap Controller), and a Session tab whose content depends on the current
 * [StreamSessionType] — Remote Play/Resolution/FPS/Bitrate/Codec, Game Catalog, or Game
 * Library streaming settings, built at construction time since the type never changes during
 * one Activity's lifetime. Disconnect is the power icon pinned bottom-left below the tab rail,
 * always tinted with the app's theme colour regardless of tab. There is no Save button — every
 * control applies immediately: switches write straight to [viewModel]/[preferences] and apply
 * live in the same listener that flips them, the Window Size toggle calls [onDisplayModeChanged]
 * as soon as a button is checked, and remap edits both persist immediately and call
 * [StreamInput.reloadMapping] right away so the live session picks up the new mapping without
 * waiting for anything else.
 *
 * The Session tab's settings are baked into the stream at connect time (video profile / cloud
 * allocation), so changing them here can't take effect live on the current stream — a static
 * notice at the bottom of that tab tells the user as much, and that restarting the stream is
 * required for changes there to take effect.
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

	private val sessionType: StreamSessionType = viewModel.connectInfo.sessionType

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
		panel.quickSettingsDisconnectButton.setOnClickListener { dismissImmediately(); activity.finish() }

		buildSessionSettingsTab()

		// Left-hand tab rail: General Settings / Controller Mapping / Session Settings. Only
		// one section is visible at a time; the toggle group's own checked-state colouring
		// (theme colour when selected, white otherwise) is handled entirely by
		// QuickSettingsTabButton's icon/stroke colour selector, so this listener only needs
		// to swap section visibility.
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
		panel.quickSettingsControllerSection.visibility =
			if(checkedButtonId == R.id.quickSettingsTabController) View.VISIBLE else View.GONE
		panel.quickSettingsGeneralScroll.visibility =
			if(checkedButtonId == R.id.quickSettingsTabGeneral) View.VISIBLE else View.GONE
		panel.quickSettingsSessionScroll.visibility =
			if(checkedButtonId == R.id.quickSettingsTabSession) View.VISIBLE else View.GONE
	}

	// ---- Session tab: content depends on sessionType, built once (it never changes during
	// this Activity's lifetime) ----

	private fun buildSessionSettingsTab()
	{
		val container = panel.quickSettingsSessionRows
		when(sessionType)
		{
			StreamSessionType.REMOTE_PLAY -> buildRemotePlayRows(container)
			StreamSessionType.CATALOG_PSNOW -> buildCloudRows(container, isLibrary = false)
			StreamSessionType.LIBRARY_PSCLOUD -> buildCloudRows(container, isLibrary = true)
		}
	}

	private fun buildRemotePlayRows(container: LinearLayout)
	{
		addSectionLabel(container, R.string.preferences_category_title_stream)

		addDropdownRow(
			container, R.string.preferences_resolution_title,
			entries = Preferences.resolutionAll.map { activity.getString(it.title) },
			values = Preferences.resolutionAll.map { it.value },
			currentValue = preferences.resolution.value
		) { value ->
			Preferences.resolutionAll.firstOrNull { it.value == value }?.let { preferences.resolution = it }
		}

		addDropdownRow(
			container, R.string.preferences_fps_title,
			entries = Preferences.fpsAll.map { activity.getString(it.title) },
			values = Preferences.fpsAll.map { it.value },
			currentValue = preferences.fps.value
		) { value ->
			Preferences.fpsAll.firstOrNull { it.value == value }?.let { preferences.fps = it }
		}

		addEditTextRow(
			container, R.string.preferences_bitrate_title,
			hint = activity.getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto),
			currentValue = preferences.bitrate
		) { value ->
			preferences.bitrate = value
		}

		addDropdownRow(
			container, R.string.preferences_codec_title,
			entries = Preferences.codecAll.map { activity.getString(it.title) },
			values = Preferences.codecAll.map { it.value },
			currentValue = preferences.codec.value
		) { value ->
			Preferences.codecAll.firstOrNull { it.value == value }?.let { preferences.codec = it }
		}
	}

	private fun buildCloudRows(container: LinearLayout, isLibrary: Boolean)
	{
		addSectionLabel(
			container,
			if(isLibrary) R.string.preferences_category_title_game_library else R.string.preferences_category_title_game_catalog
		)

		val resEntries = activity.resources.getStringArray(
			if(isLibrary) R.array.cloud_resolution_pscloud_entries else R.array.cloud_resolution_psnow_entries
		).toList()
		val resValues = activity.resources.getStringArray(
			if(isLibrary) R.array.cloud_resolution_pscloud_values else R.array.cloud_resolution_psnow_values
		).toList()
		val currentRes = if(isLibrary) preferences.getCloudResolutionPscloud() else preferences.getCloudResolutionPsnow()
		addDropdownRow(
			container,
			if(isLibrary) R.string.preferences_cloud_resolution_pscloud_title else R.string.preferences_cloud_resolution_psnow_title,
			resEntries, resValues, currentRes.toString()
		) { value ->
			val intValue = value.toIntOrNull() ?: return@addDropdownRow
			if(isLibrary) preferences.setCloudResolutionPscloud(intValue) else preferences.setCloudResolutionPsnow(intValue)
		}

		val (dcEntries, dcValues) = datacenterEntries(
			if(isLibrary) preferences.getCloudDatacentersJsonPscloud() else preferences.getCloudDatacentersJsonPsnow()
		)
		val currentDc = if(isLibrary) preferences.getCloudDatacenterPscloud() else preferences.getCloudDatacenterPsnow()
		addDropdownRow(
			container,
			if(isLibrary) R.string.preferences_cloud_datacenter_pscloud_title else R.string.preferences_cloud_datacenter_psnow_title,
			dcEntries, dcValues, currentDc
		) { value ->
			if(isLibrary) preferences.setCloudDatacenterPscloud(value) else preferences.setCloudDatacenterPsnow(value)
		}

		val bitrateSummaryRes = if(isLibrary) R.string.preferences_cloud_bitrate_pscloud_summary else R.string.preferences_cloud_bitrate_psnow_summary
		val currentBitrateMbps = (if(isLibrary) preferences.getCloudBitratePscloud() else preferences.getCloudBitratePsnow()) / 1000
		addSeekBarRow(
			container, bitrateSummaryRes,
			min = 2, max = 200, currentValue = currentBitrateMbps
		) { valueMbps ->
			if(isLibrary) preferences.setCloudBitratePscloud(valueMbps * 1000) else preferences.setCloudBitratePsnow(valueMbps * 1000)
		}
	}

	/** Mirrors SettingsFragment's populateCloudDatacenterPreference: "Auto" is always the first
	 *  option, followed by each pinged datacenter as "name (RTTms)". */
	private fun datacenterEntries(json: String): Pair<List<String>, List<String>>
	{
		val entries = mutableListOf("Auto (Best Ping)")
		val values = mutableListOf("Auto")
		if(json.isNotEmpty())
		{
			runCatching {
				val datacenters = JSONArray(json)
				for(i in 0 until datacenters.length())
				{
					val dc = datacenters.getJSONObject(i)
					val name = dc.optString("dataCenter", "")
					val rtt = dc.optInt("rtt", 0)
					if(name.isNotEmpty())
					{
						entries.add(if(rtt in 1..998) "$name (${rtt}ms)" else name)
						values.add(name)
					}
				}
			}
		}
		return entries to values
	}

	private fun addSectionLabel(container: LinearLayout, textRes: Int)
	{
		val label = activity.layoutInflater.inflate(R.layout.item_quick_settings_section_label, container, false) as TextView
		label.text = activity.getString(textRes)
		container.addView(label)
	}

	private fun addDropdownRow(
		container: LinearLayout,
		labelRes: Int,
		entries: List<String>,
		values: List<String>,
		currentValue: String,
		onSelected: (String) -> Unit
	)
	{
		val row = ItemQuickSettingsDropdownBinding.inflate(activity.layoutInflater, container, true)
		row.quickSettingsDropdownLabel.text = activity.getString(labelRes)
		// The closed spinner's text needs its own white-text layout — StreamTheme is a Light
		// MaterialComponents theme, so the system default item layout renders near-black text
		// that's unreadable against this dark panel. The dropdown list popup keeps the system
		// default layout, since that popup already renders on a light background where dark
		// text is legible.
		row.quickSettingsDropdownSpinner.adapter =
			ArrayAdapter(activity, R.layout.item_quick_settings_spinner_item, entries).apply {
				setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
			}
		row.quickSettingsDropdownSpinner.setSelection(values.indexOf(currentValue).coerceAtLeast(0), false)
		row.quickSettingsDropdownSpinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener
		{
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
				onSelected(values[position])
			override fun onNothingSelected(parent: AdapterView<*>?) {}
		}
	}

	private fun addSeekBarRow(
		container: LinearLayout,
		summaryRes: Int,
		min: Int,
		max: Int,
		currentValue: Int,
		onChanged: (Int) -> Unit
	)
	{
		val row = ItemQuickSettingsSeekbarBinding.inflate(activity.layoutInflater, container, true)
		fun updateLabel(value: Int) { row.quickSettingsSeekBarLabel.text = activity.getString(summaryRes, value) }
		row.quickSettingsSeekBar.max = max - min
		row.quickSettingsSeekBar.progress = (currentValue - min).coerceIn(0, max - min)
		updateLabel(currentValue)
		row.quickSettingsSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener
		{
			override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)
			{
				val value = progress + min
				updateLabel(value)
				if(fromUser) onChanged(value)
			}
			override fun onStartTrackingTouch(seekBar: SeekBar) {}
			override fun onStopTrackingTouch(seekBar: SeekBar) {}
		})
	}

	private fun addEditTextRow(
		container: LinearLayout,
		labelRes: Int,
		hint: String,
		currentValue: Int?,
		onChanged: (Int?) -> Unit
	)
	{
		val row = ItemQuickSettingsEdittextBinding.inflate(activity.layoutInflater, container, true)
		row.quickSettingsEditTextLabel.text = activity.getString(labelRes)
		row.quickSettingsEditText.hint = hint
		row.quickSettingsEditText.setText(currentValue?.toString() ?: "")
		row.quickSettingsEditText.doAfterTextChanged { text -> onChanged(text?.toString()?.toIntOrNull()) }
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

	/** Used ahead of Disconnect instead of [close]'s animated dismiss: the Dialog is created with
	 *  the Activity as its context, so if the Activity finishes while it's still showing, Android
	 *  throws a WindowLeaked crash — an animated close's dialog.dismiss() only runs 220ms later
	 *  via withEndAction, which isn't soon enough when the caller finishes right after. This
	 *  dismisses synchronously first. */
	private fun dismissImmediately()
	{
		isOpen = false
		panel.root.animate().cancel()
		if(dialog.isShowing) dialog.dismiss()
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
