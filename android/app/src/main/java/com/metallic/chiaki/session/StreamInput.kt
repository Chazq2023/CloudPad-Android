package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState

class StreamInput(
	val context: Context,
	val preferences: Preferences,
	val isRemotePlay: Boolean = false
) {
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return controllerState or touchControllerState
	}

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

	private val activeMapping: Map<ControllerAction, PhysicalInput> = run {
		val saved = preferences.loadControllerMapping()
		if(saved.isEmpty()) PhysicalInput.DEFAULT_MAPPING else saved
	}

	private val singleKeyToActions: Map<Int, List<ControllerAction>> =
		activeMapping.entries
			.filter { it.value is PhysicalInput.Button }
			.groupBy(
				keySelector = { (it.value as PhysicalInput.Button).keyCode },
				valueTransform = { it.key }
			)

	private val singleAxisMappings: List<Triple<ControllerAction, Int, Boolean>> =
		activeMapping.entries
			.filter { it.value is PhysicalInput.AxisDirection }
			.map { val ax = it.value as PhysicalInput.AxisDirection; Triple(it.key, ax.axis, ax.positive) }

	data class ComboEntry(val modifierKeyCode: Int, val trigger: PhysicalInput, val action: ControllerAction)

	private val comboEntries: List<ComboEntry> =
		activeMapping.entries
			.filter { it.value is PhysicalInput.Combo }
			.map { (action, input) ->
				val combo = input as PhysicalInput.Combo
				ComboEntry(combo.modifierKeyCode, combo.trigger, action)
			}

	private val comboModifierKeyCodes: Set<Int> = comboEntries.map { it.modifierKeyCode }.toSet()

	// ---- Combo runtime state ----

	private val heldModifiers = mutableMapOf<Int, Boolean>()
	private val activeComboActions = mutableMapOf<ControllerAction, Int>()
	private val triggeredComboAxes = mutableSetOf<Pair<Int, Boolean>>()
	// Last-known values for axes used as combo triggers — used to ignore axes that were
	// already above threshold when the modifier key was pressed (e.g. L2 drift at rest).
	private val lastAxisValues = mutableMapOf<Int, Float>()

	// ---- Sensor / lifecycle ----

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
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	// ---- Touchpad gestures ----

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
		ControllerAction.HOME -> if(isRemotePlay) ControllerState.BUTTON_PS else null
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

		// Update last-known axis values for combo edge detection
		for(combo in comboEntries)
		{
			if(combo.trigger is PhysicalInput.AxisDirection)
				lastAxisValues[combo.trigger.axis] = event.getAxisValue(combo.trigger.axis)
		}

		// Combo axis triggers (modifier held + axis movement)
		if(heldModifiers.isNotEmpty())
		{
			for(combo in comboEntries)
			{
				if(combo.trigger !is PhysicalInput.AxisDirection) continue
				if(combo.modifierKeyCode !in heldModifiers) continue
				val rawValue = event.getAxisValue(combo.trigger.axis)
				val dirValue = if(combo.trigger.positive) maxOf(0f, rawValue) else maxOf(0f, -rawValue)
				if(dirValue > 0.5f)
				{
					val triggerKey = combo.trigger.axis to combo.trigger.positive
					if(triggerKey !in triggeredComboAxes)
					{
						// First time this axis crosses the threshold — fire the combo once
						heldModifiers[combo.modifierKeyCode] = true
						triggeredComboAxes.add(triggerKey)
						pressAction(combo.action)
						return true
					}
					// Already triggered — let normal axis processing continue (axis is
					// excluded from it via triggeredComboAxes, so no double-processing)
				}
			}
		}

		// Normal axis processing (skip axes claimed by an active combo)
		var leftX = 0f; var leftY = 0f; var rightX = 0f; var rightY = 0f
		var l2 = 0f; var r2 = 0f; var dpadX = 0f; var dpadY = 0f

		for((action, axis, positive) in singleAxisMappings)
		{
			if((axis to positive) in triggeredComboAxes) continue
			val rawValue = event.getAxisValue(axis)
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
		motionControllerState.l2State = l2.coerceIn(0f, 1f).unsignedAxis()
		motionControllerState.r2State = r2.coerceIn(0f, 1f).unsignedAxis()

		controllerStateUpdated()
		return true
	}
}
