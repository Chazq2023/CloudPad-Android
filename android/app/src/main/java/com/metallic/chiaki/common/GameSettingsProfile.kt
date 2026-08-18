// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.session.ControllerAction
import com.metallic.chiaki.session.PhysicalInput
import org.json.JSONObject

data class GameProfileKey(val productId: String, val platform: String, val serviceType: String)
{
	val storageKey: String get() = "$serviceType|$platform|$productId"

	companion object
	{
		fun from(game: CloudGame) = GameProfileKey(game.productId, game.platform, game.serviceType)
	}
}

data class GameSettingsProfile(
	val key: GameProfileKey,
	val gameName: String,
	val resolution: Int,
	val bitrateKbps: Int,
	val controllerMappingJson: String
)
{
	fun controllerMapping(): Map<ControllerAction, PhysicalInput> =
		PhysicalInput.mappingFromJson(controllerMappingJson)

	fun toJson(): String = JSONObject()
		.put("gameName", gameName)
		.put("resolution", resolution)
		.put("bitrateKbps", bitrateKbps)
		.put("controllerMapping", controllerMappingJson)
		.toString()

	companion object
	{
		fun fromJson(key: GameProfileKey, json: String): GameSettingsProfile?
		{
			return try
			{
				val value = JSONObject(json)
				GameSettingsProfile(
					key,
					value.optString("gameName"),
					value.getInt("resolution"),
					value.getInt("bitrateKbps"),
					value.getString("controllerMapping")
				)
			}
			catch (_: Exception)
			{
				null
			}
		}
	}
}

class GameSettingsProfileStore(context: Context)
{
	private val storage = context.getSharedPreferences("game_settings_profiles", Context.MODE_PRIVATE)

	fun get(key: GameProfileKey): GameSettingsProfile?
	{
		val json = storage.getString(key.storageKey, null) ?: return null
		return GameSettingsProfile.fromJson(key, json)
	}

	fun has(key: GameProfileKey) = storage.contains(key.storageKey)

	fun save(profile: GameSettingsProfile)
	{
		storage.edit().putString(profile.key.storageKey, profile.toJson()).apply()
	}

	fun remove(key: GameProfileKey)
	{
		storage.edit().remove(key.storageKey).apply()
	}
}
