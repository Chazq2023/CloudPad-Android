// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
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
import com.metallic.chiaki.trophy.TrophyAdapter
import com.metallic.chiaki.trophy.TrophyRepository
import com.metallic.chiaki.trophy.TrophyResult
import com.metallic.chiaki.trophy.buildTrophyListItems
import com.pylux.stream.R
import com.pylux.stream.databinding.ItemQuickSettingsDropdownBinding
import com.pylux.stream.databinding.ItemQuickSettingsEdittextBinding
import com.pylux.stream.databinding.ItemQuickSettingsSeekbarBinding
import com.pylux.stream.databinding.StreamQuickSettingsPanelBinding
import kotlinx.coroutines.launch
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
	private val onDisplayModeChanged: (TransformMode) -> Unit,
	private val requestMicPermission: (onResult: (Boolean) -> Unit) -> Unit
) {
	private val panel = StreamQuickSettingsPanelBinding.inflate(activity.layoutInflater).apply {
		// root.focusable=true (see stream_quick_settings_panel.xml) exists only so a touch tap
		// on empty panel space doesn't fall through to the surface view below — but by default
		// that makes the root itself the very first focus candidate ahead of any of its
		// descendants, so a controller's initial D-pad press lands on this dead end (a plain
		// ViewGroup with no key handling of its own) instead of any actual control.
		root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
	}
	private val panelWidthPx = 320f * activity.resources.displayMetrics.density

	private val pyluxAccentColor: Int = TypedValue().let {
		activity.theme.resolveAttribute(R.attr.pyluxAccent, it, true)
		it.data
	}

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
		// Full controller focus, two-level like the Settings screen's own D-pad navigation:
		// the tab rail is one level, a tab's content is the next level in. Standard Android
		// D-pad/keyboard focus navigation and SeekBar/Spinner adjustment already work natively
		// on whichever view currently has focus, since this Dialog's own window intercepts
		// input ahead of the Activity while shown (see isCapturingInput doc below). The two
		// gaps that navigation alone doesn't cover: BUTTON_A (Cross) isn't one of Android's
		// built-in "confirm" keycodes (only DPAD_CENTER/ENTER are), so it can't activate a
		// focused control, or drill from the rail into a tab's content, without help; and
		// BUTTON_B (Circle) is the PlayStation-convention back/cancel button, used here to
		// step back out of a tab's content to the rail, then (pressed again) to close the panel.
		setOnKeyListener { _, keyCode, event ->
			when
			{
				event.action != KeyEvent.ACTION_UP -> false
				keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B ->
				{
					if(inTabContent) exitToRailScope() else close()
					true
				}
				keyCode == KeyEvent.KEYCODE_BUTTON_A ->
				{
					val focused = currentFocus
					val wasTabButton = focused != null && panel.quickSettingsTabToggle.indexOfChild(focused) >= 0
					focused?.performClick()
					if(wasTabButton) enterContentScope()
					true
				}
				else -> false
			}
		}
	}

	/** True while D-pad focus is inside the currently selected tab's content rather than on the
	 *  rail — see enterContentScope()/exitToRailScope(). */
	private var inTabContent = false

	private val currentMapping: MutableMap<ControllerAction, PhysicalInput> =
		PhysicalInput.resolveMapping(preferences.loadControllerMapping()).toMutableMap()

	private val remapAdapter: RemapAdapter
	private val capture: ControllerRemapCapture

	private val sessionType: StreamSessionType = viewModel.connectInfo.sessionType

	private val trophyRepository = TrophyRepository(preferences)
	private val trophyAdapter = TrophyAdapter()
	private var trophiesLoadedOnce = false

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
		// Without this, the RecyclerView container itself can end up taking focus ahead of its
		// focusable item rows, breaking D-pad navigation into the list — same fix TrophiesActivity
		// already needed for its own (full-screen) trophy list.
		panel.quickSettingsRemapRecyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

		panel.quickSettingsTrophiesRecyclerView.layoutManager = LinearLayoutManager(activity)
		panel.quickSettingsTrophiesRecyclerView.adapter = trophyAdapter
		panel.quickSettingsTrophiesRecyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
		panel.quickSettingsTrophiesRefreshButton.setOnClickListener { loadTrophies(forceRefresh = true) }

		panel.quickSettingsStatsRow.quickSettingsRowLabel.text = activity.getString(R.string.quick_settings_performance_overlay_title)
		panel.quickSettingsOscRow.quickSettingsRowLabel.text = activity.getString(R.string.quick_settings_osc_title)
		panel.quickSettingsTouchpadRow.quickSettingsRowLabel.text = activity.getString(R.string.quick_settings_touchpad_title)
		panel.quickSettingsMicrophoneRow.quickSettingsRowLabel.text = activity.getString(R.string.preferences_microphone_enabled_title)
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
		panel.quickSettingsMicrophoneRow.quickSettingsRowSwitch.setOnCheckedChangeListener { switchView, isChecked ->
			// The switch already visually reflects isChecked before this listener runs.
			// On the permission-request path, leave it as-is (optimistic) and only snap it
			// back off on denial — flipping it here too would re-trigger this same listener.
			if(isChecked && ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
			{
				requestMicPermission { granted ->
					if(granted)
					{
						preferences.micEnabled = true
						viewModel.session.setMicrophoneEnabled(true)
					}
					else
					{
						switchView.isChecked = false
					}
				}
			}
			else
			{
				preferences.micEnabled = isChecked
				viewModel.session.setMicrophoneEnabled(isChecked)
			}
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

		// Buttons are focusable by default but not focusableInTouchMode — open() explicitly
		// focuses the checked tab as soon as the panel appears, before the user's first D-pad
		// press has had a chance to exit touch mode, so that requestFocus() call would otherwise
		// silently fail right when the panel first opens (all later D-pad-driven focus moves are
		// unaffected, since a real key event has exited touch mode by then).
		listOf(
			panel.quickSettingsTabGeneral, panel.quickSettingsTabController,
			panel.quickSettingsTabSession, panel.quickSettingsTabTrophies
		).forEach { it.isFocusableInTouchMode = true }

		// These buttons' colour selectors only vary by checked state (see
		// quick_settings_display_mode_tint.xml) — a focused-but-unchecked tab would otherwise
		// look pixel-identical to an unfocused one, leaving a controller user with no visual
		// sign that D-pad navigation moved anywhere. The rail (tabs, close, disconnect) gets a
		// translucent white highlight; everything inside a tab's content gets the theme-coloured
		// one below, matching the Controller tab's remap list.
		listOf(
			panel.quickSettingsTabGeneral, panel.quickSettingsTabController,
			panel.quickSettingsTabSession, panel.quickSettingsTabTrophies,
			panel.quickSettingsCloseButton, panel.quickSettingsDisconnectButton
		).forEach { addFocusHighlight(it, Color.WHITE, useForeground = true) }

		listOf(
			panel.quickSettingsDisplayModeNormal, panel.quickSettingsDisplayModeZoom,
			panel.quickSettingsDisplayModeStretch
		).forEach { addFocusHighlight(it, pyluxAccentColor, useForeground = true) }

		listOf(
			panel.quickSettingsStatsRow.quickSettingsRowSwitch, panel.quickSettingsOscRow.quickSettingsRowSwitch,
			panel.quickSettingsTouchpadRow.quickSettingsRowSwitch, panel.quickSettingsMicrophoneRow.quickSettingsRowSwitch,
			panel.quickSettingsMotionRow.quickSettingsRowSwitch, panel.quickSettingsHapticsRow.quickSettingsRowSwitch,
			panel.quickSettingsPipRow.quickSettingsRowSwitch, panel.quickSettingsTrophiesRefreshButton
		).forEach { addFocusHighlight(it, pyluxAccentColor) }

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
		panel.quickSettingsTrophiesSection.visibility =
			if(checkedButtonId == R.id.quickSettingsTabTrophies) View.VISIBLE else View.GONE

		// Fetched lazily the first time this tab is opened rather than at construction time
		// (unlike the Session tab's static rows) since it's a live network call — the refresh
		// button handles picking up anything unlocked after that.
		if(checkedButtonId == R.id.quickSettingsTabTrophies && !trophiesLoadedOnce)
		{
			trophiesLoadedOnce = true
			loadTrophies(forceRefresh = false)
		}
	}

	/** Loads the trophy list for whichever game this session is streaming — resolved by
	 *  name/platform match against the account's trophy titles the same way TrophiesActivity
	 *  does. [forceRefresh] bypasses the cached account-wide trophy titles list (the refresh
	 *  button's path) so a trophy unlocked mid-session is picked up; per-game trophy detail
	 *  itself is always fetched fresh regardless, since TrophyRepository never caches that. */
	private fun loadTrophies(forceRefresh: Boolean)
	{
		panel.quickSettingsTrophiesProgressBar.visibility = View.VISIBLE
		panel.quickSettingsTrophiesEmptyText.visibility = View.GONE
		panel.quickSettingsTrophiesRecyclerView.visibility = View.GONE

		val gameName = viewModel.connectInfo.cloudGameName ?: ""
		val platform = viewModel.connectInfo.cloudGamePlatform ?: ""

		activity.lifecycleScope.launch {
			val result = trophyRepository.fetchTrophiesForGame(gameName, platform, forceRefresh)
			panel.quickSettingsTrophiesProgressBar.visibility = View.GONE
			when(result)
			{
				is TrophyResult.Success -> {
					val items = buildTrophyListItems(result.detail)
					if(items.isEmpty())
					{
						showTrophiesEmptyState(activity.getString(R.string.quick_settings_trophies_empty))
					}
					else
					{
						trophyAdapter.items = items
						panel.quickSettingsTrophiesRecyclerView.visibility = View.VISIBLE
					}
				}
				is TrophyResult.NoMatchFound -> showTrophiesEmptyState(activity.getString(R.string.quick_settings_trophies_empty))
				is TrophyResult.Error -> showTrophiesEmptyState(result.message)
			}
		}
	}

	private fun showTrophiesEmptyState(message: String)
	{
		panel.quickSettingsTrophiesEmptyText.text = message
		panel.quickSettingsTrophiesEmptyText.visibility = View.VISIBLE
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
		addFocusHighlight(row.quickSettingsDropdownSpinner, pyluxAccentColor)
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
		addFocusHighlight(row.quickSettingsSeekBar, pyluxAccentColor)
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
		addFocusHighlight(row.quickSettingsEditText, pyluxAccentColor)
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

		panel.quickSettingsMicrophoneRow.quickSettingsRowSwitch.isChecked = preferences.micEnabled
		panel.quickSettingsMotionRow.quickSettingsRowSwitch.isChecked = preferences.motionEnabled
		panel.quickSettingsHapticsRow.quickSettingsRowSwitch.isChecked = preferences.buttonHapticEnabled
		panel.quickSettingsPipRow.quickSettingsRowSwitch.isChecked = preferences.pipEnabled

		panel.root.translationX = panelWidthPx
		if(!dialog.isShowing) dialog.show()

		// Always reopen at rail scope, regardless of which scope it was left in last time.
		exitToRailScope()

		// Nothing has focus by default when the dialog first attaches, so a controller's first
		// D-pad press would have nowhere to move from — land it on the currently selected tab so
		// navigation works immediately without requiring a touch first. Posted rather than called
		// directly: right after dialog.show() the content hasn't finished its first layout pass
		// yet, and a requestFocus() on an unlaid-out view can silently lose out to the platform's
		// own default-focus pass once that layout completes a frame later.
		panel.root.post {
			panel.quickSettingsTabToggle.findViewById<View>(panel.quickSettingsTabToggle.checkedButtonId)
				?.requestFocus()
		}

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

	/** Translucent focus highlight — a low-alpha fill plus a stronger-alpha stroke, matching
	 *  the treatment TrophyAdapter/RemapAdapter's list rows already use. [useForeground] draws
	 *  it as an overlay instead of a background, for MaterialButtons (tab rail, window size,
	 *  close/disconnect) whose background/stroke is already internally managed — overwriting
	 *  that directly would fight the button's own corner radius and outline. Plain widgets
	 *  (switches, spinners, seek bars, edit texts, the trophies refresh button) use background,
	 *  capturing whatever was there before (e.g. an EditText's underline) so it's restored
	 *  rather than lost the moment focus first leaves. */
	private fun addFocusHighlight(view: View, color: Int, useForeground: Boolean = false)
	{
		val fillColor = (0x30 shl 24) or (color and 0x00FFFFFF)
		val strokeColor = (0x99 shl 24) or (color and 0x00FFFFFF)
		val strokeWidthPx = (2f * activity.resources.displayMetrics.density).toInt()
		val original = if(useForeground) view.foreground else view.background
		view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
			val drawable = if(hasFocus)
				GradientDrawable().apply {
					shape = GradientDrawable.RECTANGLE
					setColor(fillColor)
					setStroke(strokeWidthPx, strokeColor)
				}
			else original
			if(useForeground) v.foreground = drawable else v.background = drawable
		}
	}

	/** Drills D-pad focus from the tab rail into the currently selected tab's content — the
	 *  rail is temporarily excluded from focus search so D-pad navigation inside the content
	 *  can't accidentally wander back onto it; only exitToRailScope() (Circle/Back) returns. */
	private fun enterContentScope()
	{
		val container = currentTabContentContainer() ?: return
		val focusables = ArrayList<View>()
		container.addFocusables(focusables, View.FOCUS_DOWN)
		val target = focusables.firstOrNull() ?: return
		panel.quickSettingsTabToggle.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
		inTabContent = true
		target.requestFocus()
	}

	private fun exitToRailScope()
	{
		panel.quickSettingsTabToggle.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
		inTabContent = false
		panel.quickSettingsTabToggle.findViewById<View>(panel.quickSettingsTabToggle.checkedButtonId)
			?.requestFocus()
	}

	private fun currentTabContentContainer(): View? = when(panel.quickSettingsTabToggle.checkedButtonId)
	{
		R.id.quickSettingsTabController -> panel.quickSettingsControllerSection
		R.id.quickSettingsTabGeneral -> panel.quickSettingsGeneralScroll
		R.id.quickSettingsTabSession -> panel.quickSettingsSessionScroll
		R.id.quickSettingsTabTrophies -> panel.quickSettingsTrophiesSection
		else -> null
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
