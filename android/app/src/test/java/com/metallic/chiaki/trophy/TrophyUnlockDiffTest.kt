package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.Trophy
import com.metallic.chiaki.trophy.model.TrophyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrophyUnlockDiffTest {

    private fun trophy(id: Int, name: String, earned: Boolean, groupId: String = "default") = Trophy(
        trophyId = id,
        groupId = groupId,
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
        assertEquals(setOf("default:1"), baseline)
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `a trophy earned since the baseline is reported as newly unlocked`() {
        val baseline = setOf("default:1")
        val trophies = listOf(trophy(1, "Already Earned", earned = true), trophy(2, "Just Unlocked", earned = true))
        val (updatedBaseline, unlocked) = TrophyUnlockDiff.diff(baseline, trophies)
        assertEquals(setOf("default:1", "default:2"), updatedBaseline)
        assertEquals(listOf("Just Unlocked"), unlocked.map { it.name })
    }

    @Test
    fun `no newly earned trophies reports an empty list and keeps the same baseline`() {
        val baseline = setOf("default:1")
        val trophies = listOf(trophy(1, "Already Earned", earned = true), trophy(2, "Still Locked", earned = false))
        val (updatedBaseline, unlocked) = TrophyUnlockDiff.diff(baseline, trophies)
        assertEquals(baseline, updatedBaseline)
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `multiple trophies earned in the same poll are all reported`() {
        val baseline = emptySet<String>()
        val trophies = listOf(trophy(1, "First", earned = true), trophy(2, "Second", earned = true))
        val (updatedBaseline, unlocked) = TrophyUnlockDiff.diff(baseline, trophies)
        assertEquals(setOf("default:1", "default:2"), updatedBaseline)
        assertEquals(setOf("First", "Second"), unlocked.map { it.name }.toSet())
    }

    @Test
    fun `a previously unlocked trophy is never reported again on a later poll`() {
        val baseline = setOf("default:1", "default:2")
        val trophies = listOf(trophy(1, "Already Earned", earned = true), trophy(2, "Reported Last Time", earned = true))
        val (_, unlocked) = TrophyUnlockDiff.diff(baseline, trophies)
        assertTrue(unlocked.isEmpty())
    }

    @Test
    fun `trophies from different groups with the same numeric id are tracked independently`() {
        // Regression test: a collection disc's merged detail (CollectionCatalog) can contain
        // several bundled games' trophy lists at once, each numbered from a small range like any
        // other title — so the same trophyId can appear in more than one group. A bare-ID key
        // would let an already-earned trophy in one game mask a genuinely new unlock of a
        // different game's trophy sharing that same number.
        val baseline = setOf("uncharted1:1")
        val trophies = listOf(
            trophy(1, "Nathan Drake's Fortune", earned = true, groupId = "uncharted1"),
            trophy(1, "Among Thieves", earned = true, groupId = "uncharted2")
        )
        val (updatedBaseline, unlocked) = TrophyUnlockDiff.diff(baseline, trophies)
        assertEquals(setOf("uncharted1:1", "uncharted2:1"), updatedBaseline)
        assertEquals(listOf("Among Thieves"), unlocked.map { it.name })
    }
}
