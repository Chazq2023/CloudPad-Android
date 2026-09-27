package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.lib.ControllerTouch
import com.metallic.chiaki.lib.maxAbs
import kotlin.math.pow

class StreamInput(
	val context: Context,
	val preferences: Preferences,
	val isRemotePlay: Boolean = false
) {
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	val controllerState: ControllerState get() =
		mergeControllerStates(sensorControllerState, keyControllerState, motionControllerState, touchControllerState, cachedRotation)

	private val sensorControllerState = ControllerState()
	private val keyControllerState = ControllerState()
	private val motionControllerState = ControllerState()

	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon
	private val handler = Handler(Looper.getMainLooper())

	// ---- Mapping lookup structures ----

	private var activeMapping: Map<ControllerAction, PhysicalInput> = emptyMap()
	private var singleKeyToActions: Map<Int, List<ControllerAction>> = emptyMap()
	private var singleAxisMappings: List<Triple<ControllerAction, Int, Boolean>> = emptyList()

	data class ComboEntry(val modifierKeyCode: Int, val trigger: PhysicalInput, val action: ControllerAction)

	private var comboEntries: List<ComboEntry> = emptyList()
	private var comboModifierKeyCodes: Set<Int> = emptySet()

	// ---- Combo runtime state ----

	private val heldModifiers = mutableMapOf<Int, Boolean>()
	private val activeComboActions = mutableMapOf<ControllerAction, Int>()
	private val triggeredComboAxes = mutableSetOf<Pair<Int, Boolean>>()
	// Last-known values for axes used as combo triggers — used to ignore axes that were
	// already above threshold when the modifier key was pressed (e.g. L2 drift at rest).
	private val lastAxisValues = mutableMapOf<Int, Float>()

	init { reloadMapping() }

	/**
	 * Rebuilds the mapping lookup tables from the currently-saved controller mapping.
	 * Called once at construction, and again from the in-stream Quick Settings panel's
	 * Save button after a remap edit, so a live session picks up the new mapping without
	 * needing to reconnect.
	 */
	fun reloadMapping()
	{
		activeMapping = PhysicalInput.resolveMapping(preferences.loadControllerMapping())

		singleKeyToActions = activeMapping.entries
			.filter { it.value is PhysicalInput.Button }
			.groupBy(
				keySelector = { (it.value as PhysicalInput.Button).keyCode },
				valueTransform = { it.key }
			)

		singleAxisMappings = activeMapping.entries
			.filter { it.value is PhysicalInput.AxisDirection }
			.map { val ax = it.value as PhysicalInput.AxisDirection; Triple(it.key, ax.axis, ax.positive) }

		comboEntries = activeMapping.entries
			.filter { it.value is PhysicalInput.Combo }
			.map { (action, input) ->
				val combo = input as PhysicalInput.Combo
				ComboEntry(combo.modifierKeyCode, combo.trigger, action)
			}

		comboModifierKeyCodes = comboEntries.map { it.modifierKeyCode }.toSet()

		// Defensive: drop any in-flight held-key/combo runtime state referencing the old
		// mapping, to avoid a "stuck button" if a physical key held during remapping no
		// longer maps to anything.
		stopTouchpadHold()
		heldModifiers.clear()
		activeComboActions.clear()
		triggeredComboAxes.clear()
		lastAxisValues.clear()
		keyControllerState.buttons = 0U
		keyControllerState.l2State = 0U
		keyControllerState.r2State = 0U
		controllerStateUpdated()
	}

	// ---- Sensor / lifecycle ----

	/** Backs the rotation the [controllerState] getter flips motion axes for. Only read/written
	 *  while motion sensors are actually registered (see [registerDisplayListener]/
	 *  [unregisterDisplayListener], hooked into the exact same on/off points as the sensor
	 *  listener below) — accel/gyro/orient all stay at their inert defaults whenever motion isn't
	 *  active, so a stale rotation value has nothing to flip and can't cause any observable
	 *  difference; it only needs to be correct exactly when motion data is live. Replaces a
	 *  `context.getSystemService(WINDOW_SERVICE)` + `.defaultDisplay.rotation` query the getter
	 *  used to make on every single read (up to ~250Hz per active sensor) with a plain field read,
	 *  refreshed instead only on an actual rotation change via [DisplayManager.DisplayListener]. */
	@Volatile private var cachedRotation: Int = Surface.ROTATION_0
	private var displayListenerRegistered = false

	private val displayListener = object: DisplayManager.DisplayListener {
		override fun onDisplayAdded(displayId: Int) {}
		override fun onDisplayRemoved(displayId: Int) {}
		override fun onDisplayChanged(displayId: Int) { refreshCachedRotation() }
	}

	private fun refreshCachedRotation()
	{
		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		cachedRotation = windowManager.defaultDisplay.rotation
	}

	private fun registerDisplayListener()
	{
		if(displayListenerRegistered) return
		displayListenerRegistered = true
		refreshCachedRotation()
		val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
		displayManager.registerDisplayListener(displayListener, handler)
	}

	private fun unregisterDisplayListener()
	{
		if(!displayListenerRegistered) return
		displayListenerRegistered = false
		val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
		displayManager.unregisterDisplayListener(displayListener)
	}

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
			registerDisplayListener()
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			unregisterDisplayListener()
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	private var lifecycleOwnerRef: LifecycleOwner? = null
	private var motionObserverAdded = false

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		lifecycleOwnerRef = lifecycleOwner
		if(preferences.motionEnabled)
			enableMotion()
	}

	/** Live-toggles motion sensor input for an already-running stream, e.g. from the
	 *  in-stream Quick Settings panel, without needing to reconnect. */
	fun setMotionEnabled(enabled: Boolean)
	{
		if(enabled) enableMotion() else disableMotion()
	}

	private fun enableMotion()
	{
		val owner = lifecycleOwnerRef ?: return
		if(motionObserverAdded) return
		owner.lifecycle.addObserver(motionLifecycleObserver)
		motionObserverAdded = true
	}

	private fun disableMotion()
	{
		val owner = lifecycleOwnerRef ?: return
		if(!motionObserverAdded) return
		owner.lifecycle.removeObserver(motionLifecycleObserver)
		motionObserverAdded = false

		// Lifecycle.removeObserver() doesn't synthesize an ON_PAUSE call, so the sensor
		// listener must be unregistered explicitly here, otherwise motion data keeps
		// streaming (or sticks at its last reading) after being "disabled".
		unregisterDisplayListener()
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
		sensorManager.unregisterListener(sensorEventListener)
		motionControllerState.accelX = 0f; motionControllerState.accelY = 0f; motionControllerState.accelZ = 0f
		motionControllerState.gyroX = 0f; motionControllerState.gyroY = 0f; motionControllerState.gyroZ = 0f
		motionControllerState.orientX = 0f; motionControllerState.orientY = 0f; motionControllerState.orientZ = 0f; motionControllerState.orientW = 0f
		controllerStateUpdated()
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	// ---- Touchpad gestures ----

	/** Touch id for the sustained touch registered by [startTouchpadHold], if currently held. */
	private var touchpadHoldTouchId: UByte? = null

	/** Simulates pressing and holding the touchpad button (as opposed to [quickTouchpadTap]'s
	 *  momentary click) — the touch position and BUTTON_TOUCHPAD stay set until [stopTouchpadHold]
	 *  is called, mirroring how [PhysicalInput.Combo]-driven actions are held for as long as the
	 *  mapped combo/button stays pressed. */
	private fun startTouchpadHold()
	{
		touchControllerState = ControllerState()
		val touchId = touchControllerState.startTouch(960U.toUShort(), 471U.toUShort()) ?: return
		touchpadHoldTouchId = touchId
		keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
		controllerStateUpdated()
	}

	private fun stopTouchpadHold()
	{
		touchpadHoldTouchId?.let { touchControllerState.stopTouch(it) }
		touchpadHoldTouchId = null
		touchControllerState = ControllerState()
		keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
		controllerStateUpdated()
	}

	private fun quickTouchpadTap(x: UShort, y: UShort)
	{
		touchControllerState = ControllerState()
		val touchId = touchControllerState.startTouch(x, y) ?: return
		// Set BUTTON_TOUCHPAD alongside the touch position to simulate a physical click
		keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
		controllerStateUpdated()

		handler.postDelayed({
			touchControllerState.stopTouch(touchId)
			touchControllerState = ControllerState()
			keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
			controllerStateUpdated()
		}, 80)
	}

	private fun quickTouchpadSwipe(direction: Int)
	{
		keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
		controllerStateUpdated()

		val startX = 960U.toUShort()
		val startY = 471U.toUShort()

		val endX: UShort
		val endY: UShort

		when(direction)
		{
			KeyEvent.KEYCODE_DPAD_UP -> { endX = startX; endY = 120U.toUShort() }
			KeyEvent.KEYCODE_DPAD_DOWN -> { endX = startX; endY = 820U.toUShort() }
			KeyEvent.KEYCODE_DPAD_LEFT -> { endX = 250U.toUShort(); endY = startY }
			KeyEvent.KEYCODE_DPAD_RIGHT -> { endX = 1670U.toUShort(); endY = startY }
			else -> return
		}

		touchControllerState = ControllerState()
		val touchId = touchControllerState.startTouch(startX, startY) ?: return
		controllerStateUpdated()

		handler.postDelayed({
			touchControllerState.setTouchPos(touchId, endX, endY)
			controllerStateUpdated()
		}, 60)

		handler.postDelayed({
			touchControllerState.stopTouch(touchId)
			touchControllerState = ControllerState()
			controllerStateUpdated()
		}, 140)
	}

	// ---- Action → button mask ----

	private fun actionToButtonMask(action: ControllerAction): UInt? = when(action)
	{
		ControllerAction.CROSS -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
		ControllerAction.CIRCLE -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
		ControllerAction.SQUARE -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
		ControllerAction.TRIANGLE -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
		ControllerAction.L1 -> ControllerState.BUTTON_L1
		ControllerAction.R1 -> ControllerState.BUTTON_R1
		ControllerAction.L3 -> ControllerState.BUTTON_L3
		ControllerAction.R3 -> ControllerState.BUTTON_R3
		ControllerAction.START -> ControllerState.BUTTON_OPTIONS
		ControllerAction.SELECT -> if(!isRemotePlay) ControllerState.BUTTON_SHARE else null
		// Unlike SELECT above, BUTTON_PS isn't session-type-specific — the on-screen touch
		// controls' PS button (TouchControlsFragment.psButtonView) already sends it
		// unconditionally in both Remote Play and Cloud sessions. This physical-controller combo
		// path used to only send it for Remote Play, leaving the PS Home combo silently dead in
		// PS3/PS4/PS5 Cloud sessions even though the wire protocol accepts it fine.
		ControllerAction.HOME -> ControllerState.BUTTON_PS
		ControllerAction.DPAD_UP -> ControllerState.BUTTON_DPAD_UP
		ControllerAction.DPAD_DOWN -> ControllerState.BUTTON_DPAD_DOWN
		ControllerAction.DPAD_LEFT -> ControllerState.BUTTON_DPAD_LEFT
		ControllerAction.DPAD_RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
		ControllerAction.TOUCHPAD_CLICK -> ControllerState.BUTTON_TOUCHPAD
		else -> null
	}

	// ---- Action press / release ----

	private fun pressAction(action: ControllerAction)
	{
		when(action)
		{
			ControllerAction.L2 -> { keyControllerState.l2State = UByte.MAX_VALUE; controllerStateUpdated() }
			ControllerAction.R2 -> { keyControllerState.r2State = UByte.MAX_VALUE; controllerStateUpdated() }
			ControllerAction.TOUCHPAD_CLICK -> quickTouchpadTap(960U.toUShort(), 471U.toUShort())
			ControllerAction.TOUCHPAD_HOLD -> startTouchpadHold()
			ControllerAction.TOUCHPAD_LEFT_CLICK -> quickTouchpadTap(480U.toUShort(), 471U.toUShort())
			ControllerAction.TOUCHPAD_RIGHT_CLICK -> quickTouchpadTap(1440U.toUShort(), 471U.toUShort())
			ControllerAction.TOUCHPAD_SWIPE_UP -> quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_UP)
			ControllerAction.TOUCHPAD_SWIPE_DOWN -> quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_DOWN)
			ControllerAction.TOUCHPAD_SWIPE_LEFT -> quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_LEFT)
			ControllerAction.TOUCHPAD_SWIPE_RIGHT -> quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_RIGHT)
			else -> {
				val mask = actionToButtonMask(action) ?: return
				keyControllerState.buttons = keyControllerState.buttons or mask
				controllerStateUpdated()
			}
		}
	}

	private fun releaseAction(action: ControllerAction)
	{
		when(action)
		{
			ControllerAction.L2 -> { keyControllerState.l2State = 0U; controllerStateUpdated() }
			ControllerAction.R2 -> { keyControllerState.r2State = 0U; controllerStateUpdated() }
			ControllerAction.TOUCHPAD_HOLD -> stopTouchpadHold()
			// Tap/swipe actions are fire-and-forget; quickTouchpadTap/Swipe handle their own cleanup
			ControllerAction.TOUCHPAD_CLICK, ControllerAction.TOUCHPAD_LEFT_CLICK,
			ControllerAction.TOUCHPAD_RIGHT_CLICK, ControllerAction.TOUCHPAD_SWIPE_UP,
			ControllerAction.TOUCHPAD_SWIPE_DOWN, ControllerAction.TOUCHPAD_SWIPE_LEFT,
			ControllerAction.TOUCHPAD_SWIPE_RIGHT -> {}
			else -> {
				val mask = actionToButtonMask(action) ?: return
				keyControllerState.buttons = keyControllerState.buttons and mask.inv()
				controllerStateUpdated()
			}
		}
	}

	private fun fireQuickPress(action: ControllerAction)
	{
		pressAction(action)
		when(action)
		{
			ControllerAction.TOUCHPAD_CLICK, ControllerAction.TOUCHPAD_LEFT_CLICK,
			ControllerAction.TOUCHPAD_RIGHT_CLICK, ControllerAction.TOUCHPAD_SWIPE_UP,
			ControllerAction.TOUCHPAD_SWIPE_DOWN, ControllerAction.TOUCHPAD_SWIPE_LEFT,
			ControllerAction.TOUCHPAD_SWIPE_RIGHT -> {}
			else -> handler.postDelayed({ releaseAction(action) }, 80)
		}
	}

	// ---- Combo modifier lifecycle ----

	// Actions that fire as a momentary pulse rather than being held for the key duration.
	// TOUCHPAD_CLICK is included so it never overlaps with BUTTON_SHARE when both are on
	// the same physical key — the brief BUTTON_TOUCHPAD pulse fires then clears independently.
	private fun isQuickPressAction(action: ControllerAction) =
		action == ControllerAction.TOUCHPAD_CLICK
		|| action == ControllerAction.TOUCHPAD_LEFT_CLICK
		|| action == ControllerAction.TOUCHPAD_RIGHT_CLICK
		|| action == ControllerAction.TOUCHPAD_SWIPE_UP
		|| action == ControllerAction.TOUCHPAD_SWIPE_DOWN
		|| action == ControllerAction.TOUCHPAD_SWIPE_LEFT
		|| action == ControllerAction.TOUCHPAD_SWIPE_RIGHT

	private fun onComboModifierDown(keyCode: Int)
	{
		if(keyCode !in heldModifiers)
		{
			heldModifiers[keyCode] = false
			triggeredComboAxes.clear()
			// Pre-mark any combo-trigger axes that are already above threshold (e.g. L2 drift)
			// so they don't fire a combo on the very first motion event after the modifier press.
			for(combo in comboEntries)
			{
				if(combo.modifierKeyCode != keyCode) continue
				if(combo.trigger !is PhysicalInput.AxisDirection) continue
				val current = lastAxisValues[combo.trigger.axis] ?: 0f
				val dir = if(combo.trigger.positive) maxOf(0f, current) else maxOf(0f, -current)
				if(dir > 0.5f) triggeredComboAxes.add(combo.trigger.axis to combo.trigger.positive)
			}

			val actions = singleKeyToActions[keyCode]
			val hasHeldActions = actions?.any { !isQuickPressAction(it) } == true

			actions?.forEach { action ->
				if(!isQuickPressAction(action))
				{
					// Held actions (e.g. SELECT→BUTTON_SHARE) always press immediately on key-down
					pressAction(action)
				}
				else if(!hasHeldActions)
				{
					// No held actions present: fire quick-press actions immediately (same
					// behaviour as the non-modifier single-action path, e.g. TOUCHPAD_CLICK)
					fireQuickPress(action)
				}
				// If there ARE held actions, quick-press actions are deferred to key-up
				// to avoid BUTTON_TOUCHPAD overlapping BUTTON_SHARE in the same state frame
			}
		}
	}

	private fun onComboModifierUp(keyCode: Int)
	{
		val comboTriggered = heldModifiers.remove(keyCode) ?: false
		triggeredComboAxes.clear()

		val toRelease = activeComboActions.entries.filter { it.value == keyCode }.map { it.key }.toList()
		for(action in toRelease)
		{
			activeComboActions.remove(action)
			releaseAction(action)
		}

		val actions = singleKeyToActions[keyCode]
		val hasHeldActions = actions?.any { !isQuickPressAction(it) } == true

		// Two passes: release held actions first, then fire quick presses.
		// This guarantees BUTTON_SHARE (SELECT) is cleared before BUTTON_TOUCHPAD
		// is set, so they never appear together in a controller state frame.
		// Quick-press actions are only deferred here when held actions are also present;
		// if there are no held actions they already fired on key-down.
		actions?.forEach { action ->
			if(!isQuickPressAction(action)) releaseAction(action)
		}
		if(!comboTriggered && hasHeldActions)
		{
			// Defer quick-press actions one event-loop tick (matching the single-action path's
			// handler.post deferral) so the cleared BUTTON_SHARE state is fully processed
			// by the server before BUTTON_TOUCHPAD appears.
			val quickPressActions = singleKeyToActions[keyCode]?.filter { isQuickPressAction(it) } ?: emptyList()
			if(quickPressActions.isNotEmpty())
			{
				handler.post {
					quickPressActions.forEach { fireQuickPress(it) }
				}
			}
		}
	}

	// ---- dispatchKeyEvent ----

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
		if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0)
			return event.keyCode in comboModifierKeyCodes || event.keyCode in singleKeyToActions
		val isDown = event.action == KeyEvent.ACTION_DOWN

		// --- COMBO MODIFIER ---
		if(event.keyCode in comboModifierKeyCodes)
		{
			if(isDown) onComboModifierDown(event.keyCode) else onComboModifierUp(event.keyCode)
			return true
		}

		// --- COMBO TRIGGER (button) ---
		if(isDown && heldModifiers.isNotEmpty())
		{
			for(combo in comboEntries)
			{
				if(combo.trigger !is PhysicalInput.Button) continue
				if(combo.trigger.keyCode != event.keyCode) continue
				if(combo.modifierKeyCode !in heldModifiers) continue

				heldModifiers[combo.modifierKeyCode] = true
				pressAction(combo.action)
				when(combo.action)
				{
					ControllerAction.TOUCHPAD_SWIPE_UP, ControllerAction.TOUCHPAD_SWIPE_DOWN,
					ControllerAction.TOUCHPAD_SWIPE_LEFT, ControllerAction.TOUCHPAD_SWIPE_RIGHT -> {}
					else -> activeComboActions[combo.action] = combo.modifierKeyCode
				}
				return true
			}
		}

		if(!isDown)
		{
			val activeCombo = activeComboActions.entries.firstOrNull { (action, _) ->
				comboEntries.any {
					it.action == action &&
					it.trigger is PhysicalInput.Button &&
					it.trigger.keyCode == event.keyCode
				}
			}
			if(activeCombo != null)
			{
				activeComboActions.remove(activeCombo.key)
				releaseAction(activeCombo.key)
				return true
			}
		}

		// --- SINGLE-INPUT ACTION(S) — one physical button may fire multiple actions ---
		val actions = singleKeyToActions[event.keyCode] ?: return false

		// If any held action (e.g. SELECT→BUTTON_SHARE) shares this key with TOUCHPAD_CLICK,
		// defer the touchpad quick press until after the held action releases so the two
		// button bits never appear in the same state update sent to the console.
		val hasHeldAction = actions.any { !isQuickPressAction(it) }

		for(action in actions)
		{
			when(action)
			{
				ControllerAction.L2 -> { keyControllerState.l2State = if(isDown) UByte.MAX_VALUE else 0U }
				ControllerAction.R2 -> { keyControllerState.r2State = if(isDown) UByte.MAX_VALUE else 0U }
				ControllerAction.TOUCHPAD_CLICK -> when {
					// Standalone: fire on key-down as normal
					!hasHeldAction && isDown -> fireQuickPress(action)
					// Paired with held action: defer to key-up so BUTTON_TOUCHPAD never
					// overlaps BUTTON_SHARE (or similar) in the same controller state frame
					hasHeldAction && !isDown -> handler.post { fireQuickPress(action) }
				}
				ControllerAction.TOUCHPAD_HOLD -> if(isDown) startTouchpadHold() else stopTouchpadHold()
				ControllerAction.TOUCHPAD_LEFT_CLICK -> { if(isDown) quickTouchpadTap(480U.toUShort(), 471U.toUShort()) }
				ControllerAction.TOUCHPAD_RIGHT_CLICK -> { if(isDown) quickTouchpadTap(1440U.toUShort(), 471U.toUShort()) }
				ControllerAction.TOUCHPAD_SWIPE_UP -> { if(isDown) quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_UP) }
				ControllerAction.TOUCHPAD_SWIPE_DOWN -> { if(isDown) quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_DOWN) }
				ControllerAction.TOUCHPAD_SWIPE_LEFT -> { if(isDown) quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_LEFT) }
				ControllerAction.TOUCHPAD_SWIPE_RIGHT -> { if(isDown) quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_RIGHT) }
				else -> {
					val buttonMask = actionToButtonMask(action) ?: continue
					keyControllerState.buttons = if(isDown) keyControllerState.buttons or buttonMask
					                              else keyControllerState.buttons and buttonMask.inv()
				}
			}
		}
		controllerStateUpdated()
		return true
	}

	// ---- onGenericMotionEvent ----

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false

		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		fun Float.coerceSigned() = coerceIn(-1f, 1f)
		// Front-loads L2/R2 response so a partial squeeze reaches a meaningful analog value
		// sooner instead of tracking raw travel linearly (which felt like it needed a near-full
		// press before anything registered), while still reaching maximum at a full press.
		fun Float.triggerResponseCurve() = if(this <= 0f) 0f else pow(0.4f)

		// L2/R2 travel is reported on different axis codes depending on the controller's
		// driver: Xbox-style pads use AXIS_LTRIGGER/AXIS_RTRIGGER, while DualShock/DualSense
		// pads commonly report the same physical trigger via AXIS_BRAKE/AXIS_GAS instead.
		// Checking both and taking whichever is populated means the default mapping gets
		// a genuine analog reading regardless of which axis the connected pad actually uses.
		fun MotionEvent.resolvedAxisValue(axis: Int): Float = when(axis)
		{
			MotionEvent.AXIS_LTRIGGER -> maxOf(getAxisValue(axis), getAxisValue(MotionEvent.AXIS_BRAKE))
			MotionEvent.AXIS_RTRIGGER -> maxOf(getAxisValue(axis), getAxisValue(MotionEvent.AXIS_GAS))
			else -> getAxisValue(axis)
		}

		// Update last-known axis values for combo edge detection
		for(combo in comboEntries)
		{
			if(combo.trigger is PhysicalInput.AxisDirection)
				lastAxisValues[combo.trigger.axis] = event.resolvedAxisValue(combo.trigger.axis)
		}

		// Combo axis triggers (modifier held + axis movement)
		if(heldModifiers.isNotEmpty())
		{
			for(combo in comboEntries)
			{
				if(combo.trigger !is PhysicalInput.AxisDirection) continue
				if(combo.modifierKeyCode !in heldModifiers) continue
				val rawValue = event.resolvedAxisValue(combo.trigger.axis)
				val dirValue = if(combo.trigger.positive) maxOf(0f, rawValue) else maxOf(0f, -rawValue)
				val triggerKey = combo.trigger.axis to combo.trigger.positive
				if(dirValue > 0.5f)
				{
					if(triggerKey !in triggeredComboAxes)
					{
						// First time this axis crosses the threshold — fire the combo once
						heldModifiers[combo.modifierKeyCode] = true
						triggeredComboAxes.add(triggerKey)
						// Quick-press actions (swipes) clean themselves up via their own delayed
						// handler and must never be tracked here, matching the button-trigger path.
						if(!isQuickPressAction(combo.action)) activeComboActions[combo.action] = combo.modifierKeyCode
						pressAction(combo.action)
						return true
					}
					// Already triggered — let normal axis processing continue (axis is
					// excluded from it via triggeredComboAxes, so no double-processing)
				}
				else if(triggerKey in triggeredComboAxes)
				{
					// Axis has returned to neutral while the modifier is still held — release any
					// held (non-quick-press) combo action bound to it, e.g. TOUCHPAD_HOLD, so it
					// doesn't stay stuck on until the modifier itself is released.
					triggeredComboAxes.remove(triggerKey)
					if(activeComboActions.remove(combo.action) != null) releaseAction(combo.action)
				}
			}
		}

		// Normal axis processing (skip axes claimed by an active combo)
		var leftX = 0f; var leftY = 0f; var rightX = 0f; var rightY = 0f
		var l2 = 0f; var r2 = 0f; var dpadX = 0f; var dpadY = 0f

		for((action, axis, positive) in singleAxisMappings)
		{
			if((axis to positive) in triggeredComboAxes) continue
			val rawValue = event.resolvedAxisValue(axis)
			val dirValue = if(positive) maxOf(0f, rawValue) else maxOf(0f, -rawValue)
			when(action)
			{
				ControllerAction.LEFT_STICK_RIGHT -> leftX += dirValue
				ControllerAction.LEFT_STICK_LEFT -> leftX -= dirValue
				ControllerAction.LEFT_STICK_DOWN -> leftY += dirValue
				ControllerAction.LEFT_STICK_UP -> leftY -= dirValue
				ControllerAction.RIGHT_STICK_RIGHT -> rightX += dirValue
				ControllerAction.RIGHT_STICK_LEFT -> rightX -= dirValue
				ControllerAction.RIGHT_STICK_DOWN -> rightY += dirValue
				ControllerAction.RIGHT_STICK_UP -> rightY -= dirValue
				ControllerAction.L2 -> l2 += dirValue
				ControllerAction.R2 -> r2 += dirValue
				ControllerAction.DPAD_RIGHT -> dpadX += dirValue
				ControllerAction.DPAD_LEFT -> dpadX -= dirValue
				ControllerAction.DPAD_DOWN -> dpadY += dirValue
				ControllerAction.DPAD_UP -> dpadY -= dirValue
				else -> {}
			}
		}

		var dpadButtons = 0U
		if(dpadX > 0.5f) dpadButtons = dpadButtons or ControllerState.BUTTON_DPAD_RIGHT
		if(dpadX < -0.5f) dpadButtons = dpadButtons or ControllerState.BUTTON_DPAD_LEFT
		if(dpadY > 0.5f) dpadButtons = dpadButtons or ControllerState.BUTTON_DPAD_DOWN
		if(dpadY < -0.5f) dpadButtons = dpadButtons or ControllerState.BUTTON_DPAD_UP

		val dpadMask = ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_LEFT or
				ControllerState.BUTTON_DPAD_DOWN or ControllerState.BUTTON_DPAD_UP
		motionControllerState.buttons = (motionControllerState.buttons and dpadMask.inv()) or dpadButtons
		motionControllerState.leftX = leftX.coerceSigned().signedAxis()
		motionControllerState.leftY = leftY.coerceSigned().signedAxis()
		motionControllerState.rightX = rightX.coerceSigned().signedAxis()
		motionControllerState.rightY = rightY.coerceSigned().signedAxis()
		motionControllerState.l2State = l2.coerceIn(0f, 1f).triggerResponseCurve().unsignedAxis()
		motionControllerState.r2State = r2.coerceIn(0f, 1f).triggerResponseCurve().unsignedAxis()

		controllerStateUpdated()
		return true
	}
}

