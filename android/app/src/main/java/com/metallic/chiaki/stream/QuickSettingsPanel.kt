// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.metallic.chiaki.common.ext.disableDefaultFocusHighlight
import com.metallic.chiaki.common.ext.fixFocusOnFastScroll
import com.metallic.chiaki.friends.ChatMessage
import com.metallic.chiaki.friends.ChatMessageAdapter
import com.metallic.chiaki.friends.ConversationResult
import com.metallic.chiaki.friends.Friend
import com.metallic.chiaki.friends.FriendAdapter
import com.metallic.chiaki.friends.FriendsRepository
import com.metallic.chiaki.friends.FriendsResult
import com.metallic.chiaki.trophy.TrophyCompareAdapter
import com.metallic.chiaki.trophy.TrophyCompareRepository
import com.metallic.chiaki.trophy.TrophyComparisonResult
import com.metallic.chiaki.trophy.bindTrophyCompareHeader
import com.metallic.chiaki.lib.StreamSessionType
import com.metallic.chiaki.lib.sessionType
import com.metallic.chiaki.session.ControllerAction
import com.metallic.chiaki.session.ControllerRemapCapture
import com.metallic.chiaki.session.PhysicalInput
import com.metallic.chiaki.session.StreamInput
import com.metallic.chiaki.session.StreamStateConnected
import com.metallic.chiaki.session.StreamStateConnecting
import com.metallic.chiaki.session.StreamStateCreateError
import com.metallic.chiaki.session.StreamStateQuit
import com.metallic.chiaki.settings.RemapAdapter
import com.metallic.chiaki.settings.RemapItem
import com.metallic.chiaki.trophy.TrophyAdapter
import com.metallic.chiaki.trophy.TrophyRepository
import com.metallic.chiaki.trophy.TrophyResult
import com.metallic.chiaki.trophy.buildTrophyListItems
import com.metallic.chiaki.trophy.showTrophyDetailDialog
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
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
 * allocation), so they can't take effect live on the current stream by themselves. Unlike every
 * other row in this panel, its rows don't apply — or persist to [preferences] — immediately:
 * edits are held in [pendingRemotePlaySettings]/[pendingCloudSettings] only, so closing the
 * panel or disconnecting without ever tapping Apply leaves Preferences exactly as they were.
 * A pinned Apply button appears at the bottom of the tab's content area as soon as a row differs
 * from whatever the live stream actually last (re)started with, and disappears again if reverted
 * back. Tapping it commits the pending edits to Preferences (see [commitPendingSessionSettings])
 * and kicks off a restart: Remote Play reconnects with a freshly-built video profile — near-instant
 * for a direct-IP LAN console, but for a PSN one it redoes the full holepunch handshake first
 * (same cost as the original connect, since the profile only ever goes out in the same one-time
 * handshake that creates the native session), so it can take just as long. Cloud Play re-runs the
 * Gaikai allocation flow for a brand new session first and only swaps the live one over once that
 * succeeds, so a failed allocation leaves the current stream untouched rather than disconnecting
 * the user. See [StreamViewModel.restartRemotePlaySession] / [StreamViewModel.restartCloudSession]
 * and [updateSessionApplyVisibility].
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
	private val gameImageUrl: String,
	private val getDisplayMode: () -> TransformMode,
	private val onDisplayModeChanged: (TransformMode) -> Unit,
	private val requestMicPermission: (onResult: (Boolean) -> Unit) -> Unit,
	private val onCasSharpeningChanged: (enabled: Boolean, level: Int) -> Unit
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
					when
					{
						inTrophyCompare -> backFromTrophyCompare()
						inFriendChat -> backToFriendsList()
						inTabContent -> exitToRailScope()
						else -> close()
					}
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

	/** Debounces re-enabling the trophies refresh button's focusability after a burst of
	 *  scroll-driven row detaches — see the trophies RecyclerView's OnChildAttachStateChangeListener. */
	private val trophiesScrollSettleHandler = Handler(Looper.getMainLooper())
	private val reenableTrophiesRefreshFocusable = Runnable {
		panel.quickSettingsTrophiesRefreshButton.isFocusable = true
	}

	private val currentMapping: MutableMap<ControllerAction, PhysicalInput> =
		PhysicalInput.resolveMapping(preferences.loadControllerMapping()).toMutableMap()

	private val remapAdapter: RemapAdapter
	private val capture: ControllerRemapCapture

	private val sessionType: StreamSessionType = viewModel.connectInfo.sessionType

	// ---- Session tab: Apply-gated restart (see class doc comment) ----

	private data class RemotePlaySettingsSnapshot(
		val resolution: Preferences.Resolution,
		val fps: Preferences.FPS,
		val bitrate: Int?,
		val codec: Preferences.Codec
	)

	private data class CloudSettingsSnapshot(
		val resolution: Int,
		val datacenter: String,
		val bitrateKbps: Int
	)

	/** Snapshot of the settings the live stream actually last (re)started with, i.e. what's
	 *  currently in [preferences] as of the last successful Apply. Only one of the two is ever
	 *  non-null, matching [sessionType]. Advanced to the current values once a restart they
	 *  triggered reaches [StreamStateConnected]; left alone on failure so Apply stays available
	 *  to retry. */
	private var remotePlayBaseline: RemotePlaySettingsSnapshot? = null
	private var cloudBaseline: CloudSettingsSnapshot? = null

	/** The Session tab's not-yet-applied edits. Rows write here, not straight to [preferences]
	 *  like every other row in this panel — [commitPendingSessionSettings] is what actually
	 *  copies these into Preferences, called only from [applySessionSettings] right before the
	 *  restart that picks them up. Confirmed on-device this needed fixing: previously rows wrote
	 *  straight through, so a value changed here and never Applied was already permanently saved
	 *  the moment it was picked — even if the user disconnected instead of tapping Apply — and
	 *  would come back the next time a session was opened despite never having taken effect on
	 *  the one it was changed in. Initialised to match the baseline whenever the tab's rows are
	 *  (re)built, and only ever compared against — never assigned from — the live values in
	 *  Preferences. Only one of the two is ever non-null, matching [sessionType]. */
	private var pendingRemotePlaySettings: RemotePlaySettingsSnapshot? = null
	private var pendingCloudSettings: CloudSettingsSnapshot? = null

	/** True from the moment Apply is tapped until the restart it triggered resolves (either
	 *  outcome) — gates whether the next [StreamStateConnected]/error transition should advance
	 *  the baseline above. Without this, the panel's very first connection on open() would also
	 *  count. */
	private var pendingSessionRestart = false

	/** Every dropdown/seekbar/edittext control added to the Session tab's rows, so they can all
	 *  be disabled while a restart is in flight — otherwise a further edit made mid-restart would
	 *  be silently folded into the baseline once that restart's (unrelated) success lands. */
	private val sessionRowControls = mutableListOf<View>()

	private val trophyRepository = TrophyRepository(preferences)
	private val trophyAdapter = TrophyAdapter(onTrophyClick = { trophy -> showTrophyDetailDialog(activity, trophy) })
	private var trophiesLoadedOnce = false

	private val friendsRepository = FriendsRepository(preferences)
	private val friendAdapter = FriendAdapter(
		onFriendClick = { friend -> showFriendChat(friend) },
		onCompareTrophiesClick = { friend -> showTrophyCompare(friend) }
	)
	private val chatMessageAdapter = ChatMessageAdapter()
	private var friendsLoadedOnce = false
	/** True while D-pad focus is inside the inline chat sub-view of the Friends tab rather than
	 *  its friends-list sub-view — a third nesting level below inTabContent, see the panel's
	 *  BACK/BUTTON_B key handling. */
	private var inFriendChat = false
	private var currentChatGroupId: String? = null

	private val trophyCompareRepository = TrophyCompareRepository(preferences, trophyRepository)
	private val trophyCompareAdapter = TrophyCompareAdapter()
	/** Sibling nesting level to [inFriendChat] — also a direct child of the friends-list
	 *  sub-view, not nested inside chat. */
	private var inTrophyCompare = false
	private var currentCompareAccountId: String? = null

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
		// Holding D-pad down to fast-scroll otherwise recycles the currently-focused row out from
		// under itself: the platform's focus-restoration then falls back to the nearest other
		// focusable view in the window (close/refresh), which is what caused focus to bounce out
		// of the list entirely mid-scroll. A larger off-screen view cache keeps recently-focused
		// rows around instead of tearing them down, so LinearLayoutManager's own scroll-to-follow-
		// focus handling has a real view to hand focus off to.
		panel.quickSettingsRemapRecyclerView.setItemViewCacheSize(20)

		panel.quickSettingsTrophiesRecyclerView.layoutManager = LinearLayoutManager(activity)
		panel.quickSettingsTrophiesRecyclerView.adapter = trophyAdapter
		panel.quickSettingsTrophiesRecyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
		panel.quickSettingsTrophiesRecyclerView.fixFocusOnFastScroll("QSTrophies") {
			// The platform's own (synchronous) default focus-restoration runs the instant a
			// focused row detaches — before fixFocusOnFastScroll's own redirect gets a chance to
			// run — and it's not scoped to the list, so it can briefly land on the refresh button
			// (the nearest other focusable view) every single detach. That's the flicker: refresh
			// flashes focused, then a moment later gets overridden back into the list, over and
			// over while scrolling. Making it transiently unfocusable for as long as detaches keep
			// happening in quick succession (and only that long — restored once scrolling actually
			// stops) removes it from that race entirely without blocking deliberate D-pad-up
			// navigation to it once the list is idle.
			panel.quickSettingsTrophiesRefreshButton.isFocusable = false
			trophiesScrollSettleHandler.removeCallbacks(reenableTrophiesRefreshFocusable)
			trophiesScrollSettleHandler.postDelayed(reenableTrophiesRefreshFocusable, 300L)
		}
		panel.quickSettingsTrophiesRefreshButton.setOnClickListener { loadTrophies(forceRefresh = true) }

		panel.quickSettingsFriendsRecyclerView.layoutManager = LinearLayoutManager(activity)
		panel.quickSettingsFriendsRecyclerView.adapter = friendAdapter
		panel.quickSettingsFriendsRecyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
		panel.quickSettingsFriendsRecyclerView.setItemViewCacheSize(20)
		panel.quickSettingsFriendsRefreshButton.setOnClickListener { loadFriends(forceRefresh = true) }

		panel.quickSettingsFriendChatRecyclerView.layoutManager = LinearLayoutManager(activity).apply { stackFromEnd = true }
		panel.quickSettingsFriendChatRecyclerView.adapter = chatMessageAdapter
		panel.quickSettingsFriendChatRecyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
		panel.quickSettingsFriendChatBackButton.setOnClickListener { backToFriendsList() }
		panel.quickSettingsFriendChatRefreshButton.setOnClickListener { refreshFriendChat() }
		panel.quickSettingsFriendChatSendButton.setOnClickListener { sendFriendChatMessage() }

		panel.quickSettingsTrophyCompareRecyclerView.layoutManager = LinearLayoutManager(activity)
		panel.quickSettingsTrophyCompareRecyclerView.adapter = trophyCompareAdapter
		panel.quickSettingsTrophyCompareRecyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
		panel.quickSettingsTrophyCompareRecyclerView.fixFocusOnFastScroll("QSTrophyCompare")
		panel.quickSettingsTrophyCompareBackButton.setOnClickListener { backFromTrophyCompare() }
		panel.quickSettingsTrophyCompareRefreshButton.setOnClickListener { loadTrophyCompare() }

		// "Current game" header row and the Trophies tab both only make sense for cloud
		// streaming (PS3/PS4/PS5) — Remote Play has no catalog game/trophy title to show.
		val isCloudSession = sessionType != StreamSessionType.REMOTE_PLAY
		panel.quickSettingsTabTrophies.visibility = if(isCloudSession) View.VISIBLE else View.GONE
		if(isCloudSession)
		{
			panel.quickSettingsGameInfoRow.visibility = View.VISIBLE
			val gameName = viewModel.connectInfo.cloudGameName ?: ""
			val gameLabelText = activity.getString(R.string.quick_settings_current_game, gameName)
			// Colours just the "Current game: " label — the title itself stays the TextView's
			// own white — without hardcoding the label text, so it still works if translated.
			panel.quickSettingsGameNameText.text = SpannableString(gameLabelText).apply {
				setSpan(
					ForegroundColorSpan(pyluxAccentColor),
					0, gameLabelText.length - gameName.length,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
				)
			}
			if(gameImageUrl.isNotEmpty())
			{
				panel.quickSettingsGameIcon.load(gameImageUrl) { crossfade(true) }
			}
			else
			{
				panel.quickSettingsGameIcon.setImageResource(android.R.drawable.ic_menu_gallery)
			}
		}

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

		// CAS Image Sharpening: applies to the live GL renderer immediately, in the same
		// listener that flips the toggle/moves the slider — no Save button, same as every
		// other row here. Slider only exists visually while the toggle is on.
		panel.quickSettingsCasRow.quickSettingsRowLabel.text = activity.getString(R.string.preferences_cas_sharpening_enabled_title)
		panel.quickSettingsCasSeekBarRow.quickSettingsSeekBar.max = Preferences.CAS_SHARPENING_LEVEL_MAX - Preferences.CAS_SHARPENING_LEVEL_MIN
		panel.quickSettingsCasSeekBarRow.quickSettingsSeekBar.keyProgressIncrement = 1
		panel.quickSettingsCasRow.quickSettingsRowSwitch.setOnCheckedChangeListener { _, isChecked ->
			preferences.casSharpeningEnabled = isChecked
			panel.quickSettingsCasSeekBarRow.root.visibility = if(isChecked) View.VISIBLE else View.GONE
			onCasSharpeningChanged(isChecked, preferences.casSharpeningLevel)
		}
		panel.quickSettingsCasSeekBarRow.quickSettingsSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener
		{
			override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)
			{
				val value = progress + Preferences.CAS_SHARPENING_LEVEL_MIN
				updateCasSeekBarLabel(value)
				if(fromUser)
				{
					preferences.casSharpeningLevel = value
					onCasSharpeningChanged(preferences.casSharpeningEnabled, value)
				}
			}
			override fun onStartTrackingTouch(seekBar: SeekBar) {}
			override fun onStopTrackingTouch(seekBar: SeekBar) {}
		})

		// Window Size applies immediately too, as soon as a new option is checked.
		panel.quickSettingsDisplayModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if(!isChecked) return@addOnButtonCheckedListener
			onDisplayModeChanged(TransformMode.fromButton(checkedId))
		}

		panel.quickSettingsCloseButton.setOnClickListener { close() }
		panel.quickSettingsDisconnectButton.setOnClickListener { showDisconnectOptions() }

		buildSessionSettingsTab()

		panel.quickSettingsSessionApplyButton.setOnClickListener { applySessionSettings() }

		// Cloud Play's allocation flow (the pre-reconnect half of a restart) has no equivalent
		// StreamState — worse, the account-level session lock it goes through forces the *old*
		// cloud session closed server-side partway through (confirmed on-device: it arrives on
		// the still-live StreamSession as a Quit event), so a stray StreamStateQuit during this
		// window is an expected side effect of the restart, not its outcome. Its own progress/
		// failure is tracked separately here instead; once a new ConnectInfo is actually handed
		// off, this goes back to Idle and the ordinary StreamState observer below takes over for
		// the real outcome. A Failed outcome gets its own recovery dialog from StreamActivity
		// (Retry/Quit) rather than a toast here — the old session is near-certainly already dead
		// by that point (see above), so a passive toast alone would leave the user stranded on a
		// frozen frame with no way back in.
		viewModel.sessionRestartState.observe(activity, Observer { state ->
			if(state is SessionRestartState.Failed)
				pendingSessionRestart = false
			updateSessionApplyVisibility()
		})

		// Drives the reconnect half of a restart (both session types funnel through here once a
		// new ConnectInfo is handed to StreamSession) — advances the baseline on success, leaves
		// it alone on failure so Apply reappears for a retry. Quit/CreateError while
		// sessionRestartState is still InProgress is the stray old-session-killed side effect
		// above, not this attempt's outcome, so it's ignored here too — the real failure (if any)
		// arrives after sessionRestartState drops back to Idle, once restartWithNewConnectInfo has
		// actually been called. StreamActivity's own observer on this same LiveData already shows
		// the user-facing error dialog for that real failure case.
		viewModel.session.state.observe(activity, Observer { state ->
			if(pendingSessionRestart)
			{
				val allocating = viewModel.sessionRestartState.value is SessionRestartState.InProgress
				when(state)
				{
					is StreamStateConnected -> {
						remotePlayBaseline = currentRemotePlaySnapshot()
						cloudBaseline = currentCloudSnapshot()
						pendingSessionRestart = false
					}
					is StreamStateCreateError, is StreamStateQuit -> if(!allocating) pendingSessionRestart = false
					else -> { }
				}
			}
			updateSessionApplyVisibility()
		})

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
			panel.quickSettingsTabSession, panel.quickSettingsTabTrophies, panel.quickSettingsTabFriends
		).forEach { it.isFocusableInTouchMode = true }

		// These buttons' colour selectors only vary by checked state (see
		// quick_settings_display_mode_tint.xml) — a focused-but-unchecked tab would otherwise
		// look pixel-identical to an unfocused one, leaving a controller user with no visual
		// sign that D-pad navigation moved anywhere. The rail (tabs, close, disconnect) gets a
		// translucent white highlight; everything inside a tab's content gets the theme-coloured
		// one below, matching the Controller tab's remap list.
		listOf(
			panel.quickSettingsTabGeneral, panel.quickSettingsTabController,
			panel.quickSettingsTabSession, panel.quickSettingsTabTrophies, panel.quickSettingsTabFriends,
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
			panel.quickSettingsPipRow.quickSettingsRowSwitch, panel.quickSettingsCasRow.quickSettingsRowSwitch,
			panel.quickSettingsCasSeekBarRow.quickSettingsSeekBar, panel.quickSettingsTrophiesRefreshButton,
			panel.quickSettingsFriendsRefreshButton,
			panel.quickSettingsFriendChatBackButton, panel.quickSettingsFriendChatRefreshButton,
			panel.quickSettingsFriendChatInput, panel.quickSettingsFriendChatSendButton,
			panel.quickSettingsTrophyCompareBackButton, panel.quickSettingsTrophyCompareRefreshButton
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
		panel.quickSettingsFriendsSection.visibility =
			if(checkedButtonId == R.id.quickSettingsTabFriends) View.VISIBLE else View.GONE

		// Whichever container just became visible needs its focus-blocking synced to the
		// current scope (see exitToRailScope()'s doc comment) — the container that was blocked
		// before is now GONE and irrelevant, but a freshly-shown one defaults to unblocked
		// (its layout-declared descendantFocusability) unless set here.
		currentTabContentContainer()?.descendantFocusability =
			if(inTabContent) ViewGroup.FOCUS_BEFORE_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS

		// See updateSessionApplyVisibility's doc comment — the Apply bar doesn't belong to any
		// one tab's container, so leaving this tab (or returning to it) needs to re-evaluate it.
		updateSessionApplyVisibility()

		// Fetched lazily the first time this tab is opened rather than at construction time
		// (unlike the Session tab's static rows) since it's a live network call — the refresh
		// button handles picking up anything unlocked after that.
		if(checkedButtonId == R.id.quickSettingsTabTrophies && !trophiesLoadedOnce)
		{
			trophiesLoadedOnce = true
			loadTrophies(forceRefresh = false)
		}
		if(checkedButtonId == R.id.quickSettingsTabFriends && !friendsLoadedOnce)
		{
			friendsLoadedOnce = true
			loadFriends(forceRefresh = false)
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
		panel.quickSettingsTrophiesProgressText.visibility = View.GONE
		panel.quickSettingsTrophiesCountsRow.visibility = View.GONE

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
						showTrophiesEmptyState(activity.getString(R.string.quick_settings_trophies_empty, gameName))
					}
					else
					{
						trophyAdapter.items = items
						panel.quickSettingsTrophiesRecyclerView.visibility = View.VISIBLE
						showTrophiesSummary(result.detail.summary)
					}
				}
				is TrophyResult.NoMatchFound -> showTrophiesEmptyState(activity.getString(R.string.quick_settings_trophies_empty, gameName))
				is TrophyResult.Error -> showTrophiesEmptyState(result.message)
			}
		}
	}

	private fun showTrophiesSummary(summary: TrophyTitleSummary)
	{
		panel.quickSettingsTrophiesProgressText.text = "${summary.progressPercent}% Complete"
		panel.quickSettingsTrophiesProgressText.visibility = View.VISIBLE
		panel.quickSettingsTrophiesPlatinumCount.text = summary.earnedTrophies.platinum.toString()
		panel.quickSettingsTrophiesGoldCount.text = summary.earnedTrophies.gold.toString()
		panel.quickSettingsTrophiesSilverCount.text = summary.earnedTrophies.silver.toString()
		panel.quickSettingsTrophiesBronzeCount.text = summary.earnedTrophies.bronze.toString()
		panel.quickSettingsTrophiesCountsRow.visibility = View.VISIBLE
	}

	private fun showTrophiesEmptyState(message: String)
	{
		panel.quickSettingsTrophiesEmptyText.text = message
		panel.quickSettingsTrophiesEmptyText.visibility = View.VISIBLE
	}

	/** Loads the account's friends list — unlike Trophies this isn't tied to the game being
	 *  streamed, so it's the same call regardless of session type. */
	private fun loadFriends(forceRefresh: Boolean)
	{
		panel.quickSettingsFriendsProgressBar.visibility = View.VISIBLE
		panel.quickSettingsFriendsEmptyText.visibility = View.GONE
		panel.quickSettingsFriendsRecyclerView.visibility = View.GONE

		activity.lifecycleScope.launch {
			when(val result = friendsRepository.fetchFriends(forceRefresh))
			{
				is FriendsResult.Success -> {
					panel.quickSettingsFriendsProgressBar.visibility = View.GONE
					if(result.friends.isEmpty())
					{
						showFriendsEmptyState(activity.getString(R.string.quick_settings_friends_empty))
					}
					else
					{
						friendAdapter.items = result.friends
						panel.quickSettingsFriendsRecyclerView.visibility = View.VISIBLE
					}
				}
				is FriendsResult.Error -> {
					panel.quickSettingsFriendsProgressBar.visibility = View.GONE
					showFriendsEmptyState(result.message)
				}
			}
		}
	}

	private fun showFriendsEmptyState(message: String)
	{
		panel.quickSettingsFriendsEmptyText.text = message
		panel.quickSettingsFriendsEmptyText.visibility = View.VISIBLE
	}

	/** Swaps the Friends tab's list sub-view for its inline chat sub-view — never a separate
	 *  Activity, which would background StreamActivity mid-session (see the layout's own comment
	 *  on quickSettingsFriendsSection). */
	private fun showFriendChat(friend: Friend)
	{
		inFriendChat = true
		panel.quickSettingsFriendsListGroup.visibility = View.GONE
		panel.quickSettingsFriendsChatGroup.visibility = View.VISIBLE
		panel.quickSettingsFriendChatTitle.text = friend.onlineId
		panel.quickSettingsFriendChatProgressBar.visibility = View.VISIBLE
		panel.quickSettingsFriendChatEmptyText.visibility = View.GONE
		panel.quickSettingsFriendChatRecyclerView.visibility = View.GONE
		currentChatGroupId = null

		activity.lifecycleScope.launch {
			when(val result = friendsRepository.openConversation(friend.accountId))
			{
				is ConversationResult.Success -> {
					currentChatGroupId = result.groupId
					showChatMessages(result.messages)
				}
				is ConversationResult.Error -> {
					// Still capture the group id if the DM group itself was created fine and only
					// the history fetch failed — lets the user send even though history didn't load.
					currentChatGroupId = result.groupId
					panel.quickSettingsFriendChatProgressBar.visibility = View.GONE
					panel.quickSettingsFriendChatEmptyText.text = result.message
					panel.quickSettingsFriendChatEmptyText.visibility = View.VISIBLE
				}
			}
		}

		// Same reasoning as open()'s post{}: right after the group's visibility flips the new
		// content hasn't finished its first layout pass yet, so requestFocus() here can silently
		// lose to the platform's own default-focus pass a frame later without this.
		panel.quickSettingsFriendsChatGroup.post {
			panel.quickSettingsFriendChatBackButton.isFocusableInTouchMode = true
			panel.quickSettingsFriendChatBackButton.requestFocus()
		}
	}

	private fun showChatMessages(messages: List<ChatMessage>)
	{
		panel.quickSettingsFriendChatProgressBar.visibility = View.GONE

		if(messages.isEmpty())
		{
			panel.quickSettingsFriendChatEmptyText.text = activity.getString(R.string.friend_chat_empty_state)
			panel.quickSettingsFriendChatEmptyText.visibility = View.VISIBLE
			panel.quickSettingsFriendChatRecyclerView.visibility = View.GONE
			return
		}

		panel.quickSettingsFriendChatEmptyText.visibility = View.GONE
		chatMessageAdapter.items = messages
		panel.quickSettingsFriendChatRecyclerView.visibility = View.VISIBLE
		panel.quickSettingsFriendChatRecyclerView.scrollToPosition(messages.size - 1)
	}

	/** Re-fetches the open conversation on demand — same call the panel already makes right after
	 *  sending, just triggerable manually so the latest messages (e.g. a friend's reply) show up
	 *  without having to leave and re-enter the chat. */
	private fun refreshFriendChat()
	{
		val groupId = currentChatGroupId ?: return
		panel.quickSettingsFriendChatProgressBar.visibility = View.VISIBLE
		activity.lifecycleScope.launch {
			when(val result = friendsRepository.refreshConversation(groupId))
			{
				is ConversationResult.Success -> showChatMessages(result.messages)
				is ConversationResult.Error -> {
					panel.quickSettingsFriendChatProgressBar.visibility = View.GONE
					panel.quickSettingsFriendChatEmptyText.text = result.message
					panel.quickSettingsFriendChatEmptyText.visibility = View.VISIBLE
				}
			}
		}
	}

	private fun sendFriendChatMessage()
	{
		val text = panel.quickSettingsFriendChatInput.text?.toString()?.trim() ?: ""
		val groupId = currentChatGroupId
		if(text.isEmpty() || groupId == null) return

		panel.quickSettingsFriendChatInput.setText("")

		// Optimistic append — shows the sent message immediately rather than waiting on the
		// send + re-fetch round trip, matching how any messenger app behaves. Reconciled with
		// the server's own view once refreshConversation comes back below.
		showChatMessages(chatMessageAdapter.items + ChatMessage(text, "", isMine = true, timestampMs = System.currentTimeMillis()))

		activity.lifecycleScope.launch {
			friendsRepository.sendMessage(groupId, text)
			when(val result = friendsRepository.refreshConversation(groupId))
			{
				is ConversationResult.Success -> showChatMessages(result.messages)
				is ConversationResult.Error -> { /* keep the optimistic state on screen */ }
			}
		}
	}

	private fun backToFriendsList()
	{
		inFriendChat = false
		currentChatGroupId = null
		panel.quickSettingsFriendsChatGroup.visibility = View.GONE
		panel.quickSettingsFriendsListGroup.visibility = View.VISIBLE
		panel.quickSettingsFriendsListGroup.post {
			panel.quickSettingsFriendsRefreshButton.isFocusableInTouchMode = true
			panel.quickSettingsFriendsRefreshButton.requestFocus()
		}
	}

	/** Swaps the Friends tab's list sub-view for its inline trophy-comparison sub-view — a
	 *  sibling of the chat sub-view, not nested inside it, same "no separate Activity" reasoning. */
	private fun showTrophyCompare(friend: Friend)
	{
		inTrophyCompare = true
		currentCompareAccountId = friend.accountId
		panel.quickSettingsFriendsListGroup.visibility = View.GONE
		panel.quickSettingsTrophyCompareGroup.visibility = View.VISIBLE
		panel.quickSettingsTrophyCompareTitle.text = activity.getString(R.string.trophy_compare_title, friend.onlineId)

		loadTrophyCompare()

		panel.quickSettingsTrophyCompareGroup.post {
			panel.quickSettingsTrophyCompareBackButton.isFocusableInTouchMode = true
			panel.quickSettingsTrophyCompareBackButton.requestFocus()
		}
	}

	private fun loadTrophyCompare()
	{
		val accountId = currentCompareAccountId ?: return
		panel.quickSettingsTrophyCompareProgressBar.visibility = View.VISIBLE
		panel.quickSettingsTrophyCompareEmptyText.visibility = View.GONE
		panel.quickSettingsTrophyCompareContentGroup.visibility = View.GONE

		activity.lifecycleScope.launch {
			when (val result = trophyCompareRepository.fetchComparison(accountId))
			{
				is TrophyComparisonResult.Success -> {
					panel.quickSettingsTrophyCompareProgressBar.visibility = View.GONE
					panel.quickSettingsTrophyCompareSharedGamesLabel.text =
						activity.getString(R.string.trophy_compare_shared_games, result.sharedGames.size)

					if (result.sharedGames.isEmpty())
					{
						showTrophyCompareEmptyState(activity.getString(R.string.trophy_compare_empty))
					}
					else
					{
						val friend = friendAdapter.items.firstOrNull { it.accountId == accountId }
						panel.quickSettingsTrophyCompareHeader.bindTrophyCompareHeader(
							result, result.myAvatarUrl, theirName = friend?.onlineId ?: "", theirAvatarUrl = friend?.avatarUrl ?: ""
						)
						trophyCompareAdapter.items = result.sharedGames
						panel.quickSettingsTrophyCompareContentGroup.visibility = View.VISIBLE
					}
				}
				is TrophyComparisonResult.Error -> {
					panel.quickSettingsTrophyCompareProgressBar.visibility = View.GONE
					showTrophyCompareEmptyState(result.message)
				}
			}
		}
	}

	private fun showTrophyCompareEmptyState(message: String)
	{
		panel.quickSettingsTrophyCompareEmptyText.text = message
		panel.quickSettingsTrophyCompareEmptyText.visibility = View.VISIBLE
	}

	private fun backFromTrophyCompare()
	{
		inTrophyCompare = false
		currentCompareAccountId = null
		panel.quickSettingsTrophyCompareGroup.visibility = View.GONE
		panel.quickSettingsFriendsListGroup.visibility = View.VISIBLE
		panel.quickSettingsFriendsListGroup.post {
			panel.quickSettingsFriendsRefreshButton.isFocusableInTouchMode = true
			panel.quickSettingsFriendsRefreshButton.requestFocus()
		}
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
		remotePlayBaseline = currentRemotePlaySnapshot()
		pendingRemotePlaySettings = remotePlayBaseline

		addSectionLabel(container, R.string.preferences_category_title_stream)

		sessionRowControls += addDropdownRow(
			container, R.string.preferences_resolution_title,
			entries = Preferences.resolutionAll.map { activity.getString(it.title) },
			values = Preferences.resolutionAll.map { it.value },
			currentValue = preferences.resolution.value
		) { value ->
			Preferences.resolutionAll.firstOrNull { it.value == value }?.let { res ->
				pendingRemotePlaySettings = pendingRemotePlaySettings?.copy(resolution = res)
			}
			updateSessionApplyVisibility()
		}

		sessionRowControls += addDropdownRow(
			container, R.string.preferences_fps_title,
			entries = Preferences.fpsAll.map { activity.getString(it.title) },
			values = Preferences.fpsAll.map { it.value },
			currentValue = preferences.fps.value
		) { value ->
			Preferences.fpsAll.firstOrNull { it.value == value }?.let { fps ->
				pendingRemotePlaySettings = pendingRemotePlaySettings?.copy(fps = fps)
			}
			updateSessionApplyVisibility()
		}

		sessionRowControls += addEditTextRow(
			container, R.string.preferences_bitrate_title,
			hint = activity.getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto),
			currentValue = preferences.bitrate
		) { value ->
			pendingRemotePlaySettings = pendingRemotePlaySettings?.copy(bitrate = value)
			updateSessionApplyVisibility()
		}

		sessionRowControls += addDropdownRow(
			container, R.string.preferences_codec_title,
			entries = Preferences.codecAll.map { activity.getString(it.title) },
			values = Preferences.codecAll.map { it.value },
			currentValue = preferences.codec.value
		) { value ->
			Preferences.codecAll.firstOrNull { it.value == value }?.let { codec ->
				pendingRemotePlaySettings = pendingRemotePlaySettings?.copy(codec = codec)
			}
			updateSessionApplyVisibility()
		}
	}

	private fun buildCloudRows(container: LinearLayout, isLibrary: Boolean)
	{
		cloudBaseline = currentCloudSnapshot()
		pendingCloudSettings = cloudBaseline

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
		sessionRowControls += addDropdownRow(
			container,
			if(isLibrary) R.string.preferences_cloud_resolution_pscloud_title else R.string.preferences_cloud_resolution_psnow_title,
			resEntries, resValues, currentRes.toString()
		) { value ->
			val intValue = value.toIntOrNull() ?: return@addDropdownRow
			pendingCloudSettings = pendingCloudSettings?.copy(resolution = intValue)
			updateSessionApplyVisibility()
		}

		val (dcEntries, dcValues) = datacenterEntries(
			if(isLibrary) preferences.getCloudDatacentersJsonPscloud() else preferences.getCloudDatacentersJsonPsnow()
		)
		val currentDc = if(isLibrary) preferences.getCloudDatacenterPscloud() else preferences.getCloudDatacenterPsnow()
		sessionRowControls += addDropdownRow(
			container,
			if(isLibrary) R.string.preferences_cloud_datacenter_pscloud_title else R.string.preferences_cloud_datacenter_psnow_title,
			dcEntries, dcValues, currentDc
		) { value ->
			pendingCloudSettings = pendingCloudSettings?.copy(datacenter = value)
			updateSessionApplyVisibility()
		}

		val bitrateSummaryRes = if(isLibrary) R.string.preferences_cloud_bitrate_pscloud_summary else R.string.preferences_cloud_bitrate_psnow_summary
		val currentBitrateMbps = (if(isLibrary) preferences.getCloudBitratePscloud() else preferences.getCloudBitratePsnow()) / 1000
		sessionRowControls += addSeekBarRow(
			container, bitrateSummaryRes,
			min = 2, max = 200, currentValue = currentBitrateMbps
		) { valueMbps ->
			pendingCloudSettings = pendingCloudSettings?.copy(bitrateKbps = valueMbps * 1000)
			updateSessionApplyVisibility()
		}
	}

	/** Writes this tab's pending, not-yet-persisted edits into [preferences] — see
	 *  [pendingRemotePlaySettings]/[pendingCloudSettings]'s doc comment. Called only from
	 *  [applySessionSettings], right before the restart that reads these back out of
	 *  Preferences to build the new video profile / cloud allocation. */
	private fun commitPendingSessionSettings()
	{
		when(sessionType)
		{
			StreamSessionType.REMOTE_PLAY -> pendingRemotePlaySettings?.let { pending ->
				preferences.resolution = pending.resolution
				preferences.fps = pending.fps
				preferences.bitrate = pending.bitrate
				preferences.codec = pending.codec
			}
			StreamSessionType.CATALOG_PSNOW, StreamSessionType.LIBRARY_PSCLOUD -> pendingCloudSettings?.let { pending ->
				if(sessionType == StreamSessionType.LIBRARY_PSCLOUD)
				{
					preferences.setCloudResolutionPscloud(pending.resolution)
					preferences.setCloudDatacenterPscloud(pending.datacenter)
					preferences.setCloudBitratePscloud(pending.bitrateKbps)
				}
				else
				{
					preferences.setCloudResolutionPsnow(pending.resolution)
					preferences.setCloudDatacenterPsnow(pending.datacenter)
					preferences.setCloudBitratePsnow(pending.bitrateKbps)
				}
			}
		}
	}

	private fun currentRemotePlaySnapshot() = RemotePlaySettingsSnapshot(
		resolution = preferences.resolution,
		fps = preferences.fps,
		bitrate = preferences.bitrate,
		codec = preferences.codec
	)

	/** Reads whichever of the Catalog (PSNow)/Library (PSCloud) preference keys applies to this
	 *  session — safe to call for either [sessionType], since it's only ever compared against
	 *  [cloudBaseline], which is equally session-type-specific. */
	private fun currentCloudSnapshot(): CloudSettingsSnapshot
	{
		val isLibrary = sessionType == StreamSessionType.LIBRARY_PSCLOUD
		return CloudSettingsSnapshot(
			resolution = if(isLibrary) preferences.getCloudResolutionPscloud() else preferences.getCloudResolutionPsnow(),
			datacenter = if(isLibrary) preferences.getCloudDatacenterPscloud() else preferences.getCloudDatacenterPsnow(),
			bitrateKbps = if(isLibrary) preferences.getCloudBitratePscloud() else preferences.getCloudBitratePsnow()
		)
	}

	private val isSessionRestarting: Boolean get() =
		viewModel.sessionRestartState.value is SessionRestartState.InProgress ||
		viewModel.session.state.value is StreamStateConnecting

	private fun applySessionSettings()
	{
		if(isSessionRestarting) return
		commitPendingSessionSettings()
		pendingSessionRestart = true
		updateSessionApplyVisibility()
		when(sessionType)
		{
			StreamSessionType.REMOTE_PLAY -> viewModel.restartRemotePlaySession()
			StreamSessionType.CATALOG_PSNOW, StreamSessionType.LIBRARY_PSCLOUD -> viewModel.restartCloudSession()
		}
	}

	/** Single source of truth for the Session tab's Apply bar — called after every row edit and
	 *  from both the [StreamViewModel.sessionRestartState] and [StreamSession.state] observers,
	 *  since any of those can flip whether there's a pending change or a restart in flight. Also
	 *  called from [showTab] on every tab switch: the bar is a layout sibling of the Session
	 *  ScrollView pinned to the bottom of the whole panel (not a child of it), so nothing about
	 *  switching tabs would otherwise touch its visibility — a change left pending on the Session
	 *  tab would keep the Apply bar showing over Trophies, Friends, etc. instead of only where
	 *  the setting it applies to actually lives. */
	private fun updateSessionApplyVisibility()
	{
		val dirty = when(sessionType)
		{
			StreamSessionType.REMOTE_PLAY -> remotePlayBaseline != null && remotePlayBaseline != pendingRemotePlaySettings
			StreamSessionType.CATALOG_PSNOW, StreamSessionType.LIBRARY_PSCLOUD ->
				cloudBaseline != null && cloudBaseline != pendingCloudSettings
		}
		val restarting = isSessionRestarting
		val sessionTabActive = panel.quickSettingsTabToggle.checkedButtonId == R.id.quickSettingsTabSession

		panel.quickSettingsSessionApplyBar.visibility = if((dirty || restarting) && sessionTabActive) View.VISIBLE else View.GONE
		panel.quickSettingsSessionApplyButton.visibility = if(restarting) View.GONE else View.VISIBLE
		panel.quickSettingsSessionApplyStatusText.visibility = if(restarting) View.VISIBLE else View.GONE
		if(restarting)
		{
			panel.quickSettingsSessionApplyStatusText.text =
				(viewModel.sessionRestartState.value as? SessionRestartState.InProgress)?.message
					?: activity.getString(R.string.quick_settings_session_restarting)
		}
		setSessionRowsEnabled(!restarting)
	}

	private fun setSessionRowsEnabled(enabled: Boolean)
	{
		sessionRowControls.forEach {
			it.isEnabled = enabled
			it.alpha = if(enabled) 1f else 0.5f
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
	): View
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
		return row.quickSettingsDropdownSpinner
	}

	private fun updateCasSeekBarLabel(value: Int)
	{
		panel.quickSettingsCasSeekBarRow.quickSettingsSeekBarLabel.text =
			activity.getString(R.string.quick_settings_cas_sharpening_level, value)
	}

	private fun addSeekBarRow(
		container: LinearLayout,
		summaryRes: Int,
		min: Int,
		max: Int,
		currentValue: Int,
		onChanged: (Int) -> Unit
	): View
	{
		val row = ItemQuickSettingsSeekbarBinding.inflate(activity.layoutInflater, container, true)
		fun updateLabel(value: Int) { row.quickSettingsSeekBarLabel.text = activity.getString(summaryRes, value) }
		row.quickSettingsSeekBar.max = max - min
		// Without this, D-pad left/right steps by SeekBar's auto-computed default increment
		// (roughly max/20 — 10 here) instead of 1, since keyProgressIncrement is never set
		// explicitly otherwise.
		row.quickSettingsSeekBar.keyProgressIncrement = 1
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
		return row.quickSettingsSeekBar
	}

	private fun addEditTextRow(
		container: LinearLayout,
		labelRes: Int,
		hint: String,
		currentValue: Int?,
		onChanged: (Int?) -> Unit
	): View
	{
		val row = ItemQuickSettingsEdittextBinding.inflate(activity.layoutInflater, container, true)
		row.quickSettingsEditTextLabel.text = activity.getString(labelRes)
		row.quickSettingsEditText.hint = hint
		row.quickSettingsEditText.setText(currentValue?.toString() ?: "")
		row.quickSettingsEditText.doAfterTextChanged { text -> onChanged(text?.toString()?.toIntOrNull()) }
		addFocusHighlight(row.quickSettingsEditText, pyluxAccentColor)
		return row.quickSettingsEditText
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

		panel.quickSettingsCasRow.quickSettingsRowSwitch.isChecked = preferences.casSharpeningEnabled
		panel.quickSettingsCasSeekBarRow.root.visibility = if(preferences.casSharpeningEnabled) View.VISIBLE else View.GONE
		panel.quickSettingsCasSeekBarRow.quickSettingsSeekBar.progress = preferences.casSharpeningLevel - Preferences.CAS_SHARPENING_LEVEL_MIN
		updateCasSeekBarLabel(preferences.casSharpeningLevel)

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

	/** Remote Play only — Cloud Play's power icon keeps its original single-tap disconnect
	 *  unchanged: "put to sleep" isn't meaningful there, since Cloud Play streams a cloud-hosted
	 *  instance rather than a physical console the user owns. For Remote Play, offers a choice
	 *  between putting the physical console to sleep first
	 *  ([com.metallic.chiaki.session.StreamSession.requestConsoleSleep]) or just disconnecting —
	 *  Sony's protocol has no distinct "power off" command, only rest mode (see that function's
	 *  own doc comment for why). Uses the app-wide dialog styling like every other dialog in the
	 *  app, and stays cancelable (back button / tap outside) by default so an accidental tap on
	 *  the power icon doesn't force picking one of two disconnecting outcomes. */
	private fun showDisconnectOptions()
	{
		if(sessionType != StreamSessionType.REMOTE_PLAY)
		{
			dismissImmediately()
			activity.finish()
			return
		}
		activity.alertDialogBuilder()
			.setMessage(R.string.alert_message_console_power_options)
			.setPositiveButton(R.string.action_console_sleep) { _, _ ->
				viewModel.session.requestConsoleSleep()
				dismissImmediately()
				activity.finish()
			}
			.setNegativeButton(R.string.action_disconnect_session) { _, _ ->
				dismissImmediately()
				activity.finish()
			}
			.show()
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
		view.disableDefaultFocusHighlight()
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
	 *  rail, close and disconnect buttons are all temporarily excluded from focus search so
	 *  D-pad navigation inside the content can't wander onto them (e.g. off the bottom of a
	 *  scrolled list); only exitToRailScope() (Circle/Back) returns. Un-blocks the content
	 *  container's own descendant focusability first — exitToRailScope()/showTab() block it
	 *  while in rail scope (see their own comments), and addFocusables()/requestFocus() below
	 *  would silently find nothing while that's still in effect. */
	private fun enterContentScope()
	{
		val container = currentTabContentContainer() ?: return
		container.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
		val focusables = ArrayList<View>()
		container.addFocusables(focusables, View.FOCUS_DOWN)
		// A ScrollView (the Session/General tabs' container) is focusable by itself by default —
		// purely so D-pad/trackball scrolling still works when nothing inside it has focus — and
		// with descendantFocusability just set to FOCUS_BEFORE_DESCENDANTS above,
		// addFocusables() lists that self-focusability before its children's, so
		// firstOrNull() picked the whole scroll container instead of its first real row
		// (confirmed on-device). Skip the container itself; fall back to it only if the tab
		// genuinely has no focusable rows at all, so D-pad scrolling still works in that case.
		val target = focusables.firstOrNull { it !== container } ?: focusables.firstOrNull() ?: return
		panel.quickSettingsTabToggle.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
		panel.quickSettingsCloseButton.isFocusable = false
		panel.quickSettingsDisconnectButton.isFocusable = false
		inTabContent = true
		target.requestFocus()
	}

	/** Blocks the current tab's content container from focus search while in rail scope — without
	 *  this, D-pad down from the last rail button (Friends) doesn't land on the Disconnect button
	 *  below the rail as expected: Android's focus search is geometric, not scoped to siblings,
	 *  and the Session/Trophies/Friends tabs' own content sits to the right of and taller than the
	 *  rail, so it can end up the nearer match and steal focus back into content that was never
	 *  actually entered (confirmed on-device). enterContentScope() is what lifts this again. */
	private fun exitToRailScope()
	{
		// Always land back on the friends list, never mid-conversation/comparison, next time this
		// tab is reopened or drilled back into.
		if(inFriendChat) backToFriendsList()
		if(inTrophyCompare) backFromTrophyCompare()

		currentTabContentContainer()?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
		panel.quickSettingsTabToggle.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
		panel.quickSettingsCloseButton.isFocusable = true
		panel.quickSettingsDisconnectButton.isFocusable = true
		inTabContent = false
		panel.quickSettingsTabToggle.findViewById<View>(panel.quickSettingsTabToggle.checkedButtonId)
			?.requestFocus()
	}

	private fun currentTabContentContainer(): ViewGroup? = when(panel.quickSettingsTabToggle.checkedButtonId)
	{
		R.id.quickSettingsTabController -> panel.quickSettingsControllerSection
		R.id.quickSettingsTabGeneral -> panel.quickSettingsGeneralScroll
		R.id.quickSettingsTabSession -> panel.quickSettingsSessionScroll
		R.id.quickSettingsTabTrophies -> panel.quickSettingsTrophiesSection
		R.id.quickSettingsTabFriends -> panel.quickSettingsFriendsSection
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
