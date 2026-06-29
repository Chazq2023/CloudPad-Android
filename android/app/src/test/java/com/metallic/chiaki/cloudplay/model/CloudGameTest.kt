package com.metallic.chiaki.cloudplay.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudGameTest {

    // --- Default field values ---

    @Test
    fun `default platform is ps4`() {
        val game = CloudGame("G1", "Test Game", "https://img.com/g1.jpg")
        assertEquals("ps4", game.platform)
    }

    @Test
    fun `default serviceType is psnow`() {
        val game = CloudGame("G1", "Test Game", "https://img.com/g1.jpg")
        assertEquals("psnow", game.serviceType)
    }

    @Test
    fun `default isOwned is false`() {
        val game = CloudGame("G1", "Test Game", "https://img.com/g1.jpg")
        assertFalse(game.isOwned)
    }

    @Test
    fun `default landscapeImageUrl falls back to imageUrl`() {
        val game = CloudGame("G1", "Test Game", "https://img.com/cover.jpg")
        assertEquals("https://img.com/cover.jpg", game.landscapeImageUrl)
    }

    @Test
    fun `default thumbnailUrl falls back to imageUrl`() {
        val game = CloudGame("G1", "Test Game", "https://img.com/cover.jpg")
        assertEquals("https://img.com/cover.jpg", game.thumbnailUrl)
    }

    @Test
    fun `default conceptUrl is empty string`() {
        val game = CloudGame("G1", "Test Game", "https://img.com/g1.jpg")
        assertEquals("", game.conceptUrl)
    }

    @Test
    fun `default entitlementId is empty string`() {
        val game = CloudGame("G1", "Test Game", "https://img.com/g1.jpg")
        assertEquals("", game.entitlementId)
    }

    // --- Explicit field values override defaults ---

    @Test
    fun `explicit landscapeImageUrl is used when provided`() {
        val game = CloudGame("G1", "Test Game", "https://img.com/cover.jpg",
            landscapeImageUrl = "https://img.com/landscape.jpg")
        assertEquals("https://img.com/landscape.jpg", game.landscapeImageUrl)
    }

    @Test
    fun `explicit platform overrides default`() {
        val ps3Game = CloudGame("G1", "Test Game", "", platform = "ps3")
        val ps5Game = CloudGame("G2", "Test Game", "", platform = "ps5")
        assertEquals("ps3", ps3Game.platform)
        assertEquals("ps5", ps5Game.platform)
    }

    @Test
    fun `isOwned can be set to true`() {
        val game = CloudGame("G1", "Owned Game", "", isOwned = true)
        assertTrue(game.isOwned)
    }

    // --- Data class copy ---

    @Test
    fun `copy changes only the targeted field`() {
        val original = CloudGame("G1", "God of War", "https://img.com/gow.jpg",
            platform = "ps4", serviceType = "psnow", isOwned = false)

        val owned = original.copy(isOwned = true)

        assertEquals(original.productId, owned.productId)
        assertEquals(original.name, owned.name)
        assertEquals(original.imageUrl, owned.imageUrl)
        assertEquals(original.platform, owned.platform)
        assertEquals(original.serviceType, owned.serviceType)
        assertTrue(owned.isOwned)
    }

    @Test
    fun `copy with new platform preserves other fields`() {
        val original = CloudGame("CUSA12345", "Spider-Man", "https://img.com/sm.jpg",
            isOwned = true, serviceType = "pscloud")

        val updated = original.copy(platform = "ps5")

        assertEquals("CUSA12345", updated.productId)
        assertEquals("Spider-Man", updated.name)
        assertEquals("ps5", updated.platform)
        assertTrue(updated.isOwned)
        assertEquals("pscloud", updated.serviceType)
    }

    // --- Equality (data class contract) ---

    @Test
    fun `two instances with identical fields are equal`() {
        val a = CloudGame("G1", "God of War", "https://img.com/gow.jpg", platform = "ps4")
        val b = CloudGame("G1", "God of War", "https://img.com/gow.jpg", platform = "ps4")
        assertEquals(a, b)
    }

    @Test
    fun `instances with different productId are not equal`() {
        val a = CloudGame("G1", "God of War", "https://img.com/gow.jpg")
        val b = CloudGame("G2", "God of War", "https://img.com/gow.jpg")
        assertNotEquals(a, b)
    }

    @Test
    fun `instances with different name are not equal`() {
        val a = CloudGame("G1", "God of War", "https://img.com/img.jpg")
        val b = CloudGame("G1", "Spider-Man", "https://img.com/img.jpg")
        assertNotEquals(a, b)
    }

    @Test
    fun `instances with different isOwned are not equal`() {
        val a = CloudGame("G1", "Game", "", isOwned = false)
        val b = CloudGame("G1", "Game", "", isOwned = true)
        assertNotEquals(a, b)
    }
}
