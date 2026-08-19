// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import androidx.annotation.StringRes
import com.pylux.stream.R

enum class TouchControl(@StringRes val labelRes: Int)
{
	DPAD(R.string.touch_control_dpad),
	LEFT_STICK(R.string.touch_control_left_stick),
	RIGHT_STICK(R.string.touch_control_right_stick),
	TOUCHPAD(R.string.touch_control_touchpad),
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
	PS(R.string.touch_control_ps)
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
		const val DEFAULT_PERCENT = 100
		const val MIN_SIZE_PERCENT = 50
		const val MAX_SIZE_PERCENT = 150
		const val MIN_OPACITY_PERCENT = 10
		const val MAX_OPACITY_PERCENT = 100
	}
}
