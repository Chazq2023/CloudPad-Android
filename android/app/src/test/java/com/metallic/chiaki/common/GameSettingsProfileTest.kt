package com.metallic.chiaki.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSettingsProfileTest
{
	@Test
	fun `profile round trips all scoped settings`()
	{
		val key = GameProfileKey("PPSA12345", "ps5", "pscloud")
		val profile = GameSettingsProfile(key, "Example Game", 1440, 35000, "{\"CROSS\":1}")

		assertEquals(profile, GameSettingsProfile.fromJson(key, profile.toJson()))
	}

	@Test
	fun `profile key distinguishes platform and service variants`()
	{
		val library = GameProfileKey("same", "ps5", "pscloud")
		val catalog = GameProfileKey("same", "ps4", "psnow")

		assertNotEquals(library.storageKey, catalog.storageKey)
	}

	@Test
	fun `malformed profile is ignored`()
	{
		assertNull(GameSettingsProfile.fromJson(GameProfileKey("id", "ps5", "pscloud"), "not-json"))
	}
}
