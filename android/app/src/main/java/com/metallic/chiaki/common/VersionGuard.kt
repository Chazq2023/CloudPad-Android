// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.metallic.chiaki.cloudplay.api.HttpClient
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.pylux.stream.BuildConfig
import com.pylux.stream.R
import kotlinx.coroutines.CompletableDeferred
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
 * Fetched once per launch (one request, no retries). The last value seen applies straight away;
 * if the fetch fails or the reply isn't a valid config, a blocking "Unable to Check for Updates"
 * popup closes the app — otherwise blocking the GitHub address would dodge every future forced
 * update. A GitHub outage therefore blocks the app until it's back. Only release builds carry a
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
	private var currentVersion = ""
	private var minimumVersion = ""
	private var outdated = false
	private var checkFailed = false
	// Completes once this launch's check has a result (or there's no check, on dev builds), so the
	// optional "Update available" prompt can wait instead of racing it (see UpdateNotifier).
	private val checkDone = CompletableDeferred<Unit>()

	/** Suspends until this launch's minimum-version check has finished (fetched or failed). */
	suspend fun awaitCheck() = checkDone.await()

	/** Whether a blocking popup ("Update Required" or "Unable to Check for Updates") applies right
	 *  now (UpdateNotifier stays out of its way). */
	fun isUpdateRequired(): Boolean = outdated || checkFailed

	data class Config(val minVersion: String, val updateUrl: String)

	/** The config from a fetch of min-version.json, or null if the reply can't be trusted (not a
	 *  200, not JSON, or no minVersion) — which counts as a failed check. */
	fun parseConfig(statusCode: Int, body: String): Config?
	{
		if (statusCode != 200) return null
		val json = try { JSONObject(body) } catch (e: Exception) { return null }
		val minimum = json.optString("minVersion", "").trim().ifBlank { return null }
		if (compareVersions(minimum, minimum) == null) return null
		return Config(minimum, json.optString("updateUrl", "").ifBlank { DEFAULT_UPDATE_URL })
	}

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
			checkDone.complete(Unit)
			return
		}

		app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks
		{
			override fun onActivityResumed(activity: Activity)
			{
				resumedActivity = activity
				if (outdated) showUpdateDialog()
				else if (checkFailed) showCheckFailedDialog()
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

		val installedAt = SystemClock.elapsedRealtime()
		scope.launch {
			val startedAt = SystemClock.elapsedRealtime()
			val config = try
			{
				withContext(Dispatchers.IO) {
					val fetchStart = SystemClock.elapsedRealtime()
					val response = HttpClient.get(CONFIG_URL, timeoutMs = 10_000)
					Log.i(TAG, "Fetched minimum version in ${SystemClock.elapsedRealtime() - fetchStart} ms " +
						"(HTTP ${response.statusCode}; started ${startedAt - installedAt} ms after launch)")
					parseConfig(response.statusCode, response.body)
				}
			}
			catch (e: Exception)
			{
				Log.w(TAG, "Couldn't fetch minimum version", e)
				null
			}

			if (config == null)
			{
				Log.w(TAG, "Minimum version check failed; blocking until it succeeds")
				checkFailed = true
				if (!outdated) showCheckFailedDialog()
				checkDone.complete(Unit)
				return@launch
			}

			prefs.edit().putString(KEY_MIN_VERSION, config.minVersion).putString(KEY_UPDATE_URL, config.updateUrl).apply()
			apply(current, config.minVersion, config.updateUrl)
			checkDone.complete(Unit)
		}
	}

	private fun apply(current: String, minimum: String?, url: String?)
	{
		outdated = isOutdated(current, minimum)
		currentVersion = current
		minimumVersion = minimum.orEmpty()
		updateUrl = url ?: DEFAULT_UPDATE_URL
		Log.i(TAG, "Version $current, minimum $minimum -> ${if (outdated) "update required" else "ok"}")
		if (outdated) showUpdateDialog()
	}

	private fun showCheckFailedDialog()
	{
		val activity = resumedActivity ?: return
		if (dialog?.isShowing == true || activity.isFinishing) return
		dialog = activity.alertDialogBuilder()
			.setTitle(R.string.version_check_failed_title)
			.setMessage(R.string.version_check_failed_message)
			.setCancelable(false)
			.setPositiveButton(R.string.action_ok) { _, _ ->
				activity.finishAffinity()
				Process.killProcess(Process.myPid())
			}
			.show()
	}

	private fun showUpdateDialog()
	{
		val activity = resumedActivity ?: return
		if (dialog?.isShowing == true || activity.isFinishing) return
		dialog = activity.alertDialogBuilder()
			.setTitle(R.string.update_required_title)
			.setMessage(activity.getString(R.string.update_required_message, currentVersion, minimumVersion))
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
