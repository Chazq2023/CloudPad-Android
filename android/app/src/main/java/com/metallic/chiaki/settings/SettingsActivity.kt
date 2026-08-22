// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.res.Resources
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.metallic.chiaki.common.Preferences
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivitySettingsBinding

interface TitleFragment
{
	fun getTitle(resources: Resources): String
}

class SettingsActivity: AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
{
	private lateinit var binding: ActivitySettingsBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
		super.onCreate(savedInstanceState)
		binding = ActivitySettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		title = ""
		setSupportActionBar(binding.toolbar)
		binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

		supportFragmentManager.addOnBackStackChangedListener {
			val titleFragment = supportFragmentManager.findFragmentById(R.id.settingsFragment) as? TitleFragment ?: return@addOnBackStackChangedListener
			binding.titleTextView.text = titleFragment.getTitle(resources)
		}

		if (savedInstanceState == null)
		{
			// Only on a genuinely fresh launch — on a config-change recreate (e.g. rotating while
			// a ListPreference's dialog is open), the FragmentManager has already restored the
			// previous SettingsFragment instance (and reconnected the open PreferenceDialogFragment
			// to it as its target fragment) as part of super.onCreate() above. Replacing it again
			// here swapped in a second, different SettingsFragment instance that dialog was never
			// attached to, leaving it pointing at a now-discarded fragment — which crashed with
			// "Target fragment must implement TargetFragment interface" as soon as the dialog tried
			// to restore itself against it.
			val rootFragment = SettingsFragment()
			replaceFragment(rootFragment, false)
			binding.titleTextView.text = rootFragment.getTitle(resources)
		}
		else
		{
			val restoredFragment = supportFragmentManager.findFragmentById(R.id.settingsFragment) as? TitleFragment
			binding.titleTextView.text = restoredFragment?.getTitle(resources) ?: ""
		}
	}

	/** Circle/B as a controller shortcut for the back button — same equivalence QuickSettingsPanel
	 *  already treats KEYCODE_BACK/KEYCODE_BUTTON_B as. onBackPressedDispatcher (rather than a
	 *  plain finish()) correctly pops back to the root preference screen first if a sub-screen
	 *  like Registered Hosts is open, matching what the hardware back key already does here. */
	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
		{
			onBackPressedDispatcher.onBackPressed()
			return true
		}
		return super.dispatchKeyEvent(event)
	}

	override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference) = when(pref.fragment)
	{
		SettingsRegisteredHostsFragment::class.java.canonicalName -> {
			replaceFragment(SettingsRegisteredHostsFragment(), true)
			true
		}
		else -> false
	}

	private fun replaceFragment(fragment: Fragment, addToBackStack: Boolean)
	{
		supportFragmentManager.beginTransaction()
			.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
			.replace(R.id.settingsFragment, fragment)
			.also {
				if(addToBackStack)
					it.addToBackStack(null)
			}
			.commit()
	}
}