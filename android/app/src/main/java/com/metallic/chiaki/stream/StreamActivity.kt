// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import androidx.appcompat.app.AlertDialog
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.metallic.chiaki.common.ext.isTv
import android.Manifest
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Matrix
import android.os.*
import android.util.Log
import android.util.Rational
import android.view.*
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.lifecycle.lifecycleScope

import com.pylux.stream.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.pylux.stream.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.StreamStateConnected
import com.metallic.chiaki.session.StreamStateConnecting
import com.metallic.chiaki.session.StreamStateCreateError
import com.metallic.chiaki.session.StreamStateIdle
import com.metallic.chiaki.session.StreamStateLoginPinRequest
import com.metallic.chiaki.session.StreamStateQuit
import com.metallic.chiaki.session.StreamState
import com.metallic.chiaki.trophy.TrophyRepository
import com.metallic.chiaki.trophy.TrophyUnlockWatcher
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()
private object SessionRestartFailedDialog: DialogContents()

class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		/** Cover art URL for the Quick Settings panel's "Current game" header — only ever set
		 *  by cloud-streaming launch paths (CloudPlayFragment); absent for Remote Play, where
		 *  that header is hidden entirely. */
		const val EXTRA_GAME_IMAGE_URL = "game_image_url"
		private const val HIDE_UI_TIMEOUT_MS = 4000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var quickSettingsPanel: QuickSettingsPanel
	private lateinit var trophyUnlockPopupPresenter: TrophyUnlockPopupPresenter

	/** Only created for cloud sessions (Catalog/Library), which are the only ones with a known
	 *  game name/platform to match trophies against — null for Remote Play. */
	private var trophyUnlockWatcher: TrophyUnlockWatcher? = null

	/** Result callback for the most recent [micPermissionLauncher] request, invoked with the
	 *  grant result then cleared. Must be registered before STARTED, hence a class field. */
	private var pendingMicPermissionCallback: ((Boolean) -> Unit)? = null
	private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		pendingMicPermissionCallback?.invoke(granted)
		pendingMicPermissionCallback = null
	}

	private val uiVisibilityHandler = Handler()

	/** Tracks whether the activity is in the stopped state (between onStop and onStart).
	 *  Used to detect PiP dismissal: onStop fires while pip=true (so cleanup is skipped),
	 *  then onPictureInPictureModeChanged(false) fires — at that point we check this
	 *  flag to know we need to shut down the session. */
	private var activityStopped = false

	/** Saved control state before entering PiP, so we can restore when exiting PiP */
	private var savedOnScreenControlsEnabled = false
	private var savedTouchpadOnlyEnabled = false

	/** [SystemClock.elapsedRealtime] when this session entered [StreamStateConnected]; 0 if not connected. */
	private var connectedAtElapsedRealtime: Long = 0L

	/** Wall-clock counterpart of [connectedAtElapsedRealtime], used as the "last played" timestamp
	 *  recorded against the game once the segment is flushed (elapsedRealtime is boot-relative and
	 *  not meaningful to persist/display). 0 if not connected. */
	private var connectedAtWallClockMs: Long = 0L

	/** True once this session has reached [StreamStateConnected] at least once. Unlike
	 *  [connectedAtElapsedRealtime] (which flushStreamTimeSegment resets back to 0 on every
	 *  disconnect, including the very one this flag needs to be checked against), this is never
	 *  reset — it exists purely to gate [autoRetriedFirstConnect] below to the console's very
	 *  first connection attempt for this Activity's lifetime. */
	private var everConnected = false

	/** Confirmed on-device: right after a console finishes waking (either from rest mode or a
	 *  cold power-on), its control connection can come up and respond to heartbeats successfully
	 *  while its actual AV/Takion streaming listener still refuses connections for a bit longer —
	 *  even well past the point our own host-list "Ready" state (see MainViewModel.confirmConsoleOn)
	 *  already waited out. The first real connection attempt against a console in this state
	 *  reliably fails with CHIAKI_QUIT_REASON_STREAM_CONNECTION_UNKNOWN ("Unknown Error in Stream
	 *  Connection"); a second attempt moments later, once that listener has caught up, reliably
	 *  succeeds. This silently retries once instead of surfacing that first failure to the user as
	 *  a "Session has quit" dialog — set true the moment that retry fires, so a second genuine
	 *  failure (the console really isn't reachable) still shows the normal dialog rather than
	 *  retrying forever. Only applies before the first successful connect ([everConnected]); a
	 *  later mid-stream disconnect always shows the dialog immediately, since auto-reconnecting
	 *  there could silently mask a real problem instead of just working around this one quirk. */
	private var autoRetriedFirstConnect = false

	/** Currently-applied window size / display mode. Only changes when the Quick Settings
	 *  panel's Save button is pressed — the panel's own toggle group is staged separately. */
	private var currentDisplayMode: TransformMode = TransformMode.FIT

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getStreamThemeStyleRes())
		super.onCreate(savedInstanceState)

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			finish()
			return
		}
		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		trophyUnlockPopupPresenter = TrophyUnlockPopupPresenter(
			container = binding.trophyUnlockPopup,
			iconView = binding.trophyUnlockPopupIcon,
			textView = binding.trophyUnlockPopupText,
			detailView = binding.trophyUnlockPopupDetail,
			badgeView = binding.trophyUnlockPopupBadge
		)

		val cloudGameName = connectInfo.cloudGameName
		val cloudGamePlatform = connectInfo.cloudGamePlatform
		if (!cloudGameName.isNullOrBlank() && !cloudGamePlatform.isNullOrBlank())
		{
			trophyUnlockWatcher = TrophyUnlockWatcher(
				trophyRepository = TrophyRepository(viewModel.preferences),
				gameName = cloudGameName,
				platform = cloudGamePlatform,
				onTrophiesUnlocked = { trophies -> trophyUnlockPopupPresenter.enqueue(trophies) }
			)
		}

		// Quick Settings panel — replaces the old bottom overlay bar entirely. Disconnect,
		// Performance Overlay, On-Screen Controls, Touchpad Only and Window Size all live
		// here now; pressing back opens/closes it. There's no Save button — every control
		// applies immediately. Motion/Touch Haptics/PiP/Remap Controller behave as before.
		quickSettingsPanel = QuickSettingsPanel(
			activity = this,
			preferences = viewModel.preferences,
			streamInput = viewModel.input,
			viewModel = viewModel,
			gameImageUrl = intent.getStringExtra(EXTRA_GAME_IMAGE_URL) ?: "",
			getDisplayMode = { currentDisplayMode },
			onDisplayModeChanged = { mode ->
				currentDisplayMode = mode
				adjustStreamViewAspect()
			},
			requestMicPermission = { onResult ->
				pendingMicPermissionCallback = onResult
				micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
			},
			onCasSharpeningChanged = { enabled, level -> binding.surfaceView.setSharpening(enabled, level) }
		)

		// Handle back button — on TV show a disconnect confirmation dialog; on touch,
		// toggle the Quick Settings panel (open if closed, discard-and-close if open).
		onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				Log.i("StreamActivity", "handleOnBackPressed: isTv=${isTv()}")
				if (isTv()) {
					alertDialogBuilder()
						.setMessage("Disconnect from stream?")
						.setPositiveButton("Disconnect") { _, _ -> finish() }
						.setNegativeButton("Cancel", null)
						.show()
				} else {
					quickSettingsPanel.toggle()
				}
			}
		})

		//viewModel.session.attachToTextureView(textureView)
		val videoProfile = connectInfo.videoProfile
		binding.surfaceView.setVideoSize(videoProfile.width, videoProfile.height)
		binding.surfaceView.setSharpening(prefs.casSharpeningEnabled, prefs.casSharpeningLevel)
		viewModel.session.attachToCasSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		// Cloud Play's re-allocation phase of an "Apply" restart (see suppressQuitDialogForRestart)
		// has no StreamState of its own — the old session can keep reporting StreamStateConnected
		// right up until the server kills it — so the loading spinner needs its own trigger here
		// rather than relying solely on stateChanged's StreamStateConnecting case. A Failed
		// outcome gets its own dialog here too: by the time re-allocation fails, the old cloud
		// session has near-certainly already been killed server-side as a side effect of locking
		// the new one (see suppressQuitDialogForRestart's doc comment), so the ordinary Quit
		// dialog that would normally tell the user their stream ended never fires — it was
		// suppressed while this was still InProgress, and no further StreamState change follows
		// to un-suppress it. This is the user's only way back to a live stream in that case.
		viewModel.sessionRestartState.observe(this, Observer { state ->
			updateProgressBarVisibility()
			if(state is SessionRestartState.Failed && dialogContents != SessionRestartFailedDialog)
			{
				dialog?.dismiss()
				val dialog = alertDialogBuilder()
					.setMessage(getString(R.string.alert_message_session_restart_failed, state.message))
					.setPositiveButton(R.string.action_reconnect) { _, _ ->
						dialog = null
						dialogContents = null
						viewModel.acknowledgeSessionRestartFailure()
						viewModel.restartCloudSession()
					}
					.setOnCancelListener {
						dialog = null
						viewModel.acknowledgeSessionRestartFailure()
						finish()
					}
					.setNegativeButton(R.string.action_quit_session) { _, _ ->
						dialog = null
						viewModel.acknowledgeSessionRestartFailure()
						finish()
					}
					.create()
				dialogContents = SessionRestartFailedDialog
				dialog.show()
			}
		})

		viewModel.showPerformanceOverlay.observe(this, Observer { show ->
			binding.performanceOverlay.isVisible = show
		})

		viewModel.overlayData.observe(this, Observer { data ->
			if (binding.performanceOverlay.isVisible) {
				binding.performanceOverlay.updateOverlay(data)
			}
		})

		// On TV, the Quick Settings panel is simply never shown — the back-press handler's
		// isTv() branch below never calls quickSettingsPanel.toggle()/open().

	}

	private val controlsDisposable = CompositeDisposable()

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			if (isTv()) {
				// Force controls hidden on TV by giving the fragment a LiveData that always emits false
				fragment.onScreenControlsEnabled = androidx.lifecycle.MutableLiveData(false)
				return
			}
			fragment.controllerState
				.subscribe { viewModel.input.touchControllerState = it }
				.addTo(controlsDisposable)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
		}
	}

	override fun onResume() {
		super.onResume()
		activityStopped = false
		Log.i("StreamActivity", "onResume: pip=$isInPictureInPictureMode session=${viewModel.session.session != null}")
		hideSystemUI()

		// Paired with viewModel.session.resume() — the GL render thread should be alive
		// exactly when the decoder is (see CasVideoSurfaceView's own doc comment).
		binding.surfaceView.onResume()
		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()
		Log.i("StreamActivity", "onPause: pip=$isInPictureInPictureMode finishing=$isFinishing")
		if (!isInPictureInPictureMode)
		{
			viewModel.session.skipNativeSurfaceCleanup = false
			binding.surfaceView.onPause()
			viewModel.session.pause()
		}
	}

	override fun onStop()
	{
		super.onStop()
		activityStopped = true
		Log.i("StreamActivity", "onStop: pip=$isInPictureInPictureMode finishing=$isFinishing")
		if (!isInPictureInPictureMode)
		{
			viewModel.session.skipNativeSurfaceCleanup = false
			binding.surfaceView.onPause()
			viewModel.session.pause()
		}
	}

	override fun onDestroy()
	{
		super.onDestroy()
		Log.i("StreamActivity", "onDestroy: finishing=$isFinishing")
		flushStreamTimeSegment()
		controlsDisposable.dispose()
		uiVisibilityHandler.removeCallbacksAndMessages(null)
	}

	override fun onConfigurationChanged(newConfig: Configuration)
	{
		super.onConfigurationChanged(newConfig)
		Log.i("StreamActivity", "onConfigurationChanged: pip=$isInPictureInPictureMode")
	}

	// --- Picture-in-Picture support ---

	override fun onUserLeaveHint()
	{
		super.onUserLeaveHint()
		Log.i("StreamActivity", "onUserLeaveHint")
		enterPipModeIfEnabled()
	}

	private fun enterPipModeIfEnabled()
	{
		if (isTv()) return

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
		{
			Log.i("StreamActivity", "PiP: not supported (API ${Build.VERSION.SDK_INT})")
			return
		}

		if (!Preferences(this).pipEnabled)
		{
			Log.i("StreamActivity", "PiP: disabled in preferences")
			return
		}

		try {
			viewModel.session.skipNativeSurfaceCleanup = true
			val result = enterPictureInPictureMode(
				PictureInPictureParams.Builder()
					.setAspectRatio(Rational(16, 9))
					.build()
			)
			Log.i("StreamActivity", "PiP: enterPictureInPictureMode returned $result")
			if (!result) {
				viewModel.session.skipNativeSurfaceCleanup = false
			}
		} catch (e: Exception) {
			Log.w("StreamActivity", "PiP: failed to enter - ${e.message}")
			viewModel.session.skipNativeSurfaceCleanup = false
		}
	}

	@Suppress("DEPRECATION")
	override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean)
	{
		super.onPictureInPictureModeChanged(isInPictureInPictureMode)
		Log.i("StreamActivity", "onPipChanged(1-param): pip=$isInPictureInPictureMode finishing=$isFinishing")
		handlePipChanged(isInPictureInPictureMode)
	}

	override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration)
	{
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
		Log.i("StreamActivity", "onPipChanged(2-param): pip=$isInPictureInPictureMode finishing=$isFinishing")
	}

	private fun handlePipChanged(isInPictureInPictureMode: Boolean)
	{
		if (isInPictureInPictureMode)
		{
			savedOnScreenControlsEnabled = viewModel.onScreenControlsEnabled.value ?: false
			savedTouchpadOnlyEnabled = viewModel.touchpadOnlyEnabled.value ?: false

			quickSettingsPanel.close()
			viewModel.setOnScreenControlsEnabled(false)
			viewModel.setTouchpadOnlyEnabled(false)
			binding.progressBar.isGone = true
		}
		else
		{
			viewModel.session.skipNativeSurfaceCleanup = false

			if (activityStopped)
			{
				Log.i("StreamActivity", "handlePipChanged: PiP dismissed while stopped, shutting down session")
				binding.surfaceView.onPause()
				viewModel.session.pause()
			}
			else if (!isFinishing)
			{
				viewModel.setOnScreenControlsEnabled(savedOnScreenControlsEnabled)
				viewModel.setTouchpadOnlyEnabled(savedTouchpadOnlyEnabled)
				hideSystemUI()
			}
		}
	}

	// --- end PiP ---

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	override fun onSystemUiVisibilityChange(visibility: Int)
	{
		// If the system bars become visible (e.g. an edge swipe in immersive mode),
		// re-hide them again after a short delay.
		if(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
		{
			uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
			uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
		}
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
				or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_FULLSCREEN)
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun flushStreamTimeSegment()
	{
		trophyUnlockWatcher?.stop()
		trophyUnlockPopupPresenter.cancel()

		if (connectedAtElapsedRealtime == 0L) return
		val delta = SystemClock.elapsedRealtime() - connectedAtElapsedRealtime
		if (delta > 0L)
		{
			viewModel.preferences.addTotalStreamTimeMs(delta)
			viewModel.connectInfo.cloudGameProductId?.let { productId ->
				viewModel.preferences.recordPlaySession(productId, delta, connectedAtWallClockMs)
			}
		}
		connectedAtElapsedRealtime = 0L
		connectedAtWallClockMs = 0L
	}

	/** True while an "Apply" restart (see QuickSettingsPanel) is between tearing down the old
	 *  session and handing a new ConnectInfo to StreamSession — covers two different but equally
	 *  self-inflicted causes of a stray Quit/CreateError on the *old* session that the user
	 *  should never see a dialog for:
	 *   - Cloud Play: StreamViewModel.restartCloudSession's Gaikai re-allocation goes through an
	 *     account-level session lock that forces the *old* cloud session closed server-side as a
	 *     side effect (confirmed on-device: arrives as a Quit with reason "Remote has
	 *     disconnected .../Shutdown requested by server", then a string of "Unknown Error" quits
	 *     from stray reconnect attempts against the now-dead old session) — well before the new
	 *     session is actually ready.
	 *   - Remote Play: StreamSession.restartWithNewConnectInfo's own shutdown() of the old
	 *     session synchronously fires a QuitEvent(reason=Stopped) on it (confirmed on-device).
	 *  Without this check, stateChanged's ordinary Quit/CreateError handling below would show the
	 *  ordinary ended-session dialog — or, for a non-error reason like Stopped, silently call
	 *  finish() and tear down the whole Activity — for what is, from the user's perspective, just
	 *  a moment of the restart they already asked for. The real outcome (success or failure)
	 *  still shows normally once the restart hands off to the new session and this goes back to
	 *  false. */
	private val suppressQuitDialogForRestart: Boolean get() =
		viewModel.sessionRestartState.value is SessionRestartState.InProgress

	private fun updateProgressBarVisibility(state: StreamState = viewModel.session.state.value ?: StreamStateIdle)
	{
		binding.progressBar.visibility =
			if(state == StreamStateConnecting || viewModel.sessionRestartState.value is SessionRestartState.InProgress)
				View.VISIBLE
			else
				View.GONE
	}

	private fun stateChanged(state: StreamState)
	{
		Log.i("StreamActivity", "stateChanged: $state pip=$isInPictureInPictureMode")
		updateProgressBarVisibility(state)

		when(state)
		{
			StreamStateConnected ->
			{
				everConnected = true
				if (connectedAtElapsedRealtime == 0L)
				{
					connectedAtElapsedRealtime = SystemClock.elapsedRealtime()
					connectedAtWallClockMs = System.currentTimeMillis()
				}
				trophyUnlockWatcher?.start(lifecycleScope)

				// Re-applied on every connect, not just the first — a Quick Settings "Apply"
				// restart (see QuickSettingsPanel) can hand StreamSession a new videoProfile, and
				// this is the only place that profile drives the surface/sharpening shader's
				// notion of decode resolution. Idempotent when nothing changed.
				val videoProfile = viewModel.session.connectInfo.videoProfile
				binding.surfaceView.setVideoSize(videoProfile.width, videoProfile.height)
				adjustStreamViewAspect()
			}

			StreamStateConnecting ->
			{
			}

			StreamStateIdle ->
			{
				flushStreamTimeSegment()
			}

			is StreamStateQuit ->
			{
				flushStreamTimeSegment()
				if(!everConnected && !autoRetriedFirstConnect && !suppressQuitDialogForRestart &&
					state.reason.toString() == "Unknown Error in Stream Connection")
				{
					Log.i("StreamActivity", "First connection attempt failed with the known post-wake Takion-not-ready quirk — silently retrying once")
					autoRetriedFirstConnect = true
					reconnect()
				}
				else if(dialogContents != StreamQuitDialog && !suppressQuitDialogForRestart)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = alertDialogBuilder()
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				flushStreamTimeSegment()
				if(dialogContents != CreateErrorDialog && !suppressQuitDialogForRestart)
				{
					dialog?.dismiss()
					val dialog = alertDialogBuilder()
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				flushStreamTimeSegment()
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = alertDialogBuilder()
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(currentDisplayMode)
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = currentDisplayMode
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(quickSettingsPanel.isCapturingInput && quickSettingsPanel.handleCaptureKeyEvent(event))
			return true
		return viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(quickSettingsPanel.isCapturingInput && quickSettingsPanel.handleCaptureMotionEvent(event))
			return true
		return viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
	}
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.quickSettingsDisplayModeStretch -> STRETCH
				R.id.quickSettingsDisplayModeZoom -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
