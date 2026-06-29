// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
import android.net.Uri

/**
 * PSN Login Activity for CloudPad.
 *
 * New flow:
 * 1. User signs in directly with Sony in their browser.
 * 2. User opens the Sony NPSSO endpoint in the same browser.
 * 3. User copies/pastes the NPSSO value into CloudPad.
 * 4. CloudPad saves NPSSO locally.
 * 5. CloudPad exchanges NPSSO for Remote Play tokens using the existing PsnTokenManager.
 *
 * This removes the old third-party xbgamestream login-code website flow.
 */
class PsnLoginActivity : AppCompatActivity() {

	companion object {
		private const val TAG = "PsnLoginActivity"

		private const val SONY_SIGN_IN_URL =
			"https://store.playstation.com/"

		private const val SONY_SSO_COOKIE_URL =
			"https://ca.account.sony.com/api/v1/ssocookie"

		const val EXTRA_NPSSO_TOKEN = "npsso_token"
		const val RESULT_LOGIN_SUCCESS = Activity.RESULT_OK
		const val RESULT_LOGIN_CANCELLED = Activity.RESULT_CANCELED
		const val RESULT_LOGIN_FAILED = 3
	}

	private lateinit var tokenManager: SecureTokenManager
	private lateinit var preferences: Preferences
	private lateinit var psnTokenManager: PsnTokenManager

	private lateinit var statusTextView: TextView
	private lateinit var progressBar: ProgressBar
	private lateinit var npssoInput: EditText
	private lateinit var signInButton: Button
	private lateinit var grabSsoButton: Button
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
		statusTextView.text =
			"Tap Sign into account, sign in with Sony in your browser, then grab and paste your SSO cookie value."
	}

	private fun resolveThemeColor(attrId: Int): Int {
		val tv = TypedValue()
		theme.resolveAttribute(attrId, tv, true)
		return tv.data
	}

	private fun styleCloudPadButton(button: Button) {
		val accent = resolveThemeColor(R.attr.pyluxAccent)
		val accentLight = resolveThemeColor(R.attr.pyluxAccentLight)
		button.setTextColor(Color.WHITE)
		button.background = android.graphics.drawable.GradientDrawable().apply {
			shape = android.graphics.drawable.GradientDrawable.RECTANGLE
			cornerRadius = 18f
			setColor(accent)
			setStroke(2, accentLight)
		}
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
			text = "CloudPad Sign In"
			setTextColor(Color.WHITE)
			textSize = 22f
			gravity = Gravity.CENTER
			setPadding(0, 0, 0, 12)
		}

		statusTextView = TextView(this).apply {
			text = "Tap Sign into account, sign in with Sony in your browser, then grab and paste your SSO cookie value."
			setTextColor(Color.WHITE)
			textSize = 15f
			gravity = Gravity.CENTER
			setPadding(0, 0, 0, 16)
		}

		npssoInput = EditText(this).apply {
			hint = "Paste NPSSO value or full JSON here"
			setTextColor(Color.WHITE)
			setHintTextColor(Color.LTGRAY)
			setSingleLine(false)
			setMinLines(2)
			setMaxLines(4)
			setPadding(16, 16, 16, 16)
		}

		progressBar = ProgressBar(this).apply {
			visibility = View.GONE
		}

		signInButton = Button(this).apply {
			text = "Sign into account"
			styleCloudPadCancelButton(this)
			setOnClickListener {
				startSonySignIn()
			}
		}

		grabSsoButton = Button(this).apply {
			text = "Grab SSO Cookie value"
			styleCloudPadCancelButton(this)
			setOnClickListener {
				grabSsoCookieValue()
			}
		}

		finaliseButton = Button(this).apply {
			text = "Finalise log in"
			isEnabled = true
			styleCloudPadCancelButton(this)
			setOnClickListener {
				val npsso = normaliseNpssoInput(npssoInput.text?.toString().orEmpty())

				if (npsso.isBlank()) {
					Toast.makeText(
						this@PsnLoginActivity,
						"Paste your NPSSO value first.",
						Toast.LENGTH_SHORT
					).show()
				} else {
					finaliseLogin(npsso)
				}
			}
		}

		cancelButton = Button(this).apply {
			text = "Cancel"
			styleCloudPadCancelButton(this)
			setOnClickListener {
				setResult(RESULT_LOGIN_CANCELLED)
				finish()
			}
		}

		root.addView(titleText)
		root.addView(statusTextView)
		root.addView(progressBar)
		root.addView(npssoInput)

		root.addView(signInButton)
		root.addView(grabSsoButton)
		root.addView(finaliseButton)
		root.addView(cancelButton)

		setContentView(root)
	}

	private fun startSonySignIn() {
		npssoInput.text?.clear()
		finaliseButton.isEnabled = true
		progressBar.visibility = View.GONE

		statusTextView.text =
			"Sony sign-in opened in your browser. After signing in, tap Grab SSO Cookie value, copy the npsso value, paste it here, then tap Finalise log in."

		openUrlInBrowser(SONY_SIGN_IN_URL)
	}

	private fun grabSsoCookieValue() {
		progressBar.visibility = View.GONE
		finaliseButton.isEnabled = true

		statusTextView.text =
			"The SSO cookie page opened in your browser. Copy the npsso value, paste it into CloudPad, then tap Finalise log in."

		openUrlInBrowser(SONY_SSO_COOKIE_URL)
	}

	private fun openUrlInBrowser(url: String) {
		try {
			val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
			startActivity(intent)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to open browser URL: $url", e)
			Toast.makeText(
				this,
				"Failed to open browser",
				Toast.LENGTH_LONG
			).show()
		}
	}

	private fun normaliseNpssoInput(input: String): String {
		val trimmed = input.trim()

		if (trimmed.isBlank()) return ""

		// Allows user to paste the full JSON:
		// { "npsso": "..." }
		try {
			val json = JSONObject(trimmed)
			val npsso = json.optString("npsso")
			if (npsso.isNotBlank()) return npsso.trim()
		} catch (_: Exception) {
		}

		// Allows pasted JSON-ish text:
		// "npsso": "..."
		Regex(""""npsso"\s*:\s*"([^"]+)"""")
			.find(trimmed)
			?.groupValues
			?.getOrNull(1)
			?.let { return it.trim() }

		// Allows user to paste only the raw NPSSO value.
		return trimmed
	}

	private fun finaliseLogin(npsso: String) {
		if (finalising) return

		finalising = true
		progressBar.visibility = View.VISIBLE
		finaliseButton.isEnabled = false
		grabSsoButton.isEnabled = false
		signInButton.isEnabled = false
		statusTextView.text = "Finalising login…"

		scope.launch {
			try {
				val exchangeSuccess = withContext(Dispatchers.IO) {
					/*
                     * Save NPSSO using the existing secure token path.
                     * This is what Cloud Play / catalog code uses.
                     */
					tokenManager.saveNpssoToken(npsso)

					/*
                     * Keep the existing Remote Play token exchange.
                     * This is what your old PsnLoginActivity already did after receiving NPSSO.
                     */
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
						"Cloud login complete. Remote Play setup may need retrying.",
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
				grabSsoButton.isEnabled = true
				signInButton.isEnabled = true
			}
		}
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}

	override fun onBackPressed() {
		setResult(RESULT_LOGIN_CANCELLED)
		super.onBackPressed()
	}
}