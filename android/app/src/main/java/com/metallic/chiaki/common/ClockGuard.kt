// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.metallic.chiaki.cloudplay.api.HttpClient
import com.metallic.chiaki.cloudplay.api.TrustedClock
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.pylux.stream.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.TimeZone
import kotlin.math.abs

/**
 * Stops CloudPad being used with a manually changed device date. When automatic date & time is
 * off and the device's date differs from the real date (Sony's clock, or Android's network time),
 * a popup asks the user to set it back and the app closes. Checked on every activity resume and
 * whenever the system reports the clock changed, so it catches changes made while the app is open
 * as well as at launch. The pre-order release gate doesn't depend on this (it uses Sony's clock
 * directly); this is about refusing to run at all on a tampered clock.
 */
object ClockGuard
{
	private const val TAG = "ClockGuard"

	/** Normal clock drift around midnight mustn't count as a changed date. */
	const val TOLERANCE_MS = 5L * 60 * 1000

	private const val DAY_MS = 24L * 60 * 60 * 1000

	/** Fetched just to read Sony's Date header when no Sony response has been seen yet. */
	private const val TIME_PROBE_URL = "https://www.playstation.com/"

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var resumedActivity: Activity? = null
	private var dialog: AlertDialog? = null
	private var probedThisProcess = false

	enum class Verdict { OK, WRONG_DATE, UNKNOWN }

	/** Calendar day number of [ms] in [tz] — so "the same date" means the same in any country. */
	fun localDay(ms: Long, tz: TimeZone): Long = Math.floorDiv(ms + tz.getOffset(ms), DAY_MS)

	fun isDeviceDateWrong(deviceMs: Long, trustedMs: Long, tz: TimeZone): Boolean =
		abs(deviceMs - trustedMs) > TOLERANCE_MS && localDay(deviceMs, tz) != localDay(trustedMs, tz)

	/** TLS failing because a certificate looks expired/not-yet-valid is the device clock talking. */
	fun isCertificateDateFailure(e: Throwable): Boolean
	{
		var t: Throwable? = e
		while (t != null)
		{
			if (t is CertificateExpiredException || t is CertificateNotYetValidException) return true
			if (t is CertPathValidatorException && (t.reason == CertPathValidatorException.BasicReason.EXPIRED ||
					t.reason == CertPathValidatorException.BasicReason.NOT_YET_VALID)) return true
			t = t.cause
		}
		return false
	}

	fun install(app: Application)
	{
		app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks
		{
			override fun onActivityResumed(activity: Activity)
			{
				resumedActivity = activity
				check(activity.applicationContext)
			}
			override fun onActivityPaused(activity: Activity) { if (resumedActivity === activity) resumedActivity = null }
			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
			override fun onActivityStarted(activity: Activity) {}
			override fun onActivityStopped(activity: Activity) {}
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
			override fun onActivityDestroyed(activity: Activity) {}
		})

		val filter = IntentFilter().apply {
			addAction(Intent.ACTION_TIME_CHANGED)
			addAction(Intent.ACTION_DATE_CHANGED)
			addAction(Intent.ACTION_TIMEZONE_CHANGED)
		}
		val receiver = object : BroadcastReceiver()
		{
			override fun onReceive(context: Context, intent: Intent) = check(context.applicationContext)
		}
		// System-protected broadcasts, so exporting the receiver doesn't open it to other apps.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
		else
			app.registerReceiver(receiver, filter)
	}

	private fun isAutoTimeOn(context: Context): Boolean =
		Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 1

	private fun networkTimeOrNull(): Long?
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
		return try { SystemClock.currentNetworkTimeClock().millis() } catch (e: Exception) { null }
	}

	private fun check(context: Context)
	{
		if (isAutoTimeOn(context)) return
		scope.launch {
			val verdict = verdict()
			Log.i(TAG, "Clock check: $verdict")
			if (verdict == Verdict.WRONG_DATE) showWrongDateDialog()
		}
	}

	private suspend fun verdict(): Verdict
	{
		val trusted = TrustedClock.nowOrNull() ?: networkTimeOrNull() ?: run {
			if (probedThisProcess) return Verdict.UNKNOWN
			probedThisProcess = true
			try
			{
				withContext(Dispatchers.IO) { HttpClient.get(TIME_PROBE_URL, followRedirects = false, timeoutMs = 5000) }
				TrustedClock.nowOrNull()
			}
			catch (e: Exception)
			{
				if (isCertificateDateFailure(e)) return Verdict.WRONG_DATE
				Log.w(TAG, "Couldn't get a trusted time", e)
				null
			}
		} ?: return Verdict.UNKNOWN
		return if (isDeviceDateWrong(System.currentTimeMillis(), trusted, TimeZone.getDefault())) Verdict.WRONG_DATE else Verdict.OK
	}

	private fun showWrongDateDialog()
	{
		val activity = resumedActivity ?: return
		if (dialog?.isShowing == true || activity.isFinishing) return
		dialog = activity.alertDialogBuilder()
			.setTitle(R.string.clock_wrong_date_title)
			.setMessage(R.string.clock_wrong_date_message)
			.setCancelable(false)
			.setPositiveButton(R.string.action_ok) { _, _ ->
				activity.finishAffinity()
				Process.killProcess(Process.myPid())
			}
			.show()
	}
}
