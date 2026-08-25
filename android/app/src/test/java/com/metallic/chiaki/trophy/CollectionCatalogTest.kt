package com.metallic.chiaki.trophy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionCatalogTest {

    @Test
    fun `recognizes a known collection disc and returns its bundled games' candidate names`() {
        val subGames = CollectionCatalog.subGamesFor("The Sly Trilogy", "ps3")
        assertEquals(
            listOf(
                listOf("Sly Cooper and the Thievius Raccoonus"),
                listOf("Sly 2", "Sly 2: Band of Thieves"),
                listOf("Sly 3", "Sly 3: Honor Among Thieves")
            ),
            subGames
        )
    }

    @Test
    fun `does not offer a bare Sly Cooper candidate that collides with an unrelated game`() {
        // Regression test (real account data): a bare "Sly Cooper" candidate for this disc's
        // first game wrongly matched the unrelated, separately released "Sly Cooper: Thieves in
        // Time" (2013) via TrophyMatcher's Pass 3 subsequence rule — "Sly Cooper" is a literal
        // prefix of that different game's real title — because this disc's own PS3 trophy title
        // for game 1 hadn't synced yet to give Pass 1 an exact match to prefer instead. Unlike
        // "Sly 2"/"Sly 3" (no known collision), game 1 must stick to its full, unambiguous name.
        val subGames = CollectionCatalog.subGamesFor("The Sly Trilogy", "ps3")
        assertEquals(listOf("Sly Cooper and the Thievius Raccoonus"), subGames!![0])
    }

    @Test
    fun `each bundled game offers both a bare and a fully-subtitled candidate name`() {
        // Regression test (real account data): Sony registered this disc's third game as the
        // bare "Sly 3", not the fully-subtitled "Sly 3: Honor Among Thieves" the standalone PS4
        // remaster uses — since it's unknown upfront which convention any given disc/game uses,
        // every bundled game must offer more than one candidate to try.
        val subGames = CollectionCatalog.subGamesFor("The Sly Trilogy", "ps3")
        assertTrue(subGames!!.all { it.size >= 1 })
        assertTrue(subGames.any { it.size > 1 })
    }

    @Test
    fun `matches regardless of trademark symbols and casing, same as TrophyMatcher normalize`() {
        val subGames = CollectionCatalog.subGamesFor("uncharted™: the nathan drake collection", "ps4")
        assertEquals(3, subGames?.size)
    }

    @Test
    fun `returns null for the correct collection name on the wrong platform`() {
        // Regression guard: a collection disc must never bleed its sub-game list into a
        // different platform's session, same reasoning as TrophyMatcher's platform guard for
        // ordinary titles (see TrophyMatcherTest's GTA5/PS4-vs-PS5 regression).
        assertNull(CollectionCatalog.subGamesFor("The Sly Trilogy", "ps4"))
        assertNull(CollectionCatalog.subGamesFor("Uncharted: The Nathan Drake Collection", "ps5"))
    }

    @Test
    fun `returns null for an ordinary title that is not a known collection`() {
        assertNull(CollectionCatalog.subGamesFor("Ratchet & Clank: Into the Nexus", "ps3"))
    }

    @Test
    fun `recognizes every configured collection`() {
        assertEquals(3, CollectionCatalog.subGamesFor("Devil May Cry HD Collection", "ps3")?.size)
        assertEquals(3, CollectionCatalog.subGamesFor("Assassin's Creed The Ezio Collection", "ps4")?.size)
    }

    @Test
    fun `does not treat Serious Sam Collection as a split-per-game collection`() {
        // Regression test (real account data): Serious Sam Collection was originally guessed to
        // split per-game like Sly Trilogy/Nathan Drake Collection, but Sony actually gives it one
        // single combined trophy title ("Serious Sam Collection") matching its own catalogue name
        // exactly — the same as Uncharted: Legacy of Thieves Collection. Treating it as a
        // collection meant this path only ever searched for three sub-game names that don't
        // exist, and never tried the real, matching entry at all.
        assertNull(CollectionCatalog.subGamesFor("Serious Sam Collection", "ps4"))
    }
}
