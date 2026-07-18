package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.TrophyCounts
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrophyCompareRepositoryTest {

    private fun title(
        npCommunicationId: String,
        name: String,
        progress: Int = 0,
        earned: TrophyCounts = TrophyCounts()
    ) = TrophyTitleSummary(
        npCommunicationId = npCommunicationId,
        npServiceName = "trophy2",
        trophyTitleName = name,
        trophyTitleIconUrl = "",
        trophyTitlePlatform = "PS5",
        hasTrophyGroups = false,
        definedTrophies = TrophyCounts(),
        earnedTrophies = earned,
        progressPercent = progress
    )

    @Test
    fun `only games present on both accounts are matched`() {
        val mine = listOf(
            title("NPWR001", "Elden Ring"),
            title("NPWR002", "God of War Ragnarok"),
            title("NPWR003", "Only Mine")
        )
        val theirs = listOf(
            title("NPWR001", "Elden Ring"),
            title("NPWR002", "God of War Ragnarok"),
            title("NPWR004", "Only Theirs")
        )

        val shared = matchSharedGames(mine, theirs)

        assertEquals(setOf("Elden Ring", "God of War Ragnarok"), shared.map { it.gameName }.toSet())
        assertEquals(2, shared.size)
    }

    @Test
    fun `no overlap produces an empty list`() {
        val mine = listOf(title("NPWR001", "Elden Ring"))
        val theirs = listOf(title("NPWR002", "Bloodborne"))

        assertTrue(matchSharedGames(mine, theirs).isEmpty())
    }

    @Test
    fun `either side being empty produces an empty list`() {
        val mine = listOf(title("NPWR001", "Elden Ring"))

        assertTrue(matchSharedGames(mine, emptyList()).isEmpty())
        assertTrue(matchSharedGames(emptyList(), mine).isEmpty())
    }

    @Test
    fun `matched entries carry each side's own progress and earned counts`() {
        val mine = listOf(title("NPWR001", "Elden Ring", progress = 62, earned = TrophyCounts(bronze = 10, platinum = 1)))
        val theirs = listOf(title("NPWR001", "Elden Ring", progress = 88, earned = TrophyCounts(bronze = 20)))

        val shared = matchSharedGames(mine, theirs).single()

        assertEquals(62, shared.myProgressPercent)
        assertEquals(88, shared.theirProgressPercent)
        assertEquals(10, shared.myEarned.bronze)
        assertEquals(1, shared.myEarned.platinum)
        assertEquals(20, shared.theirEarned.bronze)
    }

    @Test
    fun `shared games are sorted by combined trophies earned, highest first`() {
        val mine = listOf(
            title("NPWR001", "Barely Played", earned = TrophyCounts(bronze = 1)),
            title("NPWR002", "Heavily Played", earned = TrophyCounts(bronze = 50, gold = 10)),
            title("NPWR003", "Middling", earned = TrophyCounts(bronze = 10))
        )
        val theirs = listOf(
            title("NPWR001", "Barely Played", earned = TrophyCounts(bronze = 1)),
            title("NPWR002", "Heavily Played", earned = TrophyCounts(bronze = 40)),
            title("NPWR003", "Middling", earned = TrophyCounts(bronze = 5))
        )

        val shared = matchSharedGames(mine, theirs)

        assertEquals(listOf("Heavily Played", "Middling", "Barely Played"), shared.map { it.gameName })
    }
}
