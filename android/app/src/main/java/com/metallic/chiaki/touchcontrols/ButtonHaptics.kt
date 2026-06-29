package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import com.metallic.chiaki.common.Preferences

class ButtonHaptics(val context: Context)
{
	private val enabled = Preferences(context).buttonHapticEnabled

	fun trigger(view: View, harder: Boolean = false)
	{
		if(!enabled)
			return
		val constant = if(harder) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.KEYBOARD_TAP
		view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
	}
}
