// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.view.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.lib.*

sealed class StreamState
object StreamStateIdle: StreamState()
object StreamStateConnecting: StreamState()
object StreamStateConnected: StreamState()
data class StreamStateCreateError(val error: CreateError): StreamState()
data class StreamStateQuit(val reason: QuitReason, val reasonString: String?): StreamState()
data class StreamStateLoginPinRequest(val pinIncorrect: Boolean): StreamState()

class StreamSession(val connectInfo: ConnectInfo, val logManager: LogManager, val logVerbose: Boolean, val input: StreamInput)
{
	var session: Session? = null
		private set

	private val _state = MutableLiveData<StreamState>(StreamStateIdle)
	val state: LiveData<StreamState> get() = _state

	private var surfaceTexture: SurfaceTexture? = null
	private var surface: Surface? = null

	/** Holepunch session for PSN connections (kept alive for session lifetime) */
	private var holepunchSession: HolepunchSession? = null

	/** When true, surfaceDestroyed will not call setSurface(null) on the native session.
	 *  Set to true during PiP transitions where the surface is briefly destroyed and
	 *  recreated, and setSurface(null) blocks on the native decoder. */
	var skipNativeSurfaceCleanup = false

	// ---- Microphone (Remote Play only) ----
	// The console only accepts mic audio in a fixed 2ch/16-bit/48000Hz/480-samples-per-frame
	// format (see the matching header set up natively in chiaki-jni.c's sessionCreate), so
	// capture is mono at 48kHz and each sample is duplicated into an interleaved stereo frame.
	private val micSampleRate = 48000
	private val micSamplesPerFrame = 480
	private var audioRecord: AudioRecord? = null
	private var micThread: Thread? = null
	@Volatile private var micThreadRunning = false
	/** Whether connectMicrophone() has been sent for the current native session (once per
	 *  session — subsequent on/off just mutes/unmutes, mirroring the Qt desktop client). */
	private var micConnected = false
	/** True only from CHIAKI_EVENT_CONNECTED until shutdown() — see setMicrophoneEnabled. */
	private var sessionConnected = false

	init
	{
		input.controllerStateChangedCallback = {
			session?.setControllerState(it)
		}
	}

