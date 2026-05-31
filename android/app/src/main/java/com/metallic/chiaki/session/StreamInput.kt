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
	private val mapSelectToTouchpad: Boolean = false
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

	private var selectHeld = false
	private var selectUsedForSwipe = false
	private var pendingSelectPress = false
	private var lastDpadSwipeDirection: Int? = null
	private var suppressNextSelectReleasePress = false

	private val selectPressRunnable = Runnable {
		if(mapSelectToTouchpad && selectHeld && !selectUsedForSwipe && pendingSelectPress)
		{
			keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
			pendingSelectPress = false
			controllerStateUpdated()
		}
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

	private fun quickTouchpadSwipe(direction: Int)
	{
		handler.removeCallbacks(selectPressRunnable)
		pendingSelectPress = false
		selectUsedForSwipe = true
		suppressNextSelectReleasePress = true

		keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
		controllerStateUpdated()

		val startX = 960U.toUShort()
		val startY = 471U.toUShort()

		val endX: UShort
		val endY: UShort

		when(direction)
		{
			KeyEvent.KEYCODE_DPAD_UP -> {
				endX = startX
				endY = 120U.toUShort()
			}
			KeyEvent.KEYCODE_DPAD_DOWN -> {
				endX = startX
				endY = 820U.toUShort()
			}
			KeyEvent.KEYCODE_DPAD_LEFT -> {
				endX = 250U.toUShort()
				endY = startY
			}
			KeyEvent.KEYCODE_DPAD_RIGHT -> {
				endX = 1670U.toUShort()
				endY = startY
			}
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

	private fun handleDpadSwipeFromHat(dpadX: Float, dpadY: Float): Boolean
	{
		if(!mapSelectToTouchpad || !selectHeld)
			return false

		val direction = when
		{
			dpadY < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
			dpadY > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
			dpadX < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
			dpadX > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
			else -> {
				lastDpadSwipeDirection = null

				if(selectUsedForSwipe)
					suppressNextSelectReleasePress = true

				return false
			}
		}

		if(lastDpadSwipeDirection == direction)
			return true

		lastDpadSwipeDirection = direction
		selectUsedForSwipe = true
		quickTouchpadSwipe(direction)
		return true
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		if(mapSelectToTouchpad && selectHeld && event.action == KeyEvent.ACTION_DOWN)
		{
			when(event.keyCode)
			{
				KeyEvent.KEYCODE_DPAD_UP,
				KeyEvent.KEYCODE_DPAD_DOWN,
				KeyEvent.KEYCODE_DPAD_LEFT,
				KeyEvent.KEYCODE_DPAD_RIGHT -> {
					selectUsedForSwipe = true
					quickTouchpadSwipe(event.keyCode)
					return true
				}
			}
		}

		when(event.keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_L2 -> {
				keyControllerState.l2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
			KeyEvent.KEYCODE_BUTTON_R2 -> {
				keyControllerState.r2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
		}

		val buttonMask: UInt = when(event.keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
			KeyEvent.KEYCODE_BUTTON_X -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
			KeyEvent.KEYCODE_BUTTON_Y -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
			KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
			KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
			KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
			KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3

			KeyEvent.KEYCODE_BUTTON_SELECT -> {
				if(mapSelectToTouchpad)
				{
					if(event.action == KeyEvent.ACTION_DOWN)
					{
						selectHeld = true
						selectUsedForSwipe = false
						suppressNextSelectReleasePress = false
						pendingSelectPress = false
						lastDpadSwipeDirection = null

						handler.removeCallbacks(selectPressRunnable)
					}
					else
					{
						handler.removeCallbacks(selectPressRunnable)

						if(suppressNextSelectReleasePress)
						{
							touchControllerState = ControllerState()

							selectHeld = false
							selectUsedForSwipe = false
							suppressNextSelectReleasePress = false
							pendingSelectPress = false
							lastDpadSwipeDirection = null

							controllerStateUpdated()
							return true
						}

						keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
						controllerStateUpdated()

						handler.postDelayed({
							keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
							controllerStateUpdated()
						}, 80)

						touchControllerState = ControllerState()

						selectHeld = false
						selectUsedForSwipe = false
						suppressNextSelectReleasePress = false
						pendingSelectPress = false
						lastDpadSwipeDirection = null

						controllerStateUpdated()
					}

					return true
				}
				else
				{
					ControllerState.BUTTON_SHARE
				}
			}

			KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
			KeyEvent.KEYCODE_BUTTON_C -> ControllerState.BUTTON_PS
			KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
			else -> return false
		}

		keyControllerState.buttons = keyControllerState.buttons.run {
			when(event.action)
			{
				KeyEvent.ACTION_DOWN -> this or buttonMask
				KeyEvent.ACTION_UP -> this and buttonMask.inv()
				else -> this
			}
		}

		controllerStateUpdated()
		return true
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false

		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()

		motionControllerState.leftX = event.getAxisValue(MotionEvent.AXIS_X).signedAxis()
		motionControllerState.leftY = event.getAxisValue(MotionEvent.AXIS_Y).signedAxis()
		motionControllerState.rightX = event.getAxisValue(MotionEvent.AXIS_Z).signedAxis()
		motionControllerState.rightY = event.getAxisValue(MotionEvent.AXIS_RZ).signedAxis()
		motionControllerState.l2State = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).unsignedAxis()
		motionControllerState.r2State = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).unsignedAxis()

		val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
		val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

		if(handleDpadSwipeFromHat(dpadX, dpadY))
			return true

		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)

			it and (
					ControllerState.BUTTON_DPAD_RIGHT or
							ControllerState.BUTTON_DPAD_LEFT or
							ControllerState.BUTTON_DPAD_DOWN or
							ControllerState.BUTTON_DPAD_UP
					).inv() or dpadButtons
		}

		controllerStateUpdated()
		return true
	}
}