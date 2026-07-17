package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.Trophy
import com.metallic.chiaki.trophy.model.TrophyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrophyUnlockDiffTest {

    private fun trophy(id: Int, name: String, earned: Boolean) = Trophy(
        trophyId = id,
        groupId = "default",
        type = TrophyType.BRONZE,
        name = name,
        detail = "",
        iconUrl = "",
        hidden = false,
        earned = earned,
        earnedDateTimeMs = if (earned) 1_000L else null
    )

    @Test
    fun `first poll establishes a silent baseline and reports nothing`() {
        val trophies = listOf(trophy(1, "Already Earned", earned = true), trophy(2, "Locked", earned = false))
        val (baseline, unlocked) = TrophyUnlockDiff.diff(null, trophies)
        assertEquals(setOf(1), baseline)
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `a trophy earned since the baseline is reported as newly unlocked`() {
        val baseline = setOf(1)
        val trophies = listOf(trophy(1, "Already Earned", earned = true), trophy(2, "Just Unlocked", earned = true))
        val (updatedBaseline, unlocked) = TrophyUnlockDiff.diff(baseline, trophies)
        assertEquals(setOf(1, 2), updatedBaseline)
        assertEquals(listOf("Just Unlocked"), unlocked.map { it.name })
    }

    @Test
    fun `no newly earned trophies reports an empty list and keeps the same baseline`() {
        val baseline = setOf(1)
        val trophies = listOf(trophy(1, "Already Earned", earned = true), trophy(2, "Still Locked", earned = false))
        val (updatedBaseline, unlocked) = TrophyUnlockDiff.diff(baseline, trophies)
        assertEquals(baseline, updatedBaseline)
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `multiple trophies earned in the same poll are all reported`() {
        val baseline = emptySet<Int>()
        val trophies = listOf(trophy(1, "First", earned = true), trophy(2, "Second", earned = true))
        val (updatedBaseline, unlocked) = TrophyUnlockDiff.diff(baseline, trophies)
        assertEquals(setOf(1, 2), updatedBaseline)
        assertEquals(setOf("First", "Second"), unlocked.map { it.name }.toSet())
    }

    @Test
    fun `a previously unlocked trophy is never reported again on a later poll`() {
        val baseline = setOf(1, 2)
        val trophies = listOf(trophy(1, "Already Earned", earned = true), trophy(2, "Reported Last Time", earned = true))
        val (_, unlocked) = TrophyUnlockDiff.diff(baseline, trophies)
        assertTrue(unlocked.isEmpty())
    }
}