/** Merges sensor/key/motion/touch [ControllerState]s into a single fresh one, reproducing
 *  exactly what chaining three [ControllerState.or] calls used to compute (see git history for
 *  the previous `sensorControllerState or keyControllerState or motionControllerState`, then
 *  `... or touchControllerState`), but as one allocation instead of three. [StreamInput]'s
 *  `controllerState` getter reads this up to ~250Hz per active motion sensor (see the sensor
 *  registration in `StreamInput.motionLifecycleObserver`) plus every touch/button event, so the
 *  old chain's per-call `ControllerState` + touch-array churn was real, avoidable GC pressure on
 *  the input-latency path. A standalone top-level function (rather than inline in the getter) so
 *  it's directly unit-testable without needing an Android [android.content.Context].
 *
 *  Derived field-by-field from [ControllerState.or]'s own semantics:
 *  - buttons/l2State/r2State/leftX/Y/rightX/Y: [ControllerState.or] merges these the same way
 *    (bitwise-or, max, max-by-magnitude) across ALL FOUR sources, including [touch] — which is
 *    not just touchpad taps: StreamActivity's on-screen controls fragment assigns its entire
 *    ControllerState (face buttons, D-pad, L1/R1, sticks, L2/R2) wholesale into
 *    `StreamInput.touchControllerState`, so it's a full input source in its own right, not merely
 *    a touch-position carrier. l2State/r2State also get the same explicit "motion wins outright
 *    if it has any analog value at all, even over a harder-pressed digital/key mapping" override
 *    the old code applied mid-chain (before folding in touch), preserved in the same position
 *    below.
 *  - gyro/accel/orient: [ControllerState.or] always keeps its LEFT operand's values for these
 *    (see its source), and the old chain always put [sensor] first — the only one of the four
 *    that ever sets them — so they always end up being [sensor]'s raw values untouched by the
 *    other three, same as read directly here.
 *  - touches: only [touch] ever has an active (id >= 0) touch — sensor/key/motion keep the
 *    default all-inert (id -1) pair for their entire lifetime — so the old chain's touches merge
 *    always bottomed out at [touch]'s touches regardless of the other three, same as read
 *    directly here.
 *
 *  [rotation] flips accel/gyro/orient X/Z the same way the old code did for [Surface.ROTATION_90]
 *  — see [StreamInput.cachedRotation]'s doc for why a caller-supplied value rather than querying
 *  the display directly here. */