	/** Live-toggles mic capture for an already-running stream, e.g. from the in-stream Quick
	 *  Settings panel or automatically once CHIAKI_EVENT_CONNECTED fires if the setting was
	 *  already enabled. No-ops outside Remote Play sessions, without an active session, before
	 *  the session has fully connected, or without RECORD_AUDIO granted (permission must
	 *  already have been requested by the caller — this only defends against it having been
	 *  revoked since, or being called too early).
	 *
	 *  The "before fully connected" guard matters even for the disable path: connectMicrophone()/
	 *  toggleMicrophone() go through chiaki_ctrl's thread-safe queued send path, which wakes the
	 *  ctrl thread by signaling the same notif_pipe that ctrl_connect() blocks on while the
	 *  initial TCP handshake to the console is still in flight (lib/src/ctrl.c). Signaling it
	 *  during that window is indistinguishable from a cancellation and aborts the control
	 *  connection outright ("Ctrl has failed while waiting for ctrl startup") — confirmed
	 *  on-device. The Quick Settings panel has no gating that prevents it being opened and its
	 *  mic row toggled while still in StreamStateConnecting, so this can't just be the caller's
	 *  responsibility — it's safe to call this no-op path repeatedly since the preference is
	 *  still recorded by the caller either way and gets picked up by the CHIAKI_EVENT_CONNECTED
	 *  auto-enable once the session is actually ready. */
	fun setMicrophoneEnabled(enabled: Boolean)
	{
		val session = session ?: return
		if(!input.isRemotePlay)
			return
		if(!sessionConnected)
		{
			Log.w("StreamSession", "setMicrophoneEnabled($enabled) called before the session finished connecting; ignoring")
			return
		}
		if(enabled)
		{
			if(ContextCompat.checkSelfPermission(input.context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
			{
				Log.w("StreamSession", "setMicrophoneEnabled(true) but RECORD_AUDIO is not granted")
				return
			}
			if(!micConnected)
			{
				session.connectMicrophone()
				micConnected = true
			}
			// chiaki_ctrl's mic toggle wire encoding is inverted from what its `muted`
			// parameter name suggests (confirmed on-device: passing false here actually
			// muted the console-side mic, true unmuted it) — ctrl.c's own toggle log line
			// has the same inversion. Compensating here rather than in the shared native
			// library, which other platforms may already work around in their own way.
			session.toggleMicrophone(true)
			startMicCapture(session)
		}
		else
		{
			session.toggleMicrophone(false)
			stopMicCapture()
		}
	}

	private fun startMicCapture(nativeSession: Session)
	{
		if(micThreadRunning)
			return

		val minBufBytes = AudioRecord.getMinBufferSize(micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
		if(minBufBytes <= 0)
		{
			Log.e("StreamSession", "Mic capture: getMinBufferSize failed ($minBufBytes)")
			return
		}
		val bufferSizeBytes = maxOf(minBufBytes, micSamplesPerFrame * 2 * 4)

		fun openAudioRecord(source: Int) = try
		{
			AudioRecord(source, micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeBytes)
		}
		catch(e: SecurityException)
		{
			Log.e("StreamSession", "Mic capture: failed to create AudioRecord", e)
			null
		}

		var record = openAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
		if(record == null || record.state != AudioRecord.STATE_INITIALIZED)
		{
			record?.release()
			record = openAudioRecord(MediaRecorder.AudioSource.MIC)
		}
		if(record == null || record.state != AudioRecord.STATE_INITIALIZED)
		{
			Log.e("StreamSession", "Mic capture: AudioRecord failed to initialize")
			record?.release()
			return
		}

		audioRecord = record
		micThreadRunning = true
		record.startRecording()
		micThread = Thread {
			val monoBuf = ShortArray(micSamplesPerFrame)
			val stereoBuf = ShortArray(micSamplesPerFrame * 2)
			while(micThreadRunning)
			{
				val read = record.read(monoBuf, 0, micSamplesPerFrame)
				if(read <= 0)
					continue
				for(i in 0 until read)
				{
					stereoBuf[i * 2] = monoBuf[i]
					stereoBuf[i * 2 + 1] = monoBuf[i]
				}
				if(read == micSamplesPerFrame)
					nativeSession.sendMicFrame(stereoBuf)
			}
		}.apply {
			name = "MicCapture"
			start()
		}
	}

	private fun stopMicCapture()
	{
		micThreadRunning = false
		// Stop the record first to unblock a pending read() — joining before this can hang.
		audioRecord?.let {
			try { it.stop() } catch(e: Exception) { Log.w("StreamSession", "Mic capture: stop failed", e) }
		}
		micThread?.join(500)
		micThread = null
		audioRecord?.release()
		audioRecord = null
	}

	fun shutdown()
	{
		Log.i("StreamSession", "shutdown: session=${session != null}")
		// Mic capture thread must be fully joined before the native session pointer can be
		// freed below (on the background dispose thread) — otherwise a concurrent
		// sendMicFrame() call could race the native free.
		stopMicCapture()
		micConnected = false
		sessionConnected = false
		// If a native Session was created with a holepunch pointer, the Session owns it
		// and will free it in chiaki_session_fini(). Don't double-free.
		if(session != null)
		{
			val sessionToDispose = session
			session?.stop()
			// Move blocking dispose() call to background thread to prevent ANR
			// (dispose can block for 10+ seconds on network timeouts during holepunch cleanup)
			Thread {
				sessionToDispose?.dispose()
				Log.i("StreamSession", "Session disposed on background thread")
			}.start()
			session = null
			holepunchSession = null // consumed by native Session
		}
		else
		{
			val hpSessionToFini = holepunchSession
			// Move blocking fini() call to background thread to prevent ANR
			Thread {
				hpSessionToFini?.fini()
				Log.i("StreamSession", "Holepunch session finalized on background thread")
			}.start()
			holepunchSession = null
		}
		_state.value = StreamStateIdle
		//surfaceTexture?.release()
	}

	fun pause()
	{
		Log.i("StreamSession", "pause")
		shutdown()
	}

	fun resume()
	{
		Log.i("StreamSession", "resume: session=${session != null}")
		if(session != null)
			return
		_state.value = StreamStateConnecting

		val duid = connectInfo.duid
		val hasPsnToken = !connectInfo.psnToken.isNullOrEmpty()
		Log.i("StreamSession", "resume: duid=${duid?.take(16) ?: "null"}, hasPsnToken=$hasPsnToken, host=${connectInfo.host}, ps5=${connectInfo.ps5}")
		if(!duid.isNullOrEmpty() && hasPsnToken)
		{
			// PSN connection: perform holepunch before creating session
			Log.i("StreamSession", "Using PSN holepunch connection path")
			resumePsnConnection(duid)
		}
		else
		{
			// Local or cloud connection: create session directly
			Log.i("StreamSession", "Using local/cloud connection path")
			resumeLocalConnection()
		}
	}

	/**
	 * Resume with a PSN holepunch connection.
	 * Mimics StreamSession::ConnectPsnConnection() from the Qt app.
	 * Runs holepunch steps on background thread, then creates Session with holepunch ptr.
	 */
	private fun resumePsnConnection(duid: String)
	{
		Thread {
			try
			{
				Log.i("StreamSession", "Starting PSN holepunch connection (duid=$duid)")

				// Step 1: Initialize holepunch session
				val hpSession = HolepunchSession(connectInfo.psnToken!!)
				holepunchSession = hpSession

				// Step 2: Discover UPnP
				val upnpErr = hpSession.upnpDiscover()
				if(!upnpErr.isSuccess)
					Log.w("StreamSession", "UPnP discover failed (non-fatal): $upnpErr")

				// Step 3: Create session on PSN server
				val createErr = hpSession.create()
				if(!createErr.isSuccess)
				{
					Log.e("StreamSession", "Holepunch session create failed: $createErr")
					hpSession.fini()
					holepunchSession = null
					_state.postValue(StreamStateCreateError(CreateError(createErr)))
					return@Thread
				}
				Log.i("StreamSession", "Holepunch session created")

				// Step 4: Create offer for control connection
				val offerErr = hpSession.createOffer()
				if(!offerErr.isSuccess)
				{
					Log.e("StreamSession", "Holepunch create offer failed: $offerErr")
					hpSession.fini()
					holepunchSession = null
					_state.postValue(StreamStateCreateError(CreateError(offerErr)))
					return@Thread
				}
				Log.i("StreamSession", "Holepunch offer created for CTRL")

				// Step 5: Start session for specific console
				val duidBytes = hexStringToBytes(duid)
				val consoleType = if(connectInfo.ps5) HolepunchConsoleType.PS5 else HolepunchConsoleType.PS4
				val startErr = hpSession.start(duidBytes, consoleType)
				if(!startErr.isSuccess)
				{
					Log.e("StreamSession", "Holepunch session start failed: $startErr")
					hpSession.fini()
					holepunchSession = null
					_state.postValue(StreamStateCreateError(CreateError(startErr)))
					return@Thread
				}
				Log.i("StreamSession", "Holepunch session started")

				// Step 6: Punch hole for control connection
				val punchErr = hpSession.punchHole(HolepunchPortType.CTRL)
				if(!punchErr.isSuccess)
				{
					Log.e("StreamSession", "Holepunch punch hole (CTRL) failed: $punchErr")
					hpSession.fini()
					holepunchSession = null
					_state.postValue(StreamStateCreateError(CreateError(punchErr)))
					return@Thread
				}
				Log.i("StreamSession", "Holepunch CTRL hole punched!")

				// Step 7: Create Session with holepunch session pointer
				// The native session_init() will use this for the streaming connection
				// (data hole punching happens inside the native session thread)
				val psnConnectInfo = connectInfo.copy(holepunchSessionPtr = hpSession.getPtr())
				val session = Session(psnConnectInfo, logManager.createNewFile().file.absolutePath, logVerbose)
				session.eventCallback = this::eventCallback
				session.start()
				val surface = surface
				if(surface != null)
					session.setSurface(surface)
				this.session = session
			}
			catch(e: CreateError)
			{
				holepunchSession?.fini()
				holepunchSession = null
				_state.postValue(StreamStateCreateError(e))
			}
			catch(e: Exception)
			{
				Log.e("StreamSession", "PSN connection failed", e)
				holepunchSession?.fini()
				holepunchSession = null
				_state.postValue(StreamStateCreateError(CreateError(ErrorCode(-1))))
			}
		}.start()
	}

	/**
	 * Resume with a local/cloud connection (no holepunch).
	 */
	private fun resumeLocalConnection()
	{
		// Create session on background thread to avoid ANR (DNS resolution can block)
		Thread {
			try
			{
				val session = Session(connectInfo, logManager.createNewFile().file.absolutePath, logVerbose)
				session.eventCallback = this::eventCallback
				session.start()
				val surface = surface
				if(surface != null)
					session.setSurface(surface)
				this.session = session
			}
			catch(e: CreateError)
			{
				_state.postValue(StreamStateCreateError(e))
			}
		}.start()
	}

	private fun hexStringToBytes(hex: String): ByteArray
	{
		val len = hex.length / 2
		val result = ByteArray(len)
		for(i in 0 until len)
		{
			result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
		}
		return result
	}

	private fun eventCallback(event: Event)
	{
		Log.i("StreamSession", "eventCallback: ${event.javaClass.simpleName}")
		when(event)
		{
			is ConnectedEvent -> {
				Log.i("StreamSession", "EVENT: Connected!")
				_state.postValue(StreamStateConnected)
				sessionConnected = true
				if(input.preferences.micEnabled)
					setMicrophoneEnabled(true)
			}
			is QuitEvent -> {
				Log.i("StreamSession", "EVENT: Quit reason=${event.reason} str=${event.reasonString}")
				_state.postValue(StreamStateQuit(event.reason, event.reasonString))
			}
			is LoginPinRequestEvent -> {
				Log.i("StreamSession", "EVENT: LoginPinRequest pinIncorrect=${event.pinIncorrect}")
				_state.postValue(StreamStateLoginPinRequest(event.pinIncorrect))
			}
			is RumbleEvent -> { }
			is AutoRegistEvent -> Log.i("StreamSession", "EVENT: AutoRegist host=${event.host.serverNickname}")
			is HolepunchEvent -> Log.i("StreamSession", "EVENT: Holepunch")
		}
	}

	fun attachToSurfaceView(surfaceView: SurfaceView)
	{
		surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder)
			{
				Log.i("StreamSession", "surfaceCreated")
			}

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int)
			{
				Log.i("StreamSession", "surfaceChanged: ${width}x${height}, session=${session != null}")
				val surface = holder.surface
				this@StreamSession.surface = surface
				session?.setSurface(surface)
				Log.i("StreamSession", "surfaceChanged: setSurface done")
			}

			override fun surfaceDestroyed(holder: SurfaceHolder)
			{
				Log.i("StreamSession", "surfaceDestroyed: session=${session != null}, skipNativeCleanup=$skipNativeSurfaceCleanup")
				this@StreamSession.surface = null
				if (!skipNativeSurfaceCleanup)
				{
					session?.setSurface(null)
					Log.i("StreamSession", "surfaceDestroyed: setSurface(null) done")
				}
			}
		})
	}

	/** Like [attachToTextureView], but the Surface comes from a [com.metallic.chiaki.stream.CasVideoSurfaceView]'s
	 *  off-screen SurfaceTexture (an external OES texture sampled by its CAS shader) instead of a
	 *  TextureView's own on-screen one — the decoder doesn't know or care which. */
	fun attachToCasSurfaceView(view: com.metallic.chiaki.stream.CasVideoSurfaceView)
	{
		view.onSurfaceReady = { readySurface ->
			if(surface == null)
			{
				surface = readySurface
				session?.setSurface(readySurface)
			}
		}
	}

	fun attachToTextureView(textureView: TextureView)
	{
		textureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
			override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int)
			{
				if(surfaceTexture != null)
					return
				surfaceTexture = surface
				this@StreamSession.surface = Surface(surfaceTexture)
				session?.setSurface(Surface(surface))
			}

			override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean
			{
				// return false if we want to keep the surface texture
				return surfaceTexture == null
			}

			override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { }
			override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
		}

		val surfaceTexture = surfaceTexture
		if(surfaceTexture != null)
			textureView.setSurfaceTexture(surfaceTexture)
	}

	fun setLoginPin(pin: String)
	{
		session?.setLoginPin(pin)
	}
}