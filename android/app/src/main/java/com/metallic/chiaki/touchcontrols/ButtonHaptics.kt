package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.metallic.chiaki.common.Preferences

class ButtonHaptics(val context: Context)
{
	private val preferences = Preferences(context)

	fun trigger(harder: Boolean = false)
	{
		if (!preferences.buttonHapticEnabled) return

		val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			(context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
		else
			@Suppress("DEPRECATION")
			context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

		if (vibrator == null || !vibrator.hasVibrator())
		{
			Log.w("ButtonHaptics", "No vibrator available")
			return
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
		{
			val effect = if (harder) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
			vibrator.vibrate(VibrationEffect.createPredefined(effect))
		}
		else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			vibrator.vibrate(VibrationEffect.createOneShot(20, if (harder) 220 else 160))
		else
			@Suppress("DEPRECATION")
			vibrator.vibrate(20)
	}
}
