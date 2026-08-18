// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.common.GameProfileKey
import com.metallic.chiaki.common.GameSettingsProfile
import com.metallic.chiaki.common.GameSettingsProfileStore
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.session.PhysicalInput
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityGameProfileSettingsBinding

class GameProfileSettingsActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_PRODUCT_ID = "product_id"
		const val EXTRA_PLATFORM = "platform"
		const val EXTRA_SERVICE_TYPE = "service_type"
		const val EXTRA_GAME_NAME = "game_name"
	}

	private lateinit var binding: ActivityGameProfileSettingsBinding
	private lateinit var key: GameProfileKey
	private lateinit var gameName: String
	private lateinit var mappingJson: String
	private lateinit var resolutionValues: List<Int>

	private val remapLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		result.data?.getStringExtra(ControllerRemapActivity.EXTRA_MAPPING_JSON)?.let { mappingJson = it }
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val preferences = Preferences(this)
		if (preferences.getThemeColour() != "pink") setTheme(preferences.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		val productId = intent.getStringExtra(EXTRA_PRODUCT_ID) ?: return finish()
		val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: return finish()
		val serviceType = intent.getStringExtra(EXTRA_SERVICE_TYPE) ?: return finish()
		gameName = intent.getStringExtra(EXTRA_GAME_NAME).orEmpty()
		key = GameProfileKey(productId, platform, serviceType)

		binding = ActivityGameProfileSettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		setSupportActionBar(binding.toolbar)
		binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
		binding.titleTextView.setText(R.string.game_profile_settings_title)
		binding.gameNameTextView.text = gameName

		val profileStore = GameSettingsProfileStore(this)
		val existing = profileStore.get(key)
		resolutionValues = if (serviceType == "pscloud") listOf(720, 1080, 1440, 2160) else listOf(720, 1080)
		binding.resolutionSpinner.adapter = ArrayAdapter(
			this, android.R.layout.simple_spinner_dropdown_item, resolutionValues.map { "${it}p" }
		)
		val globalResolution = if (serviceType == "pscloud") preferences.getCloudResolutionPscloud()
		else preferences.getCloudResolutionPsnow()
		val resolution = existing?.resolution ?: globalResolution
		binding.resolutionSpinner.setSelection(resolutionValues.indexOf(resolution).coerceAtLeast(0))

		val globalBitrate = if (serviceType == "pscloud") preferences.getCloudBitratePscloud()
		else preferences.getCloudBitratePsnow()
		val bitrateMbps = ((existing?.bitrateKbps ?: globalBitrate) / 1000).coerceIn(2, 200)
		binding.bitrateSeekBar.progress = bitrateMbps - 2
		updateBitrateLabel(bitrateMbps)
		binding.bitrateSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener
		{
			override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateBitrateLabel(progress + 2)
			override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
			override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
		})

		mappingJson = existing?.controllerMappingJson ?: PhysicalInput.mappingToJson(
			PhysicalInput.resolveMapping(preferences.loadControllerMapping())
		)
		binding.remapControllerButton.setOnClickListener {
			remapLauncher.launch(Intent(this, ControllerRemapActivity::class.java).apply {
				putExtra(ControllerRemapActivity.EXTRA_PROFILE_EDIT_MODE, true)
				putExtra(ControllerRemapActivity.EXTRA_MAPPING_JSON, mappingJson)
			})
		}
		binding.saveProfileButton.setOnClickListener {
			profileStore.save(GameSettingsProfile(
				key, gameName,
				resolutionValues[binding.resolutionSpinner.selectedItemPosition],
				(binding.bitrateSeekBar.progress + 2) * 1000,
				mappingJson
			))
			Toast.makeText(this, R.string.game_profile_saved, Toast.LENGTH_SHORT).show()
			setResult(RESULT_OK)
			finish()
		}
		binding.removeProfileButton.visibility = if (existing != null) View.VISIBLE else View.GONE
		binding.removeProfileButton.setOnClickListener {
			profileStore.remove(key)
			Toast.makeText(this, R.string.game_profile_removed, Toast.LENGTH_SHORT).show()
			setResult(RESULT_OK)
			finish()
		}
	}

	private fun updateBitrateLabel(valueMbps: Int)
	{
		binding.bitrateTextView.text = getString(R.string.preferences_bitrate_title) + ": $valueMbps Mbps"
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
		{
			onBackPressedDispatcher.onBackPressed()
			return true
		}
		return super.dispatchKeyEvent(event)
	}
}
