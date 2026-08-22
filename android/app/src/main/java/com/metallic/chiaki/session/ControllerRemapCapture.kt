// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import com.pylux.stream.R
import kotlin.math.abs

/**
 * Shared "listen for the next controller input and bind it to an action" capture logic,
 * used by both the full-screen Settings > Remap Controller screen and the in-stream
 * Quick Settings panel's embedded remap list.
 */
class ControllerRemapCapture(
	private val context: Context,
	private val onInputDetected: (ControllerAction, PhysicalInput) -> Unit,
	private val onCleared: (ControllerAction) -> Unit,
	private val onDialogClosed: () -> Unit = {}
) {
	var listeningForAction: ControllerAction? = null
		private set
	private var listenDialog: AlertDialog? = null
	private var captureModifier: Int? = null

	val isListening: Boolean get() = listeningForAction != null

	companion object {
		/**
		 * Actions that must always be captured as a plain analog axis. Many controllers emit
		 * a synthetic digital KeyEvent (e.g. KEYCODE_BUTTON_L2/R2, or a DPAD-emulation keycode
		 * for stick tilts) alongside the analog MotionEvent for the very same physical trigger
		 * or stick push. Without this, that stray KeyEvent gets recorded as a "modifier" and the
		 * following MotionEvent gets combined into a bogus Combo instead of a clean AxisDirection.
		 */
		private val analogOnlyActions = setOf(
			ControllerAction.L2, ControllerAction.R2,
			ControllerAction.LEFT_STICK_UP, ControllerAction.LEFT_STICK_DOWN,
			ControllerAction.LEFT_STICK_LEFT, ControllerAction.LEFT_STICK_RIGHT,
			ControllerAction.RIGHT_STICK_UP, ControllerAction.RIGHT_STICK_DOWN,
			ControllerAction.RIGHT_STICK_LEFT, ControllerAction.RIGHT_STICK_RIGHT
		)
	}

	private inner class InputCaptureDialog : AlertDialog(context) {
		override fun dispatchKeyEvent(event: KeyEvent): Boolean {
			if (listeningForAction != null && handleCaptureKeyEvent(event)) return true
			return super.dispatchKeyEvent(event)
		}

		override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
			if (listeningForAction != null && handleCaptureMotionEvent(event)) return true
			return super.dispatchGenericMotionEvent(event)
		}
	}

	fun startListeningFor(action: ControllerAction) {
		listeningForAction = action
		captureModifier = null

		val dialog = InputCaptureDialog()
		// Subclassing AlertDialog directly (needed to override dispatchKeyEvent/
		// dispatchGenericMotionEvent for capture) bypasses AppAlertDialogBuilder's themed
		// background — applying the same drawable it uses so this still matches every other
		// dialog in the app instead of falling back to the platform default.
		dialog.window?.setBackgroundDrawableResource(R.drawable.bg_disclaimer_box)
		dialog.setTitle(action.displayName)
		dialog.setMessage(context.getString(R.string.controller_remap_press_button))
		dialog.setButton(AlertDialog.BUTTON_NEGATIVE, context.getString(R.string.action_cancel)) { _, _ ->
			cancelListening()
		}
		dialog.setButton(AlertDialog.BUTTON_NEUTRAL, context.getString(R.string.controller_remap_clear)) { _, _ ->
			onCleared(action)
			cancelListening()
		}
		dialog.setCancelable(false)
		listenDialog = dialog
		dialog.show()
	}

	fun handleCaptureKeyEvent(event: KeyEvent): Boolean {
		// Ignore held-key repeat events
		if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true

		val ignoredKeyCodes = setOf(
			KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
			KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU
		)
		if (event.keyCode in ignoredKeyCodes) {
			if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
				listenDialog?.dismiss()
				cancelListening()
			}
			return true
		}

		// Analog-only actions (triggers, stick tilts) must never treat a KeyEvent as a
		// modifier/trigger — only the corresponding MotionEvent axis should be captured.
		if (listeningForAction in analogOnlyActions) return true

		when (event.action) {
			KeyEvent.ACTION_DOWN -> {
				val mod = captureModifier
				when {
					mod == null -> {
						// First key down — record as potential modifier
						captureModifier = event.keyCode
						listenDialog?.setMessage(
							context.getString(
								R.string.controller_remap_modifier_held,
								PhysicalInput.formatKeyCode(event.keyCode)
							)
						)
					}
					mod != event.keyCode -> {
						// Second different key while first still held — save as combo
						handleDetected(PhysicalInput.Combo(mod, PhysicalInput.Button(event.keyCode)))
					}
				}
			}
			KeyEvent.ACTION_UP -> {
				val mod = captureModifier
				if (mod != null && mod == event.keyCode) {
					// Modifier released without a second input — save as single button
					handleDetected(PhysicalInput.Button(mod))
				}
			}
		}
		return true
	}

	fun handleCaptureMotionEvent(event: MotionEvent): Boolean {
		if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false

		val axes = listOf(
			MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
			MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
			// L2/R2 travel is reported via AXIS_LTRIGGER/AXIS_RTRIGGER on Xbox-style pads,
			// but via AXIS_BRAKE/AXIS_GAS on DualShock/DualSense pads — scan both so capture
			// works regardless of which axis the connected controller actually populates.
			MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
			MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
			MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
		)
		for (axis in axes) {
			val value = event.getAxisValue(axis)
			if (abs(value) > 0.8f) {
				val axisInput = PhysicalInput.AxisDirection(axis, value > 0)
				val mod = captureModifier
				handleDetected(if (mod != null) PhysicalInput.Combo(mod, axisInput) else axisInput)
				return true
			}
		}
		return false
	}

	private fun handleDetected(input: PhysicalInput) {
		val action = listeningForAction ?: return
		listenDialog?.dismiss()
		listenDialog = null
		listeningForAction = null
		captureModifier = null

		onInputDetected(action, input)
		onDialogClosed()
	}

	fun cancelListening() {
		listeningForAction = null
		captureModifier = null
		listenDialog = null
		onDialogClosed()
	}
}
