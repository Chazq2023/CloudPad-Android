package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.TrophyCounts
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers TrophyService's cache serialize/deserialize round trip — the only part of the network
 * layer that's pure enough to unit test without hitting Sony's live API.
 */
class TrophyServiceParsingTest {

    private val sample = listOf(
        TrophyTitleSummary(
            npCommunicationId = "NPWR20188_00",
            npServiceName = "trophy2",
            trophyTitleName = "God of War",
            trophyTitleIconUrl = "https://example.com/icon.png",
            trophyTitlePlatform = "PS5",
            hasTrophyGroups = true,
            definedTrophies = TrophyCounts(bronze = 25, silver = 12, gold = 3, platinum = 1),
            earnedTrophies = TrophyCounts(bronze = 10, silver = 2, gold = 0, platinum = 0),
            progressPercent = 42
        ),
        TrophyTitleSummary(
            npCommunicationId = "NPWR00001_00",
            npServiceName = "trophy",
            trophyTitleName = "Uncharted 4",
            trophyTitleIconUrl = "",
            trophyTitlePlatform = "PS4",
            hasTrophyGroups = false,
            definedTrophies = TrophyCounts(),
            earnedTrophies = TrophyCounts(),
            progressPercent = 0
        )
    )

    @Test
    fun `serialize then deserialize round trips every field`() {
        val json = TrophyService.serializeTitles(sample)
        val parsed = TrophyService.deserializeTitles(json)

        assertEquals(sample.size, parsed.size)
        assertEquals(sample, parsed)
    }

    @Test
    fun `deserialize returns empty list for malformed json`() {
        val parsed = TrophyService.deserializeTitles("not valid json")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `deserialize returns empty list for empty array`() {
        val parsed = TrophyService.deserializeTitles("[]")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `deserialize skips entries missing npCommunicationId`() {
        val json = """[{"trophyTitleName":"No Id Game"}]"""
        val parsed = TrophyService.deserializeTitles(json)
        assertTrue(parsed.isEmpty())
    }
}
