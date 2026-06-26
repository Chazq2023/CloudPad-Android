// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.pylux.stream.R
import com.metallic.chiaki.common.LicenseAgreementActivity
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.exportAndShareAllSettings
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.importSettingsFromUri
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import kotlinx.coroutines.launch

class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	override fun getBoolean(key: String?, defValue: Boolean) = when(key)
	{
		preferences.logVerboseKey -> preferences.logVerbose
		preferences.rumbleEnabledKey -> preferences.rumbleEnabled
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		else -> defValue
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.rumbleEnabledKey -> preferences.rumbleEnabled = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
		}
	}

	override fun getString(key: String, defValue: String?) = when(key)
	{
		preferences.resolutionKey -> preferences.resolution.value
		preferences.fpsKey -> preferences.fps.value
		preferences.bitrateKey -> preferences.bitrate?.toString() ?: ""
		preferences.codecKey -> preferences.codec.value
		preferences.cloudResolutionPscloudKey -> preferences.getCloudResolutionPscloud().toString()
		preferences.cloudResolutionPsnowKey -> preferences.getCloudResolutionPsnow().toString()
		preferences.cloudDatacenterPsnowKey -> preferences.getCloudDatacenterPsnow()
		preferences.cloudDatacenterPscloudKey -> preferences.getCloudDatacenterPscloud()
		else -> defValue
	}

	override fun putString(key: String, value: String?)
	{
		when(key)
		{
			preferences.resolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value } ?: return
				preferences.resolution = resolution
			}
			preferences.fpsKey ->
			{
				val fps = Preferences.FPS.values().firstOrNull { it.value == value } ?: return
				preferences.fps = fps
			}
			preferences.bitrateKey -> preferences.bitrate = value?.toIntOrNull()
			preferences.codecKey ->
			{
				val codec = Preferences.Codec.values().firstOrNull { it.value == value } ?: return
				preferences.codec = codec
			}
			preferences.cloudResolutionPscloudKey -> preferences.setCloudResolutionPscloud(value?.toIntOrNull() ?: 720)
			preferences.cloudResolutionPsnowKey -> preferences.setCloudResolutionPsnow(value?.toIntOrNull() ?: 720)
			preferences.cloudDatacenterPsnowKey -> preferences.setCloudDatacenterPsnow(value ?: "Auto")
			preferences.cloudDatacenterPscloudKey -> preferences.setCloudDatacenterPscloud(value ?: "Auto")
		}
	}

	override fun getInt(key: String, defValue: Int) = when (key) {
		preferences.cloudBitratePscloudKey -> preferences.getCloudBitratePscloud() / 1000
		preferences.cloudBitratePsnowKey -> preferences.getCloudBitratePsnow() / 1000
		else -> defValue
	}

	override fun putInt(key: String, value: Int) {
		when (key) {
			preferences.cloudBitratePscloudKey -> preferences.setCloudBitratePscloud(value * 1000)
			preferences.cloudBitratePsnowKey -> preferences.setCloudBitratePsnow(value * 1000)
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
	}

	private var disposable = CompositeDisposable()
	private var exportDisposable = CompositeDisposable().also { it.addTo(disposable) }

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		val preferences = viewModel.preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		// Populate cloud datacenter dropdowns dynamically from saved ping results
		populateCloudDatacenterPreference(
			preferenceScreen.findPreference(getString(R.string.preferences_cloud_datacenter_psnow_key)),
			preferences.getCloudDatacentersJsonPsnow()
		)
		populateCloudDatacenterPreference(
			preferenceScreen.findPreference(getString(R.string.preferences_cloud_datacenter_pscloud_key)),
			preferences.getCloudDatacentersJsonPscloud()
		)

		bindCloudBitratePreference(
			preferenceScreen.findPreference(getString(R.string.preferences_cloud_bitrate_pscloud_key)),
			preferences
		)

		bindCloudBitratePreference(
			preferenceScreen.findPreference(getString(R.string.preferences_cloud_bitrate_psnow_key)),
			preferences
		)

		val bitratePreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_bitrate_key))
		val bitrateSummaryProvider = Preference.SummaryProvider<EditTextPreference> {
			preferences.bitrate?.toString() ?: getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
		}
		bitratePreference?.let {
			it.summaryProvider = bitrateSummaryProvider
			it.setOnBindEditTextListener { editText ->
				editText.hint = getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.bitrate?.toString() ?: "")
			}
		}
		viewModel.bitrateAuto.observe(this, Observer {
			bitratePreference?.summaryProvider = bitrateSummaryProvider
		})

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		preferenceScreen.findPreference<Preference>("remap_controller")?.setOnPreferenceClickListener {
			startActivity(Intent(requireContext(), ControllerRemapActivity::class.java))
			true
		}

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.setOnPreferenceClickListener { importSettings(); true }

		val logoutPreference = preferenceScreen.findPreference<Preference>("psn_logout")
		logoutPreference?.isVisible = preferences.hasNpssoToken()
		logoutPreference?.setOnPreferenceClickListener { showLogoutConfirmation(preferences); true }

		val localePreference = preferenceScreen.findPreference<Preference>("locale_display")
		if (preferences.hasNpssoToken())
			localePreference?.summary = preferences.getCloudLanguage()
		else
			localePreference?.summary = getString(R.string.preferences_locale_summary_not_set)

		// View License
		preferenceScreen.findPreference<Preference>("view_license")?.setOnPreferenceClickListener { viewLicense(); true }
		
	}

	override fun onDestroy()
	{
		super.onDestroy()
		disposable.dispose()
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.title_settings)

	private fun exportSettings()
	{
		val activity = activity ?: return
		exportDisposable.clear()
		exportAndShareAllSettings(activity).addTo(exportDisposable)
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
	}
	
	private fun viewLicense()
	{
		val intent = Intent(requireContext(), LicenseAgreementActivity::class.java)
		intent.putExtra(LicenseAgreementActivity.EXTRA_VIEW_ONLY, true)
		startActivity(intent)
	}

	private fun showLogoutConfirmation(preferences: Preferences)
	{
		requireContext().alertDialogBuilder()
			.setTitle(R.string.preferences_psn_logout_title)
			.setMessage(R.string.preferences_psn_logout_message)
			.setPositiveButton(R.string.preferences_psn_logout_confirm) { _, _ ->
				performLogout(preferences)
			}
			.setNegativeButton(R.string.action_cancel, null)
			.show()
	}

	private fun performLogout(preferences: Preferences)
	{
		preferences.clearNpssoToken()
		preferences.psnAuthToken = ""
		preferences.psnRefreshToken = ""
		preferences.psnAuthTokenExpiry = 0L
		preferences.psnAccountId = ""
		preferenceScreen?.findPreference<Preference>("psn_logout")?.isVisible = false
		Toast.makeText(requireContext(), R.string.preferences_psn_logout_success, Toast.LENGTH_SHORT).show()
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		android.util.Log.i("SettingsFragment", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, hasData=${data != null}")
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, disposable)
			}
		}
	}
	
	/**
	 * Populate cloud datacenter dropdown from saved ping results JSON
	 * Matches Qt behavior of showing discovered datacenters with their ping times
	 */
	private fun populateCloudDatacenterPreference(preference: ListPreference?, datacentersJson: String)
	{
		if (preference == null) return

		try
		{
			if (datacentersJson.isEmpty())
			{
				// No saved datacenters, use default "Auto" only
				preference.entries = arrayOf("Auto (Best Ping)")
				preference.entryValues = arrayOf("Auto")
				return
			}

			// Parse the JSON array of datacenter ping results
			val datacenters = org.json.JSONArray(datacentersJson)
			val entries = mutableListOf<String>()
			val values = mutableListOf<String>()

			// Always add "Auto" as first option
			entries.add("Auto (Best Ping)")
			values.add("Auto")

			// Add each datacenter with its ping time (no IP)
			for (i in 0 until datacenters.length())
			{
				val dc = datacenters.getJSONObject(i)
				val name = dc.optString("dataCenter", "")
				val rtt = dc.optInt("rtt", 0)

				if (name.isNotEmpty())
				{
					// Format: "sjca (36ms)" - just name and ping, no IP
					val displayName = if (rtt > 0 && rtt < 999)
					{
						"$name (${rtt}ms)"
					}
					else
					{
						name
					}

					entries.add(displayName)
					values.add(name)  // Store just the datacenter name as the value
				}
			}

			preference.entries = entries.toTypedArray()
			preference.entryValues = values.toTypedArray()
		}
		catch (e: Exception)
		{
			// If JSON parsing fails, fall back to Auto only
			preference.entries = arrayOf("Auto (Best Ping)")
			preference.entryValues = arrayOf("Auto")
		}
	}

	private fun bindCloudBitratePreference(preference: SeekBarPreference?, preferences: Preferences) {
		if (preference == null) return

		val summaryRes = when (preference.key) {
			preferences.cloudBitratePsnowKey -> R.string.preferences_cloud_bitrate_psnow_summary
			preferences.cloudBitratePscloudKey -> R.string.preferences_cloud_bitrate_pscloud_summary
			else -> return
		}

		preference.summaryProvider = Preference.SummaryProvider<SeekBarPreference> { pref ->
			getString(summaryRes, pref.value)
		}
	}
}