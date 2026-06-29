package com.metallic.chiaki.touchcontrols

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.metallic.chiaki.common.Preferences

class ButtonHaptics(val view: View)
{
	private val preferences = Preferences(view.context)

	fun trigger(harder: Boolean = false)
	{
		if (!preferences.buttonHapticEnabled) return
		val constant = when
		{
			harder -> HapticFeedbackConstants.LONG_PRESS
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 -> HapticFeedbackConstants.KEYBOARD_PRESS
			else -> HapticFeedbackConstants.VIRTUAL_KEY
		}
		view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
	}
}
