package com.metallic.chiaki.cloudplay.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddableGamesTest {

    private fun game(id: String, name: String, owned: Boolean = false, platform: String = "ps5") =
        CloudGame(id, name, "", platform = platform, serviceType = "pscloud", isOwned = owned)

    @Test
    fun `notInLibrary drops owned games`() {
        val games = listOf(game("PPSA1", "Owned Game", owned = true), game("PPSA2", "Not Owned"))

        assertEquals(listOf("PPSA2"), games.notInLibrary().map { it.productId })
    }

    @Test
    fun `notInLibrary is empty when everything is owned`() {
        assertTrue(listOf(game("PPSA1", "A", owned = true)).notInLibrary().isEmpty())
    }

    @Test
    fun `notInLibrary sorts A to Z ignoring case`() {
        val games = listOf(game("3", "zelda"), game("1", "Astro Bot"), game("2", "bloodborne"))

        assertEquals(listOf("Astro Bot", "bloodborne", "zelda"), games.notInLibrary().map { it.name })
    }

    @Test
    fun `notInLibrary dedupes the same productId and platform but keeps other platforms`() {
        val games = listOf(
            game("X1", "Game", platform = "ps5"),
            game("X1", "Game", platform = "ps5"),
            game("X1", "Game", platform = "ps4")
        )

        assertEquals(2, games.notInLibrary().size)
    }

    @Test
    fun `matchingQuery blank returns the full list`() {
        val games = listOf(game("1", "A"), game("2", "B"))

        assertEquals(games, games.matchingQuery(""))
        assertEquals(games, games.matchingQuery("   "))
    }

    @Test
    fun `matchingQuery matches name case-insensitively`() {
        val games = listOf(game("1", "God of War"), game("2", "Gran Turismo"))

        assertEquals(listOf("God of War"), games.matchingQuery("war").map { it.name })
    }

    @Test
    fun `matchingQuery also matches productId`() {
        val games = listOf(game("PPSA0001", "A"), game("PPSA0002", "B"))

        assertEquals(listOf("B"), games.matchingQuery("ppsa0002").map { it.name })
    }

    @Test
    fun `matchingQuery with no hits returns empty`() {
        assertTrue(listOf(game("1", "A")).matchingQuery("zzz").isEmpty())
    }
}
