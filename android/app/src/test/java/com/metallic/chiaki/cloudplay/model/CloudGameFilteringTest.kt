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

    // Mirrors the PS5-Library-only streamability filter block in CloudPlayFragment.observeViewModel
    private fun applyStreamabilityFilter(
        games: List<CloudGame>,
        section: String,
        filterState: Int
    ): List<CloudGame> = if (section != "pscloud") games else when (filterState) {
        1 -> games.filter { it.streamableStatus == StreamableStatus.STREAMABLE }
        2 -> games.filter { it.streamableStatus == StreamableStatus.NOT_STREAMABLE }
        3 -> games.filter { it.streamableStatus == StreamableStatus.UNKNOWN }
        else -> games
    }

    // Mirrors the sortState block in CloudPlayFragment.observeViewModel
    private fun applySortState(
        games: List<CloudGame>,
        sortState: Int,
        lastPlayedMs: Map<String, Long> = emptyMap()
    ): List<CloudGame> =
        when (sortState) {
            1 -> games.sortedByDescending { it.name.lowercase() }
            2 -> games.sortedByDescending { lastPlayedMs[it.productId] ?: 0L }
            else -> games.sortedBy { it.name.lowercase() } // A->Z (default)
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

    // --- PS5 Library streamability filter (All / Streamable / Non-streamable / Not Verified) ---

    private val mixedStatusGames = listOf(
        CloudGame("A", "Confirmed Streamable", "", platform = "ps5", serviceType = "pscloud", streamableStatus = StreamableStatus.STREAMABLE),
        CloudGame("B", "Confirmed Non-streamable", "", platform = "ps5", serviceType = "pscloud", streamableStatus = StreamableStatus.NOT_STREAMABLE),
        CloudGame("C", "Never Attempted", "", platform = "ps5", serviceType = "pscloud", streamableStatus = StreamableStatus.UNKNOWN)
    )

    @Test
    fun `streamability filter state 0 shows all games regardless of status`() {
        val result = applyStreamabilityFilter(mixedStatusGames, "pscloud", 0)
        assertEquals(3, result.size)
    }

    @Test
    fun `streamability filter state 1 shows only streamable games`() {
        val result = applyStreamabilityFilter(mixedStatusGames, "pscloud", 1)
        assertEquals(listOf("Confirmed Streamable"), result.map { it.name })
    }

    @Test
    fun `streamability filter state 2 shows only non-streamable games`() {
        val result = applyStreamabilityFilter(mixedStatusGames, "pscloud", 2)
        assertEquals(listOf("Confirmed Non-streamable"), result.map { it.name })
    }

    @Test
    fun `streamability filter state 3 shows only not-verified games`() {
        val result = applyStreamabilityFilter(mixedStatusGames, "pscloud", 3)
        assertEquals(listOf("Never Attempted"), result.map { it.name })
    }

    @Test
    fun `streamability filter never applies outside the PS5 Library section`() {
        val result = applyStreamabilityFilter(mixedStatusGames, "psnow_ps4", 1)
        assertEquals(3, result.size)
    }

    @Test
    fun `an unrecognised streamability filter state falls back to showing all games`() {
        val result = applyStreamabilityFilter(mixedStatusGames, "pscloud", 99)
        assertEquals(3, result.size)
    }

    // --- Sort: A→Z (default) ---

    @Test
    fun `sort state 0 orders games A to Z`() {
        val games = listOf(
            CloudGame("C", "Zelda", ""),
            CloudGame("A", "Astro's Playroom", ""),
            CloudGame("B", "Batman", "")
        )
        val sorted = applySortState(games, 0)
        assertEquals(listOf("Astro's Playroom", "Batman", "Zelda"), sorted.map { it.name })
    }

    @Test
    fun `sort state 0 is case-insensitive`() {
        val games = listOf(
            CloudGame("B", "zelda", ""),
            CloudGame("A", "Astro", "")
        )
        val sorted = applySortState(games, 0)
        assertEquals("Astro", sorted.first().name)
    }

    @Test
    fun `an unrecognised sort state falls back to A to Z`() {
        val games = listOf(CloudGame("B", "Zelda", ""), CloudGame("A", "Astro", ""))
        val sorted = applySortState(games, 99)
        assertEquals("Astro", sorted.first().name)
    }

    // --- Sort: Z→A ---

    @Test
    fun `sort state 1 orders games Z to A`() {
        val games = listOf(
            CloudGame("C", "Zelda", ""),
            CloudGame("A", "Astro's Playroom", ""),
            CloudGame("B", "Batman", "")
        )
        val sorted = applySortState(games, 1)
        assertEquals(listOf("Zelda", "Batman", "Astro's Playroom"), sorted.map { it.name })
    }

    // --- Sort: Recently Played (works the same for Catalog and Library sections) ---

    @Test
    fun `sort state 2 orders games by most recently played first`() {
        val games = listOf(
            CloudGame("A", "Played Long Ago", "", platform = "ps3"),
            CloudGame("B", "Played Most Recently", "", platform = "ps4"),
            CloudGame("C", "Never Played", "", platform = "ps5", serviceType = "pscloud")
        )
        val lastPlayed = mapOf("A" to 1_000L, "B" to 5_000L) // "C" absent == never played
        val sorted = applySortState(games, 2, lastPlayed)
        assertEquals(
            listOf("Played Most Recently", "Played Long Ago", "Never Played"),
            sorted.map { it.name }
        )
    }

    @Test
    fun `sort state 2 treats games with no recorded playtime as least recent`() {
        val games = listOf(
            CloudGame("A", "Has Playtime", ""),
            CloudGame("B", "No Playtime", "")
        )
        val sorted = applySortState(games, 2, mapOf("A" to 42L))
        assertEquals("Has Playtime", sorted.first().name)
        assertEquals("No Playtime", sorted.last().name)
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
