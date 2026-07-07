// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.common.Preferences
import com.pylux.stream.R

class PsnSignInWebViewActivity : AppCompatActivity() {

	companion object {
		private const val SIGN_IN_URL = "https://store.playstation.com/"
	}

	private lateinit var webView: WebView
	private lateinit var progressBar: ProgressBar

	override fun onCreate(savedInstanceState: Bundle?) {
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		CookieManager.getInstance().setAcceptCookie(true)

		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setBackgroundColor(Color.rgb(7, 3, 10))
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.MATCH_PARENT
			)
		}

		val accent = resolveThemeColor(R.attr.pyluxAccent)
		val accentLight = resolveThemeColor(R.attr.pyluxAccentLight)

		val topBar = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(24, 24, 24, 24)
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
		}

		val titleView = TextView(this).apply {
			text = "Sign in to PlayStation"
			setTextColor(Color.WHITE)
			textSize = 18f
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
		}

		val doneButton = Button(this).apply {
			text = "Done"
			setTextColor(Color.WHITE)
			background = android.graphics.drawable.GradientDrawable().apply {
				shape = android.graphics.drawable.GradientDrawable.RECTANGLE
				cornerRadius = 18f
				setColor(accent)
				setStroke(2, accentLight)
			}
			setOnClickListener { finish() }
		}

		topBar.addView(titleView)
		topBar.addView(doneButton)

		progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
			max = 100
			progress = 0
			visibility = View.VISIBLE
			layoutParams = LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				8
			)
		}

		webView = WebView(this).apply {
			settings.apply {
				javaScriptEnabled = true
				domStorageEnabled = true
				setSupportZoom(true)
				builtInZoomControls = true
				displayZoomControls = false
				userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
			}
			CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

			webViewClient = object : WebViewClient() {
				override fun onPageFinished(view: WebView?, url: String?) {
					super.onPageFinished(view, url)
					CookieManager.getInstance().flush()
				}
			}

			webChromeClient = object : WebChromeClient() {
				override fun onProgressChanged(view: WebView?, newProgress: Int) {
					super.onProgressChanged(view, newProgress)
					progressBar.progress = newProgress
					progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
				}
			}

			loadUrl(SIGN_IN_URL)
		}

		root.addView(topBar)
		root.addView(progressBar)
		root.addView(
			webView,
			LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
		)

		setContentView(root)
	}

	private fun resolveThemeColor(attrId: Int): Int {
		val tv = TypedValue()
		theme.resolveAttribute(attrId, tv, true)
		return tv.data
	}

	override fun onPause() {
		super.onPause()
		webView.onPause()
	}

	override fun onResume() {
		super.onResume()
		webView.onResume()
	}

	@Suppress("OVERRIDE_DEPRECATION")
	override fun onBackPressed() {
		if (webView.canGoBack()) {
			webView.goBack()
		} else {
			super.onBackPressed()
		}
	}

	override fun onDestroy() {
		webView.destroy()
		super.onDestroy()
	}
}