internal fun mergeControllerStates(
	sensor: ControllerState,
	key: ControllerState,
	motion: ControllerState,
	touch: ControllerState,
	rotation: Int
): ControllerState
{
	var gyroX = sensor.gyroX
	var gyroY = sensor.gyroY
	var gyroZ = sensor.gyroZ
	var accelX = sensor.accelX
	var accelY = sensor.accelY
	var accelZ = sensor.accelZ
	var orientX = sensor.orientX
	val orientY = sensor.orientY
	var orientZ = sensor.orientZ
	val orientW = sensor.orientW

	if(rotation == Surface.ROTATION_90)
	{
		accelX *= -1.0f; accelZ *= -1.0f
		gyroX *= -1.0f; gyroZ *= -1.0f
		orientX *= -1.0f; orientZ *= -1.0f
	}

	var l2State = maxOf(maxOf(sensor.l2State, key.l2State), motion.l2State)
	var r2State = maxOf(maxOf(sensor.r2State, key.r2State), motion.r2State)
	if(motion.l2State > 0U) l2State = motion.l2State
	if(motion.r2State > 0U) r2State = motion.r2State
	l2State = maxOf(l2State, touch.l2State)
	r2State = maxOf(r2State, touch.r2State)

	val srcTouches = touch.touches
	val touches = arrayOf(
		if(srcTouches[0].id >= 0) srcTouches[0] else ControllerTouch(),
		if(srcTouches[1].id >= 0) srcTouches[1] else ControllerTouch()
	)

	return ControllerState(
		buttons = sensor.buttons or key.buttons or motion.buttons or touch.buttons,
		l2State = l2State,
		r2State = r2State,
		leftX = maxAbs(maxAbs(maxAbs(sensor.leftX, key.leftX), motion.leftX), touch.leftX),
		leftY = maxAbs(maxAbs(maxAbs(sensor.leftY, key.leftY), motion.leftY), touch.leftY),
		rightX = maxAbs(maxAbs(maxAbs(sensor.rightX, key.rightX), motion.rightX), touch.rightX),
		rightY = maxAbs(maxAbs(maxAbs(sensor.rightY, key.rightY), motion.rightY), touch.rightY),
		touches = touches,
		gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ,
		accelX = accelX, accelY = accelY, accelZ = accelZ,
		orientX = orientX, orientY = orientY, orientZ = orientZ, orientW = orientW
	)
}
