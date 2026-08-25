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
                listOf("Sly Cooper", "Sly Cooper and the Thievius Raccoonus"),
                listOf("Sly 2", "Sly 2: Band of Thieves"),
                listOf("Sly 3", "Sly 3: Honor Among Thieves")
            ),
            subGames
        )
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
        assertEquals(3, CollectionCatalog.subGamesFor("Serious Sam Collection", "ps4")?.size)
    }
}
