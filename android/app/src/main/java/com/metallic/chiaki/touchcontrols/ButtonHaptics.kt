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

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			vibrator.vibrate(VibrationEffect.createOneShot(if (harder) 90 else 60, 120))
		else
			@Suppress("DEPRECATION")
			vibrator.vibrate(if (harder) 90 else 60)
	}
}
