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
		{
			// Ramp up then down to avoid the abrupt start/stop click from the motor
			if (harder)
				vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 15, 60, 15), intArrayOf(0, 60, 140, 0), -1))
			else
				vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 10, 40, 10), intArrayOf(0, 40, 90, 0), -1))
		}
		else
			@Suppress("DEPRECATION")
			vibrator.vibrate(if (harder) 90 else 60)
	}
}
