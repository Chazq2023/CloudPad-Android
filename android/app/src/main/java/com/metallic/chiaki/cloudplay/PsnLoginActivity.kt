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
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.cloudplay.api.HttpClient
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
	private lateinit var signInButton: Button
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
		statusTextView.text = "Tap Sign into account, sign in with Sony, then tap Finalise log in."
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
			text = "Tap Sign into account, sign in with Sony, then tap Finalise log in."
			setTextColor(Color.WHITE)
			textSize = 15f
			gravity = Gravity.CENTER
			setPadding(0, 0, 0, 16)
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

		finaliseButton = Button(this).apply {
			text = "Finalise log in"
			isEnabled = true
			styleCloudPadCancelButton(this)
			setOnClickListener {
				finaliseLogin()
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
		root.addView(signInButton)
		root.addView(finaliseButton)
		root.addView(cancelButton)

		setContentView(root)
	}

	private fun startSonySignIn() {
		statusTextView.text =
			"Sign in with Sony, then tap Finalise log in."
		val intent = Intent(this, PsnSignInWebViewActivity::class.java)
		startActivity(intent)
	}

	private fun finaliseLogin() {
		if (finalising) return

		finalising = true
		progressBar.visibility = View.VISIBLE
		finaliseButton.isEnabled = false
		signInButton.isEnabled = false
		statusTextView.text = "Fetching login credentials…"

		// CookieManager must be read on the main thread
		val cookies = CookieManager.getInstance().getCookie(SONY_SSO_COOKIE_URL) ?: ""

		scope.launch {
			try {
				val npsso = withContext(Dispatchers.IO) {
					fetchNpssoFromSsoCookie(cookies)
				}

				if (npsso == null) {
					statusTextView.text =
						"Could not fetch credentials. Please sign into your account first."
					Toast.makeText(
						this@PsnLoginActivity,
						"Sign in with Sony first, then tap Finalise log in.",
						Toast.LENGTH_LONG
					).show()
					return@launch
				}

				statusTextView.text = "Finalising login…"

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
				signInButton.isEnabled = true
			}
		}
	}

	private fun fetchNpssoFromSsoCookie(cookies: String): String? {
		Log.d(TAG, "Fetching SSO cookie, cookies present=${cookies.isNotBlank()}")

		val headers = mutableMapOf("Accept" to "application/json")
		if (cookies.isNotBlank()) {
			headers["Cookie"] = cookies
		}

		val response = HttpClient.get(
			url = SONY_SSO_COOKIE_URL,
			headers = headers
		)

		Log.d(TAG, "SSO response status: ${response.statusCode}")

		if (response.statusCode != 200) {
			Log.e(TAG, "SSO cookie endpoint failed: ${response.statusCode}, body: ${response.body.take(200)}")
			return null
		}

		return try {
			JSONObject(response.body).optString("npsso").takeIf { it.isNotBlank() }
		} catch (e: Exception) {
			Log.e(TAG, "SSO response parse failed: ${e.message}")
			null
		}
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
