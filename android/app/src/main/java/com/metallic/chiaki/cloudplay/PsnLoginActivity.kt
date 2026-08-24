// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.PsnTokenManager
import com.metallic.chiaki.common.SecureTokenManager
import com.pylux.stream.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Confirmed on-device: signing in through an embedded [android.webkit.WebView] gets rejected by
 * Sony's fraud/bot detection — the SSO cookie exchange right after password submit 403s from the
 * WebView, but signing into the exact same account works fine in real Chrome on the same device,
 * same network, same account. So this opens Sony's sign-in in a real Chrome Custom Tab instead
 * (sharing Chrome's own cookies/JS engine/fingerprint, not an embedded emulation of it). Custom
 * Tabs runs in Chrome's own separate process — its cookies aren't reachable from here the way the
 * old WebView's were, and there's no way to read what a Custom Tab is displaying either, so this
 * is a deliberate four-step flow rather than one: sign in (Custom Tab #1, Sony's own sign-in
 * page), fetch the npsso value (Custom Tab #2, reusing that same signed-in Chrome session,
 * pointed directly at the ssocookie endpoint so it lands straight on the raw JSON), paste it
 * in, then finalise. The middle copy/paste step is the well-known manual technique for
 * extracting an NPSSO token from any real browser — there's no OS mechanism to automate it
 * further without reading another app's page content, which Android deliberately disallows.
 */
class PsnLoginActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "PsnLoginActivity"
		private const val SONY_SIGN_IN_URL = "https://store.playstation.com/"

		const val EXTRA_NPSSO_TOKEN = "npsso_token"
		const val RESULT_LOGIN_SUCCESS = Activity.RESULT_OK
		const val RESULT_LOGIN_CANCELLED = Activity.RESULT_CANCELED
		const val RESULT_LOGIN_FAILED = 3
	}

	private lateinit var tokenManager: SecureTokenManager
	private lateinit var preferences: Preferences
	private lateinit var psnTokenManager: PsnTokenManager

	private lateinit var statusTextView: TextView
	private lateinit var npssoInput: EditText
	private lateinit var progressBar: ProgressBar
	private lateinit var signInButton: Button
	private lateinit var obtainNpssoButton: Button
	private lateinit var finaliseButton: Button
	private lateinit var cancelButton: Button
	private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

	private var finalising = false

	override fun onCreate(savedInstanceState: Bundle?) {
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		tokenManager = SecureTokenManager(this)
		preferences = Preferences(this)
		psnTokenManager = PsnTokenManager(preferences)

		setupUi()
		statusTextView.text = "1. Tap Sign into account and sign in with Sony.\n" +
			"2. Tap Obtain NPSSO Token — Chrome will show a page like {\"npsso\":\"...\"}.\n" +
			"3. Copy the npsso value and paste it below.\n" +
			"4. Tap Finalise log in."
	}

	private fun resolveThemeColor(attrId: Int): Int {
		val tv = TypedValue()
		theme.resolveAttribute(attrId, tv, true)
		return tv.data
	}

	private fun styleCloudPadCancelButton(button: Button) {
		val accent = resolveThemeColor(R.attr.pyluxAccent)
		val accentA30 = resolveThemeColor(R.attr.pyluxAccentA30)
		button.setTextColor(Color.WHITE)
		button.background = android.graphics.drawable.GradientDrawable().apply {
			shape = android.graphics.drawable.GradientDrawable.RECTANGLE
			cornerRadius = 18f
			setColor(accentA30)
			setStroke(2, accent)
		}
	}

	private fun setupUi() {
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setBackgroundColor(Color.rgb(7, 3, 10))
			setPadding(24, 24, 24, 24)
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.MATCH_PARENT
			)
		}

		val titleText = TextView(this).apply {
			text = getString(R.string.psn_login_activity_title)
			setTextColor(Color.WHITE)
			textSize = 22f
			gravity = Gravity.CENTER
			setPadding(0, 0, 0, 12)
		}

		statusTextView = TextView(this).apply {
			setTextColor(Color.WHITE)
			textSize = 15f
			gravity = Gravity.CENTER
			setPadding(0, 0, 0, 16)
		}

		npssoInput = EditText(this).apply {
			hint = getString(R.string.psn_login_npsso_hint)
			setTextColor(Color.WHITE)
			setHintTextColor(Color.GRAY)
			isSingleLine = false
			maxLines = 4
			setPadding(24, 24, 24, 24)
			background = android.graphics.drawable.GradientDrawable().apply {
				shape = android.graphics.drawable.GradientDrawable.RECTANGLE
				cornerRadius = 18f
				setColor(Color.TRANSPARENT)
				setStroke(2, resolveThemeColor(R.attr.pyluxAccentLight))
			}
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			).apply { bottomMargin = 24 }
		}

		progressBar = ProgressBar(this).apply {
			visibility = View.GONE
		}

		signInButton = Button(this).apply {
			text = getString(R.string.psn_login_open_browser)
			styleCloudPadCancelButton(this)
			setOnClickListener {
				startSonySignIn()
			}
		}

		obtainNpssoButton = Button(this).apply {
			text = getString(R.string.psn_login_obtain_npsso_button)
			styleCloudPadCancelButton(this)
			setOnClickListener {
				obtainNpssoToken()
			}
		}

		finaliseButton = Button(this).apply {
			text = getString(R.string.psn_login_finalise_button)
			isEnabled = true
			styleCloudPadCancelButton(this)
			setOnClickListener {
				finaliseLogin()
			}
		}

		cancelButton = Button(this).apply {
			text = getString(R.string.action_cancel)
			styleCloudPadCancelButton(this)
			setOnClickListener {
				setResult(RESULT_LOGIN_CANCELLED)
				finish()
			}
		}

		root.addView(titleText)
		root.addView(statusTextView)
		root.addView(progressBar)
		root.addView(signInButton)
		root.addView(obtainNpssoButton)
		root.addView(npssoInput)
		root.addView(finaliseButton)
		root.addView(cancelButton)

		setContentView(root)
	}

	private fun startSonySignIn() {
		statusTextView.text = getString(R.string.psn_login_start_status)
		val customTabsIntent = CustomTabsIntent.Builder().build()
		customTabsIntent.launchUrl(this, Uri.parse(SONY_SIGN_IN_URL))
	}

	private fun obtainNpssoToken() {
		statusTextView.text = getString(R.string.psn_login_obtain_npsso_status)
		val customTabsIntent = CustomTabsIntent.Builder().build()
		customTabsIntent.launchUrl(this, Uri.parse(PsnAuthConstants.SSOCOOKIE_ENDPOINT))
	}

	private fun finaliseLogin() {
		if (finalising) return

		val npsso = extractNpsso(npssoInput.text?.toString().orEmpty())
		if (npsso == null) {
			Toast.makeText(
				this,
				R.string.psn_login_paste_npsso_toast,
				Toast.LENGTH_LONG
			).show()
			return
		}

		finalising = true
		progressBar.visibility = View.VISIBLE
		finaliseButton.isEnabled = false
		signInButton.isEnabled = false
		obtainNpssoButton.isEnabled = false
		statusTextView.text = getString(R.string.psn_login_finalising_status)

		scope.launch {
			try {
				val exchangeSuccess = withContext(Dispatchers.IO) {
					tokenManager.saveNpssoToken(npsso)
					psnTokenManager.exchangeNpssoForTokens(npsso)
				}

				if (exchangeSuccess) {
					Log.i(TAG, "PSN login complete: NPSSO + Remote Play tokens saved")
					Toast.makeText(
						this@PsnLoginActivity,
						getString(R.string.psn_login_success),
						Toast.LENGTH_SHORT
					).show()
				} else {
					Log.w(TAG, "PSN login: NPSSO saved, but Remote Play token exchange failed")
					Toast.makeText(
						this@PsnLoginActivity,
						R.string.psn_login_remote_play_retry_toast,
						Toast.LENGTH_LONG
					).show()
				}

				val resultIntent = Intent().apply {
					putExtra(EXTRA_NPSSO_TOKEN, npsso)
				}
				setResult(RESULT_LOGIN_SUCCESS, resultIntent)
				finish()
			} catch (e: Exception) {
				Log.e(TAG, "Finalise login failed", e)

				Toast.makeText(
					this@PsnLoginActivity,
					getString(R.string.psn_login_failed),
					Toast.LENGTH_LONG
				).show()

				setResult(RESULT_LOGIN_FAILED)
				finish()
			} finally {
				finalising = false
				progressBar.visibility = View.GONE
				finaliseButton.isEnabled = true
				signInButton.isEnabled = true
				obtainNpssoButton.isEnabled = true
			}
		}
	}

	/** Accepts either the bare npsso value or the full {"npsso":"..."} JSON Chrome shows at the
	 *  ssocookie endpoint — users copying from a raw JSON response in a browser often select the
	 *  whole line rather than precisely the quoted value, so this tries JSON first and falls back
	 *  to treating the trimmed input as the token itself (also stripping surrounding quotes, the
	 *  other common copy-paste artifact). */
	private fun extractNpsso(pasted: String): String? {
		val trimmed = pasted.trim()
		if (trimmed.isEmpty()) return null

		if (trimmed.startsWith("{")) {
			return try {
				JSONObject(trimmed).optString("npsso").takeIf { it.isNotBlank() }
			} catch (e: Exception) {
				null
			}
		}

		return trimmed.removeSurrounding("\"").takeIf { it.isNotBlank() }
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}

	@Suppress("OVERRIDE_DEPRECATION")
	override fun onBackPressed() {
		setResult(RESULT_LOGIN_CANCELLED)
		super.onBackPressed()
	}
}
