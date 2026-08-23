// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.os.Bundle
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.applyFocusHighlight
import com.google.android.material.button.MaterialButton
import com.pylux.stream.R
import com.pylux.stream.databinding.FragmentControlsBinding
import com.metallic.chiaki.lib.ControllerState
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables.combineLatest
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject

abstract class TouchControlsFragment : Fragment()
{
	protected var ownControllerState = ControllerState()
		set(value)
		{
			val diff = field != value
			field = value
			if(diff)
				ownControllerStateSubject.onNext(ownControllerState)
		}

	protected val ownControllerStateSubject: Subject<ControllerState>
			= BehaviorSubject.create<ControllerState>().also { it.onNext(ownControllerState) }

	// to delay attaching to the touchpadView until it's available
	protected val controllerStateProxy: Subject<Observable<ControllerState>>
			= BehaviorSubject.create<Observable<ControllerState>>().also { it.onNext(ownControllerStateSubject) }
	val controllerState: Observable<ControllerState> get() =
		controllerStateProxy.flatMap { it }

	var onScreenControlsEnabled: LiveData<Boolean>? = null
}

class DefaultTouchControlsFragment : TouchControlsFragment()
{
	private var _binding: FragmentControlsBinding? = null
	private val binding get() = _binding!!

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		FragmentControlsBinding.inflate(inflater, container, false).let {
			_binding = it
			controllerStateProxy.onNext(
				combineLatest(ownControllerStateSubject, binding.touchpadView.controllerState) { a, b -> a or b }
			)
			it.root
		}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?)
	{
		super.onViewCreated(view, savedInstanceState)
		applyCustomization()
		binding.dpadView.stateChangeCallback = this::dpadStateChanged
		binding.crossButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_CROSS)
		binding.moonButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_MOON)
		binding.pyramidButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PYRAMID)
		binding.boxButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_BOX)
		binding.l1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L1)
		binding.r1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R1)
		binding.l3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L3)
		binding.r3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R3)
		binding.optionsButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_OPTIONS)
		binding.shareButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_SHARE)
		binding.psButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PS)

		binding.l2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { l2State = if(it) 255U else 0U } }
		binding.r2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { r2State = if(it) 255U else 0U } }

		val quantizeStick = { f: Float ->
			(Short.MAX_VALUE * f).toInt().toShort()
		}

		binding.leftAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			leftX = quantizeStick(it.x)
			leftY = quantizeStick(it.y)
		}}

		binding.rightAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			rightX = quantizeStick(it.x)
			rightY = quantizeStick(it.y)
		}}

		onScreenControlsEnabled?.observe(viewLifecycleOwner, Observer {
			view.visibility = if(it) View.VISIBLE else View.GONE
		})
	}

	fun applyCustomization()
	{
		if(_binding == null) return
		val preferences = Preferences(requireContext())
		val views = controlViews()
		binding.faceButtonsLayout.clipChildren = false
		binding.faceButtonsLayout.clipToPadding = false
		val controlsRoot = binding.root
		views.forEach { (control, controlView) ->
			val style = preferences.touchControlStyle(control)
			val scale = style.sizePercent / 100f
			controlView.scaleX = scale
			controlView.scaleY = scale
			controlView.alpha = style.opacityPercent / 100f
			controlView.post {
				controlView.translationX = controlsRoot.width * style.offsetXPermille / 1000f
				controlView.translationY = controlsRoot.height * style.offsetYPermille / 1000f
			}
		}
		binding.leftAnalogStickView.alwaysShow = preferences.touchControlStyle(TouchControl.LEFT_STICK).alwaysShow
		binding.rightAnalogStickView.alwaysShow = preferences.touchControlStyle(TouchControl.RIGHT_STICK).alwaysShow
	}

	private fun controlViews() = mapOf(
			TouchControl.DPAD to binding.dpadView,
			TouchControl.LEFT_STICK to binding.leftAnalogStickView,
			TouchControl.RIGHT_STICK to binding.rightAnalogStickView,
			TouchControl.TOUCHPAD to binding.touchpadView,
			TouchControl.CROSS to binding.crossButtonView,
			TouchControl.CIRCLE to binding.moonButtonView,
			TouchControl.TRIANGLE to binding.pyramidButtonView,
			TouchControl.SQUARE to binding.boxButtonView,
			TouchControl.L1 to binding.l1ButtonView,
			TouchControl.L2 to binding.l2ButtonView,
			TouchControl.L3 to binding.l3ButtonView,
			TouchControl.R1 to binding.r1ButtonView,
			TouchControl.R2 to binding.r2ButtonView,
			TouchControl.R3 to binding.r3ButtonView,
			TouchControl.SHARE to binding.shareButtonView,
			TouchControl.OPTIONS to binding.optionsButtonView,
			TouchControl.PS to binding.psButtonView
		)

	fun setCustomizationPanelVisible(visible: Boolean)
	{
		if(_binding == null) return
		controlViews().values.forEach { it.isEnabled = !visible }
	}

	/** True for the duration of [startMoveMode] — StreamActivity's dispatchKeyEvent/
	 *  onGenericMotionEvent check this to stop controller input from reaching StreamInput (which
	 *  would otherwise consume the D-pad as gameplay input before it ever reaches the Save
	 *  button), the same reasoning as
	 *  [com.metallic.chiaki.stream.StreamActivity.overlayMoveModeActive]. */
	var isMoveModeActive = false
		private set

	/** The Save button while [isMoveModeActive] — StreamActivity routes D-pad/controller input
	 *  here instead of letting Android's ordinary focus search run, which would otherwise land on
	 *  the on-screen control buttons/joysticks underneath (real focusable Views the rest of the
	 *  time, confirmed on-device) instead of staying on the only thing move mode should ever let
	 *  you reach. Null outside of move mode. */
	var moveModeConfirmButton: View? = null
		private set

	fun startMoveMode(control: TouchControl, onSaved: () -> Unit)
	{
		if(_binding == null) return
		isMoveModeActive = true
		val activity = requireActivity()
		val streamRoot = activity.findViewById<ViewGroup>(R.id.mainStreamLayout)
		val controlView = controlViews().getValue(control)
		var downRawX = 0f
		var downRawY = 0f
		var startTranslationX = 0f
		var startTranslationY = 0f
		controlView.setOnTouchListener { _, event ->
			when(event.actionMasked)
			{
				MotionEvent.ACTION_DOWN ->
				{
					downRawX = event.rawX
					downRawY = event.rawY
					startTranslationX = controlView.translationX
					startTranslationY = controlView.translationY
					true
				}
				MotionEvent.ACTION_MOVE ->
				{
					controlView.translationX = startTranslationX + event.rawX - downRawX
					controlView.translationY = startTranslationY + event.rawY - downRawY
					true
				}
				else -> true
			}
		}

		val accent = TypedValue().let {
			activity.theme.resolveAttribute(R.attr.pyluxAccent, it, true)
			it.data
		}
		val saveButton = MaterialButton(activity).apply {
			text = activity.getString(R.string.touch_controls_save)
			setTextColor(android.graphics.Color.WHITE)
			backgroundTintList = ColorStateList.valueOf(accent)
			// Move mode starts from a touch tap, so Android is still in touch mode and a HAT-axis
			// D-pad never leaves it — isFocusableInTouchMode is required or requestFocus() below
			// silently fails (same requirement noted for the touch-controls customization popup's
			// own destinations).
			isFocusable = true
			isFocusableInTouchMode = true
			applyFocusHighlight(accent, useForeground = true)
		}
		streamRoot.addView(saveButton, android.widget.FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
			Gravity.CENTER
		))
		moveModeConfirmButton = saveButton
		// dispatchKeyEvent/onGenericMotionEvent route controller input straight to StreamInput
		// while isMoveModeActive is false, so without an explicit focus grant here a D-pad press
		// would never reach Save at all — see isMoveModeActive's use in StreamActivity.
		saveButton.post { saveButton.requestFocus() }
		saveButton.setOnClickListener {
			val preferences = Preferences(activity)
			val style = preferences.touchControlStyle(control)
			preferences.setTouchControlStyle(
				control,
				style.copy(
					offsetXPermille = if(streamRoot.width == 0) 0 else (controlView.translationX * 1000 / streamRoot.width).toInt(),
					offsetYPermille = if(streamRoot.height == 0) 0 else (controlView.translationY * 1000 / streamRoot.height).toInt()
				)
			)
			controlView.setOnTouchListener(null)
			streamRoot.removeView(saveButton)
			isMoveModeActive = false
			moveModeConfirmButton = null
			onSaved()
		}
	}

	private fun dpadStateChanged(direction: DPadView.Direction?)
	{
		ownControllerState = ownControllerState.copy().apply {
			buttons = ((buttons
						and ControllerState.BUTTON_DPAD_LEFT.inv()
						and ControllerState.BUTTON_DPAD_RIGHT.inv()
						and ControllerState.BUTTON_DPAD_UP.inv()
						and ControllerState.BUTTON_DPAD_DOWN.inv())
					or when(direction)
					{
						DPadView.Direction.UP -> ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.DOWN -> ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.LEFT -> ControllerState.BUTTON_DPAD_LEFT
						DPadView.Direction.RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
						DPadView.Direction.LEFT_UP -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.LEFT_DOWN -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.RIGHT_UP -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.RIGHT_DOWN -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_DOWN
						null -> 0U
					})
		}
	}

	private fun buttonStateChanged(buttonMask: UInt) = { pressed: Boolean ->
		ownControllerState = ownControllerState.copy().apply {
			buttons =
				if(pressed)
					buttons or buttonMask
				else
					buttons and buttonMask.inv()

		}
	}
}
