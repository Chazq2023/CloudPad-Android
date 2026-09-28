// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import androidx.annotation.StringRes
import com.pylux.stream.R

/**
 * @param alwaysShowGatesStyle the control is invisible until touched unless "Always visible"
 *   is on, so its size/transparency sliders are hidden and ignored while it's off.
 */
enum class TouchControl(
	@StringRes val labelRes: Int,
	val defaultAlwaysShow: Boolean = false,
	val alwaysShowGatesStyle: Boolean = false
)
{
	DPAD(R.string.touch_control_dpad),
	LEFT_STICK(R.string.touch_control_left_stick, defaultAlwaysShow = true),
	RIGHT_STICK(R.string.touch_control_right_stick, defaultAlwaysShow = true),
	TOUCHPAD(R.string.touch_control_touchpad, alwaysShowGatesStyle = true),
	CROSS(R.string.touch_control_cross),
	CIRCLE(R.string.touch_control_circle),
	TRIANGLE(R.string.touch_control_triangle),
	SQUARE(R.string.touch_control_square),
	L1(R.string.touch_control_l1),
	L2(R.string.touch_control_l2),
	L3(R.string.touch_control_l3),
	R1(R.string.touch_control_r1),
	R2(R.string.touch_control_r2),
	R3(R.string.touch_control_r3),
	SHARE(R.string.touch_control_share),
	OPTIONS(R.string.touch_control_options),
	PS(R.string.touch_control_ps);

	val hasAlwaysShowOption get() = this == LEFT_STICK || this == RIGHT_STICK || alwaysShowGatesStyle

	@get:StringRes
	val alwaysShowLabelRes get() =
		if(alwaysShowGatesStyle) R.string.touch_controls_always_visible else R.string.touch_controls_always_show
}

data class TouchControlStyle(
	val sizePercent: Int,
	val opacityPercent: Int,
	val offsetXPermille: Int = 0,
	val offsetYPermille: Int = 0,
	val alwaysShow: Boolean = false
)
{
	companion object
	{
		const val DEFAULT_SIZE_PERCENT = 100
		const val DEFAULT_OPACITY_PERCENT = 50
		const val MIN_SIZE_PERCENT = 50
		const val MAX_SIZE_PERCENT = 150
		const val MIN_OPACITY_PERCENT = 10
		const val MAX_OPACITY_PERCENT = 100
	}
}

/** The style to actually render [control] with: for controls whose size/transparency only
 *  apply while "Always visible" is on, fall back to the defaults while it's off. The saved
 *  slider values are kept, so turning it back on restores them. */
fun TouchControlStyle.effectiveFor(control: TouchControl): TouchControlStyle =
	if(control.alwaysShowGatesStyle && !alwaysShow)
		copy(sizePercent = TouchControlStyle.DEFAULT_SIZE_PERCENT, opacityPercent = TouchControlStyle.DEFAULT_OPACITY_PERCENT)
	else
		this
