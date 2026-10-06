// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.metallic.chiaki.cloudplay.api.HttpClient
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.pylux.stream.BuildConfig
import com.pylux.stream.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Optional "a new version is available" prompt, shown after the launch disclaimer on every fresh
 * launch while a newer GitHub release exists ("Later" just defers it to the next launch). Unlike
 * VersionGuard's forced update, the user can keep going. Release builds only.
 */
object UpdateNotifier
{
	private const val TAG = "UpdateNotifier"
	private const val LATEST_RELEASE_API = "https://api.github.com/repos/Chazq2023/CloudPad-Android/releases/latest"
	private const val DEFAULT_RELEASE_URL = "https://github.com/Chazq2023/CloudPad-Android/releases/latest"

	data class Release(val version: String, val url: String, val notes: List<String>)

	/** What came of this launch's prompt: none shown, "Later", or "Update" (left for GitHub). */
	enum class Decision { NONE, LATER, UPDATE }

	private val decision = CompletableDeferred<Decision>()

	/** Whether this launch's prompt has been answered (or skipped) yet. */
	fun isDecided(): Boolean = decision.isCompleted

	/** Suspends until this launch's prompt is answered or found unnecessary. Game launches wait
	 *  on this so a stream doesn't start behind the prompt (see CloudPlayFragment.onGameClicked). */
	suspend fun awaitDecision(): Decision = decision.await()

	/** No prompt this launch (e.g. Main restored after process death skips the disclaimer flow). */
	fun skipPrompt() { decision.complete(Decision.NONE) }

	/** The release's "### Updates" bullets as plain text (no markdown emphasis/links/code). */
	fun parseUpdateNotes(body: String): List<String>
	{
		val notes = mutableListOf<String>()
		var inUpdates = false
		for (rawLine in body.lines())
		{
			val line = rawLine.trim()
			if (line.startsWith("#"))
			{
				inUpdates = line.trimStart('#').trim().equals("Updates", ignoreCase = true)
				continue
			}
			if (inUpdates && (line.startsWith("- ") || line.startsWith("* ")))
				notes.add(stripMarkdown(line.substring(2).trim()))
		}
		return notes
	}

	private fun stripMarkdown(text: String): String = text
		.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
		.replace("**", "").replace("__", "").replace("`", "")

	fun parseRelease(json: JSONObject): Release?
	{
		val tag = json.optString("tag_name", "").removePrefix("v").trim()
		if (tag.isEmpty() || json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return null
		val url = json.optString("html_url", "").ifBlank { DEFAULT_RELEASE_URL }
		return Release(tag, url, parseUpdateNotes(json.optString("body", "")))
	}

	/** Newer release than [current], or null (also null if either version doesn't parse). */
	fun newerRelease(current: String, release: Release?): Release? =
		release?.takeIf { VersionGuard.compareVersions(it.version, current)?.let { c -> c > 0 } == true }

	private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
		try
		{
			val response = HttpClient.get(LATEST_RELEASE_API, mapOf("Accept" to "application/vnd.github+json"), timeoutMs = 5000)
			if (response.statusCode == 200) parseRelease(JSONObject(response.body)) else null
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Couldn't check for a new release", e)
			null
		}
	}

	/**
	 * Checks for a newer release and, if there is one, shows the prompt. [onDone] runs when the
	 * prompt is closed, or straight away when there's nothing to show, so callers can chain
	 * other launch dialogs after it without stacking them.
	 */
	suspend fun checkAndPrompt(activity: Activity, onDone: () -> Unit)
	{
		val current = BuildConfig.CLOUDPAD_RELEASE_VERSION
		// Wait for this launch's required-update check first: with an out-of-date saved minimum
		// (fresh install, or just after it was raised) it can still be in flight, and showing
		// "Update available" before "Update Required" replaces it is confusing.
		VersionGuard.awaitCheck()
		if (current.isBlank() || VersionGuard.isUpdateRequired())
		{
			decision.complete(Decision.NONE)
			onDone()
			return
		}
		val release = newerRelease(current, fetchLatest())
		if (release == null || activity.isFinishing || activity.isDestroyed)
		{
			decision.complete(Decision.NONE)
			onDone()
			return
		}
		Log.i(TAG, "New release ${release.version} available (current $current)")

		val message = StringBuilder(activity.getString(R.string.update_available_message, release.version, current))
		if (release.notes.isNotEmpty())
		{
			message.append("\n\n").append(activity.getString(R.string.update_available_whats_new))
			// All of them: the dialog's message area scrolls when they don't fit, buttons stay put.
			release.notes.forEach { message.append("\n• ").append(it) }
		}

		activity.alertDialogBuilder()
			.setTitle(R.string.update_available_title)
			.setMessage(message)
			.setCancelable(false)
			.setPositiveButton(R.string.update_required_button) { _, _ ->
				decision.complete(Decision.UPDATE)
				try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url))) }
				catch (e: Exception) { Log.w(TAG, "Couldn't open release page", e) }
				onDone()
			}
			.setNegativeButton(R.string.update_available_later) { _, _ ->
				decision.complete(Decision.LATER)
				onDone()
			}
			.show()
	}
}
