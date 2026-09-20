package com.metallic.chiaki.cloudplay.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddableGamesTest {

    private fun game(id: String, name: String, owned: Boolean = false, platform: String = "ps5") =
        CloudGame(id, name, "", platform = platform, serviceType = "pscloud", isOwned = owned)

    @Test
    fun `notInLibrary drops owned games`() {
        val games = listOf(game("PPSA1", "Owned Game", owned = true), game("PPSA2", "Not Owned"))

        assertEquals(listOf("PPSA2"), games.notInLibrary().map { it.productId })
    }

    @Test
    fun `notInLibrary is empty when everything is owned`() {
        assertTrue(listOf(game("PPSA1", "A", owned = true)).notInLibrary().isEmpty())
    }

    @Test
    fun `notInLibrary sorts A to Z ignoring case`() {
        val games = listOf(game("3", "zelda"), game("1", "Astro Bot"), game("2", "bloodborne"))

        assertEquals(listOf("Astro Bot", "bloodborne", "zelda"), games.notInLibrary().map { it.name })
    }

    @Test
    fun `notInLibrary dedupes the same productId and platform but keeps other platforms`() {
        val games = listOf(
            game("X1", "Game", platform = "ps5"),
            game("X1", "Game", platform = "ps5"),
            game("X1", "Game", platform = "ps4")
        )

        assertEquals(2, games.notInLibrary().size)
    }

    @Test
    fun `matchingQuery blank returns the full list`() {
        val games = listOf(game("1", "A"), game("2", "B"))

        assertEquals(games, games.matchingQuery(""))
        assertEquals(games, games.matchingQuery("   "))
    }

    @Test
    fun `matchingQuery matches name case-insensitively`() {
        val games = listOf(game("1", "God of War"), game("2", "Gran Turismo"))

        assertEquals(listOf("God of War"), games.matchingQuery("war").map { it.name })
    }

    @Test
    fun `matchingQuery also matches productId`() {
        val games = listOf(game("PPSA0001", "A"), game("PPSA0002", "B"))

        assertEquals(listOf("B"), games.matchingQuery("ppsa0002").map { it.name })
    }

    @Test
    fun `matchingQuery with no hits returns empty`() {
        assertTrue(listOf(game("1", "A")).matchingQuery("zzz").isEmpty())
    }

    // --- excludingLibrary ---

    private fun libGame(id: String, name: String, storeId: String = id, platform: String = "ps5") =
        CloudGame(id, name, "", platform = platform, serviceType = "pscloud", isOwned = true, storeProductId = storeId)

    @Test
    fun `excludingLibrary drops a game sharing a productId despite a different name`() {
        val library = listOf(libGame("EP9000-PPSA15508_00-THELASTOFUSPART2", "The Last of Us Part II Remastered"))
        val catalog = listOf(game("EP9000-PPSA15508_00-THELASTOFUSPART2", "The Last of Us Part II"), game("PPSA9", "Other"))

        assertEquals(listOf("Other"), catalog.excludingLibrary(library).map { it.name })
    }

    @Test
    fun `excludingLibrary matches the library storeProductId`() {
        val library = listOf(libGame("EP5641-PPSA16603_00-GBVSRGAME0000001", "Granblue Fantasy Versus: Rising", storeId = "EP5641-PPSA16603_00-GBVSRSTD00000001"))
        val catalog = listOf(game("EP5641-PPSA16603_00-GBVSRSTD00000001", "Granblue Fantasy Versus: Rising"))

        assertTrue(catalog.excludingLibrary(library).isEmpty())
    }

    @Test
    fun `excludingLibrary matches edition SKUs by PPSA number`() {
        val library = listOf(libGame("UP0006-PPSA19534_00-GLACIERGAME00000", "Battlefield 6", storeId = "UP0006-PPSA34132_00-BATTLEFIELDMULTI"))
        val catalog = listOf(game("UP0006-PPSA19534_00-SANTIAGOSTANDARD", "Battlefield 6 Standard"))

        assertTrue(catalog.excludingLibrary(library).isEmpty())
    }

    @Test
    fun `excludingLibrary matches same name and platform ignoring symbols and case`() {
        val library = listOf(libGame("EP9000-PPSA05031_00-GHOSTSHIP0000000", "Ghost of Tsushima"))
        val catalog = listOf(game("EP9000-PPSA03208_00-GHOSTDIRECTORPS5", "GHOST of Tsushima™"))

        assertTrue(catalog.excludingLibrary(library).isEmpty())
    }

    @Test
    fun `excludingLibrary keeps the same name on a different platform`() {
        val library = listOf(libGame("CUSA00001_00-X", "Same Name", platform = "ps4"))
        val catalog = listOf(game("PPSA00002_00-Y", "Same Name", platform = "ps5"))

        assertEquals(1, catalog.excludingLibrary(library).size)
    }

    @Test
    fun `excludingLibrary with an empty library returns the catalog unchanged`() {
        val catalog = listOf(game("PPSA1", "A"), game("PPSA2", "B"))

        assertEquals(catalog, catalog.excludingLibrary(emptyList()))
    }

    @Test
    fun `excludingLibrary matches a base game against an owned Gold Edition`() {
        val library = listOf(libGame("EP0102-PPSA04405_00-BH7G000000000001", "RESIDENT EVIL 7 biohazard Gold Edition", storeId = "EP0102-PPSA01557_00-RE7VILLAGECOMPGE"))
        val catalog = listOf(game("EP0102-PPSA04401_00-BH70000000000001", "RESIDENT EVIL 7 biohazard"))

        assertTrue(catalog.excludingLibrary(library).isEmpty())
    }

    @Test
    fun `excludingLibrary does not hide other games in the same series`() {
        val library = listOf(libGame("EP0102-PPSA04405_00-BH7G000000000001", "RESIDENT EVIL 7 biohazard Gold Edition"))
        val catalog = listOf(game("PPSA00001_00-A", "Resident Evil"), game("PPSA00002_00-B", "Resident Evil 2"))

        assertEquals(2, catalog.excludingLibrary(library).size)
    }
}
