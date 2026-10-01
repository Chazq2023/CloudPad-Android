// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.metallic.chiaki.cloudplay.api.HttpClient
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.pylux.stream.BuildConfig
import com.pylux.stream.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Forces old releases to update. Builds from before a protection existed (e.g. the pre-order
 * release gate) can't be fixed after the fact, so the repo's config/min-version.json names the
 * oldest release still allowed to run; anything older gets a blocking "Update Required" popup.
 * Fetched once per launch; if it can't be fetched the last value seen is used, and with nothing
 * seen yet the app runs, so a GitHub outage never locks anyone out. Only release builds carry a
 * CloudPad version (BuildConfig.CLOUDPAD_RELEASE_VERSION) — local/dev builds skip the check.
 */
object VersionGuard
{
	private const val TAG = "VersionGuard"
	private const val CONFIG_URL = "https://raw.githubusercontent.com/Chazq2023/CloudPad-Android/master/config/min-version.json"
	private const val DEFAULT_UPDATE_URL = "https://github.com/Chazq2023/CloudPad-Android/releases/latest"
	private const val PREFS = "version_guard"
	private const val KEY_MIN_VERSION = "min_version"
	private const val KEY_UPDATE_URL = "update_url"

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var resumedActivity: Activity? = null
	private var dialog: AlertDialog? = null
	private var updateUrl = DEFAULT_UPDATE_URL
	private var outdated = false

	/** Compares dotted numeric versions ("0.1.59" vs "0.1.60"); null if either isn't one. */
	fun compareVersions(a: String, b: String): Int?
	{
		val pa = a.trim().split('.').map { it.toIntOrNull() ?: return null }
		val pb = b.trim().split('.').map { it.toIntOrNull() ?: return null }
		for (i in 0 until maxOf(pa.size, pb.size))
		{
			val diff = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
			if (diff != 0) return diff
		}
		return 0
	}

	/** True only when both versions are valid and [current] is older than [minimum]. */
	fun isOutdated(current: String, minimum: String?): Boolean
	{
		if (current.isBlank() || minimum.isNullOrBlank()) return false
		return (compareVersions(current, minimum) ?: return false) < 0
	}

	fun install(app: Application)
	{
		val current = BuildConfig.CLOUDPAD_RELEASE_VERSION
		if (current.isBlank())
		{
			Log.i(TAG, "Not a release build, skipping version check")
			return
		}

		app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks
		{
			override fun onActivityResumed(activity: Activity)
			{
				resumedActivity = activity
				if (outdated) showUpdateDialog()
			}
			override fun onActivityPaused(activity: Activity) { if (resumedActivity === activity) resumedActivity = null }
			override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
			override fun onActivityStarted(activity: Activity) {}
			override fun onActivityStopped(activity: Activity) {}
			override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
			override fun onActivityDestroyed(activity: Activity) {}
		})

		val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		// Last known minimum applies straight away, before (or without) the network fetch.
		apply(current, prefs.getString(KEY_MIN_VERSION, null), prefs.getString(KEY_UPDATE_URL, null))

		scope.launch {
			val config = try
			{
				withContext(Dispatchers.IO) {
					val response = HttpClient.get(CONFIG_URL, timeoutMs = 5000)
					if (response.statusCode == 200) JSONObject(response.body) else null
				}
			}
			catch (e: Exception)
			{
				Log.w(TAG, "Couldn't fetch minimum version", e)
				null
			} ?: return@launch

			val minimum = config.optString("minVersion", "").ifBlank { null } ?: return@launch
			val url = config.optString("updateUrl", "").ifBlank { DEFAULT_UPDATE_URL }
			prefs.edit().putString(KEY_MIN_VERSION, minimum).putString(KEY_UPDATE_URL, url).apply()
			apply(current, minimum, url)
		}
	}

	private fun apply(current: String, minimum: String?, url: String?)
	{
		outdated = isOutdated(current, minimum)
		updateUrl = url ?: DEFAULT_UPDATE_URL
		Log.i(TAG, "Version $current, minimum $minimum -> ${if (outdated) "update required" else "ok"}")
		if (outdated) showUpdateDialog()
	}

	private fun showUpdateDialog()
	{
		val activity = resumedActivity ?: return
		if (dialog?.isShowing == true || activity.isFinishing) return
		dialog = activity.alertDialogBuilder()
			.setTitle(R.string.update_required_title)
			.setMessage(R.string.update_required_message)
			.setCancelable(false)
			.setPositiveButton(R.string.update_required_button) { _, _ ->
				try
				{
					activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
				}
				catch (e: Exception)
				{
					Log.w(TAG, "Couldn't open update page", e)
				}
				activity.finishAffinity()
				Process.killProcess(Process.myPid())
			}
			.show()
	}
}
