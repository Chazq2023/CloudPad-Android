// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import com.metallic.chiaki.cloudplay.PsnLoginActivity
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.pylux.stream.BuildConfig
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
		preferences.micEnabledKey -> preferences.micEnabled
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		preferences.pipEnabledKey -> preferences.pipEnabled
		preferences.casSharpeningEnabledKey -> preferences.casSharpeningEnabled
		preferences.fsrEnabledKey -> preferences.fsrEnabled
		preferences.fsrUpscalingEnabledKey -> preferences.fsrUpscalingEnabled
		else -> defValue
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.micEnabledKey -> preferences.micEnabled = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
			preferences.pipEnabledKey -> preferences.pipEnabled = value
			preferences.casSharpeningEnabledKey -> preferences.casSharpeningEnabled = value
			preferences.fsrEnabledKey -> preferences.fsrEnabled = value
			preferences.fsrUpscalingEnabledKey -> preferences.fsrUpscalingEnabled = value
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
		preferences.themeColourKey -> preferences.getThemeColour()
		preferences.imageProcessingKey -> preferences.imageProcessing
		"locale_display" -> preferences.getCloudStoreLocale()
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
			preferences.themeColourKey -> preferences.setThemeColour(value ?: "pink")
			preferences.imageProcessingKey -> preferences.imageProcessing = value ?: "off"
			"locale_display" -> value?.let(preferences::setUserSelectedCloudStoreLocale)
		}
	}

	override fun getInt(key: String, defValue: Int) = when (key) {
		preferences.cloudBitratePscloudKey -> preferences.getCloudBitratePscloud() / 1000
		preferences.cloudBitratePsnowKey -> preferences.getCloudBitratePsnow() / 1000
		preferences.casSharpeningLevelKey -> preferences.casSharpeningLevel
		preferences.fsrSharpeningKey -> preferences.fsrSharpening
		else -> defValue
	}

	override fun putInt(key: String, value: Int) {
		when (key) {
			preferences.cloudBitratePscloudKey -> preferences.setCloudBitratePscloud(value * 1000)
			preferences.cloudBitratePsnowKey -> preferences.setCloudBitratePsnow(value * 1000)
			preferences.casSharpeningLevelKey -> preferences.casSharpeningLevel = value
			preferences.fsrSharpeningKey -> preferences.fsrSharpening = value
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
		private val CLOUD_LOCALES = listOf(
			"en-US" to "English",
			"en-GB" to "English (UK)",
			"de-DE" to "Deutsch",
			"fr-FR" to "Français",
			"fi-FI" to "Suomi",
			"it-IT" to "Italiano",
			"es-ES" to "Español",
			"nl-NL" to "Nederlands",
			"pt-BR" to "Português (BR)",
			"ja-JP" to "日本語",
			"ko-KR" to "한국어"
		)
	}

	private var disposable = CompositeDisposable()
	private var exportDisposable = CompositeDisposable().also { it.addTo(disposable) }

	// Must be registered before the Fragment reaches STARTED, so this is a property initializer
	// rather than something set up inside onCreatePreferences.
	// Must be registered before the Fragment reaches STARTED, same reasoning as
	// micPermissionLauncher below — refreshes the Account row's title/summary either way, since a
	// cancelled/failed login still needs to keep the row saying "Log In" rather than freeze on
	// leftover progress state.
	private val psnLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if(result.resultCode == Activity.RESULT_OK)
		{
			Toast.makeText(requireContext(), R.string.psn_login_success, Toast.LENGTH_SHORT).show()
		}
		updatePsnAccountPreference()
		updateLocalePreference()
	}

	private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		val micPreference = preferenceScreen?.findPreference<SwitchPreference>(getString(R.string.preferences_microphone_enabled_key))
		if(granted)
		{
			Preferences(requireContext()).micEnabled = true
			micPreference?.isChecked = true
		}
		else
		{
			Toast.makeText(requireContext(), R.string.preferences_microphone_permission_denied, Toast.LENGTH_SHORT).show()
		}
	}

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

		val casLevelPreference = preferenceScreen.findPreference<SeekBarPreference>(getString(R.string.preferences_cas_sharpening_level_key))
		val fsrUpscalePreference = preferenceScreen.findPreference<SwitchPreference>(getString(R.string.preferences_fsr_upscaling_enabled_key))
		val fsrSharpenPreference = preferenceScreen.findPreference<SeekBarPreference>(getString(R.string.preferences_fsr_sharpening_key))
		fun showProcessingOptions(mode: String)
		{
			casLevelPreference?.isVisible = mode == "cas"
			fsrUpscalePreference?.isVisible = mode == "fsr"
			fsrSharpenPreference?.isVisible = mode == "fsr"
		}
		showProcessingOptions(preferences.imageProcessing)
		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_image_processing_key))
			?.setOnPreferenceChangeListener { _, newValue ->
				showProcessingOptions(newValue as? String ?: "off")
				true
			}

		val displayMode = requireActivity().windowManager.defaultDisplay.mode
		val displayShortEdge = minOf(displayMode.physicalWidth, displayMode.physicalHeight)
		fun outputSummary(resolution: Int) = when(resolution)
		{
			720 -> getString(R.string.preferences_fsr_output_720)
			1080 -> if(displayShortEdge < 1440) getString(R.string.preferences_fsr_output_downsample, displayShortEdge)
				else getString(R.string.preferences_fsr_output_1080)
			else -> getString(R.string.preferences_fsr_output_unchanged, resolution)
		}
		fun remotePlayHeight(resolution: Preferences.Resolution) = when(resolution)
		{
			Preferences.Resolution.RES_360P -> 360
			Preferences.Resolution.RES_540P -> 540
			Preferences.Resolution.RES_720P -> 720
			Preferences.Resolution.RES_1080P -> 1080
		}
		fun updateUpscaleSummary(remotePlay: Int = remotePlayHeight(preferences.resolution),
			gameLibrary: Int = preferences.getCloudResolutionPscloud(),
			gameCatalog: Int = preferences.getCloudResolutionPsnow())
		{
			fsrUpscalePreference?.summary = getString(
				R.string.preferences_fsr_output_all,
				outputSummary(remotePlay), outputSummary(gameLibrary), outputSummary(gameCatalog)
			)
		}
		updateUpscaleSummary()
		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))
			?.setOnPreferenceChangeListener { _, newValue ->
				Preferences.resolutionAll.firstOrNull { it.value == newValue }?.let { updateUpscaleSummary(remotePlay = remotePlayHeight(it)) }
				true
			}
		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_cloud_resolution_pscloud_key))
			?.setOnPreferenceChangeListener { _, newValue ->
				updateUpscaleSummary(gameLibrary = (newValue as? String)?.toIntOrNull() ?: preferences.getCloudResolutionPscloud()); true
			}
		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_cloud_resolution_psnow_key))
			?.setOnPreferenceChangeListener { _, newValue ->
				updateUpscaleSummary(gameCatalog = (newValue as? String)?.toIntOrNull() ?: preferences.getCloudResolutionPsnow()); true
			}
		preferenceScreen.findPreference<Preference>("reset_image_quality")?.setOnPreferenceClickListener {
			preferences.resetImageQuality(); requireActivity().recreate(); true
		}

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

		updatePsnAccountPreference()
		updateLocalePreference()

		val cachedLocalePreference = preferenceScreen.findPreference<Preference>("cached_locale_display")
		val rawStored = preferences.getRawStoredLocale()
		cachedLocalePreference?.summary = rawStored ?: getString(R.string.preferences_cached_locale_summary_not_set)

		// Static, non-selectable — just reports whatever version was actually installed, read
		// straight from the APK's own manifest rather than duplicated as a preference value.
		preferenceScreen.findPreference<Preference>("app_version")?.summary = BuildConfig.VERSION_NAME

		// View License
		preferenceScreen.findPreference<Preference>("view_license")?.setOnPreferenceClickListener { viewLicense(); true }

		// Theme colour: save first, then recreate so the new theme takes effect
		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_theme_colour_key))
			?.setOnPreferenceChangeListener { _, newValue ->
				preferences.setThemeColour(newValue as? String ?: "pink")
				requireActivity().recreate()
				true
			}

		// Microphone: request RECORD_AUDIO before letting the switch turn on. The preference
		// change is rejected (returns false) until permission is granted — the launcher
		// callback then commits the value and flips the switch itself on grant.
		preferenceScreen.findPreference<SwitchPreference>(getString(R.string.preferences_microphone_enabled_key))
			?.setOnPreferenceChangeListener { _, newValue ->
				val enabling = newValue as? Boolean ?: false
				if(enabling && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
				{
					micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
					false
				}
				else true
			}
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

	/** Single "Account" row that flips between "Log Out" (tap to confirm sign-out) and "Log In"
	 *  (tap to launch [PsnLoginActivity]) depending on [Preferences.hasNpssoToken] — re-called
	 *  after logout and after the login activity returns so the row always reflects current
	 *  state rather than needing the settings screen reopened. */
	private fun updatePsnAccountPreference()
	{
		val preference = preferenceScreen?.findPreference<Preference>("psn_account") ?: return
		val preferences = Preferences(requireContext())

		if(preferences.hasNpssoToken())
		{
			preference.title = getString(R.string.preferences_psn_logout_title)
			preference.summary = getString(R.string.preferences_psn_login_summary_logged_in)
			preference.setIcon(R.drawable.ic_close_white)
			preference.setOnPreferenceClickListener { showLogoutConfirmation(preferences); true }
		}
		else
		{
			preference.title = getString(R.string.preferences_psn_login_row_title)
			preference.summary = getString(R.string.preferences_psn_login_row_summary)
			preference.setIcon(R.drawable.ic_psn_id_white)
			preference.setOnPreferenceClickListener {
				psnLoginLauncher.launch(Intent(requireContext(), PsnLoginActivity::class.java))
				true
			}
		}
	}

	/** Cloud store locale only makes sense once logged in — re-called alongside
	 *  [updatePsnAccountPreference] so it flips enabled/disabled in step with the account row
	 *  rather than only being correct the next time Settings is freshly opened. */
	private fun updateLocalePreference()
	{
		val preferences = Preferences(requireContext())
		val localePreference = preferenceScreen?.findPreference<ListPreference>("locale_display") ?: return
		if (preferences.hasNpssoToken())
		{
			localePreference.entries = CLOUD_LOCALES.map { "${it.second} (${it.first})" }.toTypedArray()
			localePreference.entryValues = CLOUD_LOCALES.map { it.first }.toTypedArray()
			localePreference.value = preferences.getCloudStoreLocale()
			localePreference.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
			localePreference.isEnabled = true
		}
		else
		{
			// The logged-in branch above sets a SummaryProvider, which Preference.setSummary()
			// refuses to run alongside once set — has to be cleared first or this throws
			// IllegalStateException the moment a user logs out with the locale already loaded.
			localePreference.summaryProvider = null
			localePreference.isEnabled = false
			localePreference.summary = getString(R.string.preferences_locale_summary_not_set)
		}
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
		updatePsnAccountPreference()
		updateLocalePreference()
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
