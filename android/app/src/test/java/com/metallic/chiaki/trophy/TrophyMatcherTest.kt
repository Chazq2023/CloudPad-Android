package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.TrophyCounts
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrophyMatcherTest {

    private fun title(
        id: String,
        name: String,
        platform: String = "PS4"
    ) = TrophyTitleSummary(
        npCommunicationId = id,
        npServiceName = if (platform.contains("PS5")) "trophy2" else "trophy",
        trophyTitleName = name,
        trophyTitleIconUrl = "",
        trophyTitlePlatform = platform,
        hasTrophyGroups = false,
        definedTrophies = TrophyCounts(),
        earnedTrophies = TrophyCounts(),
        progressPercent = 0
    )

    // --- normalize() ---

    @Test
    fun `normalize lowercases and strips trademark symbols`() {
        assertEquals("god of war", TrophyMatcher.normalize("God of War™"))
    }

    @Test
    fun `normalize strips platform parenthetical suffix`() {
        assertEquals("horizon zero dawn", TrophyMatcher.normalize("Horizon Zero Dawn (PS4)"))
    }

    @Test
    fun `normalize strips edition suffix`() {
        assertEquals("the last of us part ii", TrophyMatcher.normalize("The Last of Us Part II - Digital Edition"))
    }

    @Test
    fun `normalize collapses punctuation and extra whitespace`() {
        assertEquals("uncharted 4 a thief s end", TrophyMatcher.normalize("Uncharted 4:  A Thief's End"))
    }

    // --- findBestMatch() ---

    @Test
    fun `finds exact match by normalized name`() {
        val titles = listOf(title("NPWR001_00", "God of War"), title("NPWR002_00", "Spider-Man"))
        val match = TrophyMatcher.findBestMatch("God of War", "ps4", titles)
        assertEquals("NPWR001_00", match?.npCommunicationId)
    }

    @Test
    fun `match is case and trademark insensitive`() {
        val titles = listOf(title("NPWR001_00", "god of war"))
        val match = TrophyMatcher.findBestMatch("God Of War™", "ps4", titles)
        assertEquals("NPWR001_00", match?.npCommunicationId)
    }

    @Test
    fun `falls back to substring containment when no exact match`() {
        val titles = listOf(title("NPWR001_00", "Marvel's Spider-Man Remastered"))
        val match = TrophyMatcher.findBestMatch("Spider-Man", "ps5", titles)
        assertEquals("NPWR001_00", match?.npCommunicationId)
    }

    @Test
    fun `prefers title matching the requested platform when multiple candidates match`() {
        val titles = listOf(
            title("NPWR-PS4", "Horizon Zero Dawn", platform = "PS4"),
            title("NPWR-PS5", "Horizon Zero Dawn", platform = "PS5")
        )
        val match = TrophyMatcher.findBestMatch("Horizon Zero Dawn", "ps5", titles)
        assertEquals("NPWR-PS5", match?.npCommunicationId)
    }

    @Test
    fun `falls back to first candidate when platform does not disambiguate`() {
        val titles = listOf(title("NPWR-PS4", "Some Game", platform = "PS4"))
        val match = TrophyMatcher.findBestMatch("Some Game", "ps3", titles)
        assertEquals("NPWR-PS4", match?.npCommunicationId)
    }

    @Test
    fun `returns null when nothing matches`() {
        val titles = listOf(title("NPWR001_00", "Completely Different Game"))
        val match = TrophyMatcher.findBestMatch("God of War", "ps4", titles)
        assertNull(match)
    }

    @Test
    fun `returns null for an empty titles list`() {
        assertNull(TrophyMatcher.findBestMatch("God of War", "ps4", emptyList()))
    }

    @Test
    fun `returns null when the game name normalizes to empty`() {
        val titles = listOf(title("NPWR001_00", "God of War"))
        assertNull(TrophyMatcher.findBestMatch("™®©", "ps4", titles))
    }
}
