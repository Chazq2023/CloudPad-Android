// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pylux.stream.R

class LicenseAgreementActivity : AppCompatActivity()
{
	companion object {
		const val EXTRA_VIEW_ONLY = "view_only"
	}
	
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_license_agreement)
		
		val licenseTextView = findViewById<TextView>(R.id.licenseTextView)
		val closeButton = findViewById<Button>(R.id.closeButton)
		
		// Make license text scrollable
		licenseTextView.movementMethod = ScrollingMovementMethod()
		
		// Set license text
		licenseTextView.text = getLicenseText()
		
		closeButton.setOnClickListener { finish() }

		// The dialog theme only carries the default accent; match the user's chosen theme colour.
		closeButton.backgroundTintList = ColorStateList.valueOf(themeAccent())
	}

	private fun themeAccent(): Int
	{
		val themed = ContextThemeWrapper(this, Preferences(this).getThemeStyleRes())
		val attrs = themed.obtainStyledAttributes(intArrayOf(R.attr.pyluxAccent))
		try { return attrs.getColor(0, getColor(R.color.accent)) }
		finally { attrs.recycle() }
	}
	
	private fun getLicenseText(): String
	{
		val licenseInputStream = resources.openRawResource(R.raw.agpl_license)
		val disclaimerInputStream = resources.openRawResource(R.raw.disclaimer)
		
		val licenseText = licenseInputStream.bufferedReader().use { it.readText() }
		val disclaimerText = disclaimerInputStream.bufferedReader().use { it.readText() }
		
		return licenseText + "\n\n" + disclaimerText
	}
}
