// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import androidx.appcompat.app.AlertDialog
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.metallic.chiaki.common.ext.isTv
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Matrix
import android.os.*
import android.util.Log
import android.util.Rational
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*

import com.pylux.stream.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.pylux.stream.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.StreamSessionType
import com.metallic.chiaki.lib.sessionType
import com.metallic.chiaki.session.StreamStateConnected
import com.metallic.chiaki.session.StreamStateConnecting
import com.metallic.chiaki.session.StreamStateCreateError
import com.metallic.chiaki.session.StreamStateIdle
import com.metallic.chiaki.session.StreamStateLoginPinRequest
import com.metallic.chiaki.session.StreamStateQuit
import com.metallic.chiaki.session.StreamState
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		/** Set by [relaunch] when it's replacing this Activity as part of a Quick Settings
		 *  Refresh, so the new instance knows to keep showing [ActivityStreamBinding.refreshOverlay]
		 *  (rather than the plain [ActivityStreamBinding.progressBar]) through its own connect
		 *  phase, for a continuous "refreshing" message across the Activity swap. */
		private const val EXTRA_IS_REFRESH = "is_refresh"
		/** How many times [relaunch] has already retried this refresh after the console reported
		 *  itself still in use (see [QuitReason.isConsoleInUse]) — carried across each retry's
		 *  Activity swap so the count keeps climbing instead of resetting. 0 on the first
		 *  attempt. */
		private const val EXTRA_REFRESH_RETRY_COUNT = "refresh_retry_count"
		private const val HIDE_UI_TIMEOUT_MS = 4000L
		/** Base grace period before (re)connecting a refreshed Remote Play session, scaled up by
		 *  attempt number. The console can still report "already in use" for a while after our
		 *  own teardown completes — our side closing the connection isn't the same instant the
		 *  console's own session slot is freed, and that window isn't fixed-length, so a single
		 *  short delay isn't reliable. Cloud sessions don't need this: reallocating goes through
		 *  a fresh server-side allocation rather than reconnecting to the same physical console. */
		private const val REMOTE_PLAY_REFRESH_RECONNECT_DELAY_MS = 3000L
		/** Automatic retries after an "already in use" quit on a refreshed Remote Play
		 *  connection, before giving up and showing the normal quit-reason dialog. */
		private const val MAX_REFRESH_RETRIES = 3
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var quickSettingsPanel: QuickSettingsPanel

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

	/** Currently-applied window size / display mode. Only changes when the Quick Settings
	 *  panel's Save button is pressed — the panel's own toggle group is staged separately. */
	private var currentDisplayMode: TransformMode = TransformMode.FIT

	/** True when this Activity instance was started by [relaunch] as part of a Quick Settings
	 *  Refresh (see [EXTRA_IS_REFRESH]). Drives [stateChanged] to keep showing
	 *  [ActivityStreamBinding.refreshOverlay] through this Activity's own connect phase, instead
	 *  of the plain [ActivityStreamBinding.progressBar], until [StreamStateConnected]. */
	private var isRefreshLaunch = false

	/** See [EXTRA_REFRESH_RETRY_COUNT]. */
	private var refreshRetryCount = 0

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			finish()
			return
		}
		isRefreshLaunch = intent.getBooleanExtra(EXTRA_IS_REFRESH, false)
		refreshRetryCount = intent.getIntExtra(EXTRA_REFRESH_RETRY_COUNT, 0)

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		// Quick Settings panel — replaces the old bottom overlay bar entirely. Disconnect,
		// Performance Overlay, On-Screen Controls, Touchpad Only and Window Size all live
		// here now; pressing back opens/closes it. There's no Save button — every control
		// applies immediately. Motion/Touch Haptics/PiP/Remap Controller behave as before.
		quickSettingsPanel = QuickSettingsPanel(
			activity = this,
			preferences = viewModel.preferences,
			streamInput = viewModel.input,
			viewModel = viewModel,
			getDisplayMode = { currentDisplayMode },
			onDisplayModeChanged = { mode ->
				currentDisplayMode = mode
				adjustStreamViewAspect()
			},
			onRefreshRequested = { performRefresh() }
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
		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

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

		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()
		Log.i("StreamActivity", "onPause: pip=$isInPictureInPictureMode finishing=$isFinishing")
		if (!isInPictureInPictureMode)
		{
			viewModel.session.skipNativeSurfaceCleanup = false
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

	/** True while a Quick Settings Refresh is in flight. [Session.stop] (called by
	 *  [StreamSession.shutdown] below) makes the native session quit with a non-error reason,
	 *  same as any other clean disconnect — which [stateChanged]'s [StreamStateQuit] handling
	 *  would otherwise treat as "the user disconnected" and immediately [finish] the Activity,
	 *  racing (and always winning against) this function's own async relaunch below. Checked in
	 *  [stateChanged] to suppress that so Refresh actually gets to relaunch instead of just
	 *  closing the stream and dropping back to whatever launched it. */
	private var isRefreshing = false

	/** Called from the Quick Settings panel's Refresh action. Fully closes the current session
	 *  first and waits for that teardown to actually complete — [StreamSession.shutdown]'s
	 *  [Session.stop] call alone only signals the native session to stop; the console/cloud
	 *  backend doesn't see the disconnect until the background join finishes. Only once that's
	 *  done do we reconnect (Remote Play, just needs a fresh video profile) or reallocate
	 *  (Catalog/Library, need a brand new cloud allocation for resolution/bitrate/datacenter
	 *  changes to take effect, since those are baked in at allocation time) — otherwise the new
	 *  session can race the old one still holding its server-side slot open. Either way, success
	 *  replaces this whole Activity (and with it, the panel) with a genuinely new session rather
	 *  than trying to mutate the live one in place. */
	private fun performRefresh()
	{
		isRefreshing = true
		binding.refreshOverlay.isVisible = true
		viewModel.session.shutdown {
			when(viewModel.connectInfo.sessionType)
			{
				StreamSessionType.REMOTE_PLAY ->
					Handler(Looper.getMainLooper()).postDelayed(
						{ relaunch(viewModel.refreshedRemotePlayConnectInfo()) },
						REMOTE_PLAY_REFRESH_RECONNECT_DELAY_MS
					)
				StreamSessionType.CATALOG_PSNOW, StreamSessionType.LIBRARY_PSCLOUD ->
				{
					viewModel.refreshCloudSession { result ->
						result.onSuccess { relaunch(it) }
						result.onFailure { error ->
							isRefreshing = false
							binding.refreshOverlay.isGone = true
							Log.w("StreamActivity", "performRefresh: cloud refresh failed", error)
							alertDialogBuilder()
								.setTitle(R.string.quick_settings_refresh_failed_title)
								.setMessage(error.message ?: getString(R.string.quick_settings_refresh_failed_generic))
								.setPositiveButton(android.R.string.ok, null)
								.show()
						}
					}
				}
			}
		}
	}

	private fun relaunch(newConnectInfo: ConnectInfo, refreshRetryCount: Int = 0)
	{
		val intent = Intent(this, StreamActivity::class.java)
		intent.putExtra(EXTRA_CONNECT_INFO, newConnectInfo)
		intent.putExtra(EXTRA_IS_REFRESH, true)
		intent.putExtra(EXTRA_REFRESH_RETRY_COUNT, refreshRetryCount)
		startActivity(intent)
		finish()
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
		if (connectedAtElapsedRealtime == 0L) return
		val delta = SystemClock.elapsedRealtime() - connectedAtElapsedRealtime
		if (delta > 0L)
			viewModel.preferences.addTotalStreamTimeMs(delta)
		connectedAtElapsedRealtime = 0L
	}

	private fun stateChanged(state: StreamState)
	{
		Log.i("StreamActivity", "stateChanged: $state pip=$isInPictureInPictureMode")
		// A refresh-launched Activity shows refreshOverlay instead of the plain progressBar for
		// its whole connect phase (not just StreamStateConnecting) — the "refreshing" message
		// should stay up from the moment the old Activity finished until this one is actually
		// connected, not flash away and back during any brief intermediate state.
		binding.progressBar.visibility = if(state == StreamStateConnecting && !isRefreshLaunch) View.VISIBLE else View.GONE
		if(isRefreshLaunch)
			binding.refreshOverlay.isVisible = state != StreamStateConnected

		when(state)
		{
			StreamStateConnected ->
			{
				if (connectedAtElapsedRealtime == 0L)
					connectedAtElapsedRealtime = SystemClock.elapsedRealtime()
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
				if(isRefreshing)
				{
					// This quit was caused by our own Session.stop() in performRefresh(), not a
					// real disconnect — its own async flow (waiting on the network teardown,
					// then reconnecting/reallocating) owns what happens next, not this dialog/
					// finish() logic.
				}
				else if(dialogContents != StreamQuitDialog)
				{
					if(isRefreshLaunch && state.reason.isConsoleInUse && refreshRetryCount < MAX_REFRESH_RETRIES)
					{
						// The console hasn't freed its Remote Play session slot from the previous
						// (just-closed) connection yet. Retry automatically with a growing delay
						// instead of dropping the user into the manual reconnect dialog below —
						// refreshOverlay stays up throughout (see the isRefreshLaunch visibility
						// rule above) so this is invisible to the user as anything other than a
						// slightly longer wait.
						val nextRetry = refreshRetryCount + 1
						val delayMs = REMOTE_PLAY_REFRESH_RECONNECT_DELAY_MS * (nextRetry + 1)
						Log.w("StreamActivity", "stateChanged: console still in use after refresh (retry $refreshRetryCount/$MAX_REFRESH_RETRIES) — retrying in ${delayMs}ms")
						Handler(Looper.getMainLooper()).postDelayed(
							{ relaunch(viewModel.refreshedRemotePlayConnectInfo(), refreshRetryCount = nextRetry) },
							delayMs
						)
					}
					else if(state.reason.isError)
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
				if(dialogContents != CreateErrorDialog)
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
