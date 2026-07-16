package com.metallic.chiaki.cloudplay.api

import com.metallic.chiaki.cloudplay.model.CloudGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the override/imagic-catalog dedup logic in PsCloudCatalogService.fetchPs5CloudCatalog
 * (mirrored here as a pure function since the real one is a private suspend function driving
 * live network calls — see CloudGameFilteringTest for the same pattern used elsewhere).
 *
 * Regression coverage for: hardcoded catalog overrides (Witcher 3, Nioh 2, RE7 Gold Edition)
 * showing up as duplicate rows alongside Sony's own imagic catalog entry for the same game,
 * because the override's productId/name deliberately differs from Sony's own listing (e.g.
 * Witcher 3: Sony's GB catalog lists PPSA10408 while the override corrects to the user's real
 * entitlement PPSA03977 — both are the same game, conceptId 204794, confirmed by querying the
 * live imagic endpoint). The imagic-derived entry must be dropped once an override for the
 * same conceptId+platform exists, so only one (working) row is shown per game.
 */
class PsCloudCatalogOverrideDedupeTest {

    // Mirrors PsCloudCatalogService.fetchPs5CloudCatalog's browseGames construction
    private fun buildBrowseGames(imagicGames: List<CloudGame>, overrideGames: List<CloudGame>): List<CloudGame> {
        val overrideConceptKeys = overrideGames
            .filter { it.conceptId.isNotEmpty() }
            .map { "${it.conceptId}|${it.platform}" }
            .toSet()
        return imagicGames.filterNot { "${it.conceptId}|${it.platform}" in overrideConceptKeys } + overrideGames
    }

    private val imagicWitcher = CloudGame(
        productId = "EP4497-PPSA10408_00-00000000000000N1",
        name = "The Witcher 3: Wild Hunt",
        imageUrl = "", platform = "ps5", serviceType = "pscloud", conceptId = "204794"
    )
    private val overrideWitcher = CloudGame(
        productId = "EP4497-PPSA03977_00-00000000000GOTY8",
        name = "The Witcher 3: Wild Hunt",
        imageUrl = "", platform = "ps5", serviceType = "pscloud", conceptId = "204794"
    )

    @Test
    fun `override replaces the matching imagic entry instead of duplicating it`() {
        val result = buildBrowseGames(imagicGames = listOf(imagicWitcher), overrideGames = listOf(overrideWitcher))

        assertEquals(1, result.size)
        assertEquals(overrideWitcher.productId, result.first().productId)
    }

    @Test
    fun `override with different name than imagic entry still dedupes via conceptId`() {
        // Nioh 2 case: imagic calls it "Nioh 2", override calls it "Nioh 2 Remastered – The Complete Edition"
        val imagicNioh = CloudGame(
            productId = "EP9000-PPSA02486_00-NIOH2CE000000000", name = "Nioh 2",
            imageUrl = "", platform = "ps5", serviceType = "pscloud", conceptId = "234389"
        )
        val overrideNioh = CloudGame(
            productId = "EP9000-PPSA02488_00-NIOH2EU000000000", name = "Nioh 2 Remastered – The Complete Edition",
            imageUrl = "", platform = "ps5", serviceType = "pscloud", conceptId = "234389"
        )

        val result = buildBrowseGames(imagicGames = listOf(imagicNioh), overrideGames = listOf(overrideNioh))

        assertEquals(1, result.size)
        assertEquals("Nioh 2 Remastered – The Complete Edition", result.first().name)
    }

    @Test
    fun `override with identical productId to the imagic entry does not create an exact duplicate`() {
        // Covers an override that has become fully redundant (imagic now returns the exact
        // same productId/conceptId the override already hardcodes) — it should still collapse
        // to a single row rather than showing the same game twice.
        val liveImagic = CloudGame(
            productId = "EP0001-PPSA28183_00-GAME000000000000", name = "Assassin's Creed Black Flag Resynced",
            imageUrl = "", platform = "ps5", serviceType = "pscloud", conceptId = "10013987"
        )
        val override = liveImagic.copy()

        val result = buildBrowseGames(imagicGames = listOf(liveImagic), overrideGames = listOf(override))

        assertEquals(1, result.size)
    }

    @Test
    fun `unrelated imagic entries are left untouched`() {
        val unrelated = CloudGame(
            productId = "EP1001-PPSA99999_00-SOMEOTHERGAME00", name = "Some Other Game",
            imageUrl = "", platform = "ps5", serviceType = "pscloud", conceptId = "999999"
        )

        val result = buildBrowseGames(
            imagicGames = listOf(imagicWitcher, unrelated),
            overrideGames = listOf(overrideWitcher)
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.productId == unrelated.productId })
        assertTrue(result.any { it.productId == overrideWitcher.productId })
        assertTrue(result.none { it.productId == imagicWitcher.productId })
    }

    @Test
    fun `override with no conceptId (delisted game) does not remove any imagic entry`() {
        val delisted = CloudGame(
            productId = "EP9000-PPSA02630_00-DALLSTARSPLUS001", name = "Destruction AllStars",
            imageUrl = "", platform = "ps5", serviceType = "pscloud"
            // conceptId intentionally left blank, mirroring DELISTED_STREAMABLE_GAMES entries
        )

        val result = buildBrowseGames(imagicGames = listOf(imagicWitcher), overrideGames = listOf(delisted))

        assertEquals(2, result.size)
        assertTrue(result.any { it.productId == imagicWitcher.productId })
        assertTrue(result.any { it.productId == delisted.productId })
    }

    @Test
    fun `same conceptId but different platform does not dedupe`() {
        val ps4Version = imagicWitcher.copy(
            productId = "EP4497-CUSA10408_00-00000000000000N1", platform = "ps4"
        )

        val result = buildBrowseGames(imagicGames = listOf(ps4Version), overrideGames = listOf(overrideWitcher))

        assertEquals(2, result.size)
    }
}
