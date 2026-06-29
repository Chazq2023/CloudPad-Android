package com.metallic.chiaki.cloudplay.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the platform-filtering and sort logic that lives in CloudPlayFragment's game observer.
 * Extracted here as pure functions so they can be verified without a Fragment or ViewModel.
 */
class CloudGameFilteringTest {

    private val ps3Games = (1..3).map {
        CloudGame("PS3-00$it", "PS3 Game $it", "", platform = "ps3", serviceType = "psnow")
    }
    private val ps4Games = (1..5).map {
        CloudGame("PS4-00$it", "PS4 Game $it", "", platform = "ps4", serviceType = "psnow")
    }
    private val ps5Games = listOf(
        CloudGame("PS5-001", "PS5 Owned", "", platform = "ps5", serviceType = "pscloud", isOwned = true),
        CloudGame("PS5-002", "PS5 Unowned", "", platform = "ps5", serviceType = "pscloud", isOwned = false)
    )
    private val allGames = ps3Games + ps4Games + ps5Games

    // Mirrors the platform-filter block in CloudPlayFragment.observeViewModel
    private fun filterBySection(games: List<CloudGame>, section: String): List<CloudGame> = when (section) {
        "psnow_ps3" -> games.filter { it.platform == "ps3" }
        "psnow_ps4" -> games.filter { it.platform == "ps4" }
        else        -> games
    }

    // Mirrors the sortState block in CloudPlayFragment.observeViewModel
    private fun applySortState(games: List<CloudGame>, sortState: Int, section: String): List<CloudGame> =
        when (sortState) {
            0 -> if (section == "pscloud")
                games.sortedWith(compareByDescending { it.isOwned })
            else
                games
            1 -> games.sortedBy { it.name.lowercase() }
            2 -> games.sortedByDescending { it.name.lowercase() }
            else -> games
        }

    // --- Platform filtering ---

    @Test
    fun `PS3 section returns only PS3 games`() {
        val result = filterBySection(allGames, "psnow_ps3")
        assertEquals(3, result.size)
        assertTrue(result.all { it.platform == "ps3" })
    }

    @Test
    fun `PS4 section returns only PS4 games`() {
        val result = filterBySection(allGames, "psnow_ps4")
        assertEquals(5, result.size)
        assertTrue(result.all { it.platform == "ps4" })
    }

    @Test
    fun `PS5 Library section returns all games unfiltered`() {
        assertEquals(allGames.size, filterBySection(allGames, "pscloud").size)
    }

    @Test
    fun `PS3 filter excludes PS4 and PS5 games`() {
        val result = filterBySection(allGames, "psnow_ps3")
        assertTrue(result.none { it.platform == "ps4" })
        assertTrue(result.none { it.platform == "ps5" })
    }

    @Test
    fun `PS4 filter excludes PS3 and PS5 games`() {
        val result = filterBySection(allGames, "psnow_ps4")
        assertTrue(result.none { it.platform == "ps3" })
        assertTrue(result.none { it.platform == "ps5" })
    }

    @Test
    fun `filtering an empty list always returns empty`() {
        assertTrue(filterBySection(emptyList(), "psnow_ps3").isEmpty())
        assertTrue(filterBySection(emptyList(), "psnow_ps4").isEmpty())
        assertTrue(filterBySection(emptyList(), "pscloud").isEmpty())
    }

    // --- Sort: A→Z ---

    @Test
    fun `sort state 1 orders games A to Z`() {
        val games = listOf(
            CloudGame("C", "Zelda", ""),
            CloudGame("A", "Astro's Playroom", ""),
            CloudGame("B", "Batman", "")
        )
        val sorted = applySortState(games, 1, "psnow_ps4")
        assertEquals(listOf("Astro's Playroom", "Batman", "Zelda"), sorted.map { it.name })
    }

    @Test
    fun `sort state 1 is case-insensitive`() {
        val games = listOf(
            CloudGame("B", "zelda", ""),
            CloudGame("A", "Astro", "")
        )
        val sorted = applySortState(games, 1, "psnow_ps4")
        assertEquals("Astro", sorted.first().name)
    }

    // --- Sort: Z→A ---

    @Test
    fun `sort state 2 orders games Z to A`() {
        val games = listOf(
            CloudGame("C", "Zelda", ""),
            CloudGame("A", "Astro's Playroom", ""),
            CloudGame("B", "Batman", "")
        )
        val sorted = applySortState(games, 2, "psnow_ps4")
        assertEquals(listOf("Zelda", "Batman", "Astro's Playroom"), sorted.map { it.name })
    }

    // --- Sort: default (Library owned-first) ---

    @Test
    fun `Library default sort places owned games before unowned`() {
        val games = listOf(
            CloudGame("A", "Unowned Game", "", serviceType = "pscloud", isOwned = false),
            CloudGame("B", "Owned Game",   "", serviceType = "pscloud", isOwned = true)
        )
        val sorted = applySortState(games, 0, "pscloud")
        assertEquals("Owned Game", sorted.first().name)
        assertEquals("Unowned Game", sorted.last().name)
    }

    @Test
    fun `Catalog default sort preserves original order`() {
        val games = listOf(
            CloudGame("G3", "Gamma", ""),
            CloudGame("G1", "Alpha", ""),
            CloudGame("G2", "Beta", "")
        )
        val sorted = applySortState(games, 0, "psnow_ps3")
        assertEquals(listOf("Gamma", "Alpha", "Beta"), sorted.map { it.name })
    }

    // --- Search logic (mirrors ViewModel.applySearchFilter) ---

    @Test
    fun `search by name is case-insensitive`() {
        val games = listOf(CloudGame("G1", "God of War", ""), CloudGame("G2", "Spider-Man", ""))
        val result = games.filter { it.name.contains("GOD", ignoreCase = true) }
        assertEquals(1, result.size)
        assertEquals("God of War", result.first().name)
    }

    @Test
    fun `search by productId matches partial id`() {
        val games = listOf(CloudGame("CUSA12345", "Game A", ""), CloudGame("PPSA99999", "Game B", ""))
        val result = games.filter {
            it.name.contains("CUSA", ignoreCase = true) ||
            it.productId.contains("CUSA", ignoreCase = true)
        }
        assertEquals(1, result.size)
        assertEquals("CUSA12345", result.first().productId)
    }

    @Test
    fun `empty search returns all games`() {
        val games = listOf(CloudGame("G1", "God of War", ""), CloudGame("G2", "Spider-Man", ""))
        val result = games.filter { it.name.contains("", ignoreCase = true) }
        assertEquals(2, result.size)
    }
}
