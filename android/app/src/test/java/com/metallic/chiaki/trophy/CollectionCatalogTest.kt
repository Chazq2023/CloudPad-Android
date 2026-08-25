package com.metallic.chiaki.trophy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionCatalogTest {

    @Test
    fun `recognizes a known collection disc and returns its bundled games`() {
        val subGames = CollectionCatalog.subGameNamesFor("The Sly Trilogy", "ps3")
        assertEquals(
            listOf(
                "Sly Cooper and the Thievius Raccoonus",
                "Sly 2: Band of Thieves",
                "Sly 3: Honor Among Thieves"
            ),
            subGames
        )
    }

    @Test
    fun `matches regardless of trademark symbols and casing, same as TrophyMatcher normalize`() {
        val subGames = CollectionCatalog.subGameNamesFor("uncharted™: the nathan drake collection", "ps4")
        assertEquals(3, subGames?.size)
    }

    @Test
    fun `returns null for the correct collection name on the wrong platform`() {
        // Regression guard: a collection disc must never bleed its sub-game list into a
        // different platform's session, same reasoning as TrophyMatcher's platform guard for
        // ordinary titles (see TrophyMatcherTest's GTA5/PS4-vs-PS5 regression).
        assertNull(CollectionCatalog.subGameNamesFor("The Sly Trilogy", "ps4"))
        assertNull(CollectionCatalog.subGameNamesFor("Uncharted: The Nathan Drake Collection", "ps5"))
    }

    @Test
    fun `returns null for an ordinary title that is not a known collection`() {
        assertNull(CollectionCatalog.subGameNamesFor("Ratchet & Clank: Into the Nexus", "ps3"))
    }

    @Test
    fun `recognizes every configured collection`() {
        assertEquals(3, CollectionCatalog.subGameNamesFor("Devil May Cry HD Collection", "ps3")?.size)
        assertEquals(3, CollectionCatalog.subGameNamesFor("Assassin's Creed The Ezio Collection", "ps4")?.size)
        assertEquals(3, CollectionCatalog.subGameNamesFor("Serious Sam Collection", "ps4")?.size)
    }
}
