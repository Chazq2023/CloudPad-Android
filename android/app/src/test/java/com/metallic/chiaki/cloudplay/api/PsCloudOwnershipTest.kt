package com.metallic.chiaki.cloudplay.api

import com.metallic.chiaki.cloudplay.model.CloudGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsCloudOwnershipTest {

    private fun entitlement(
        id: String = "E001",
        productId: String = "PPSA00001_00",
        activeFlag: Boolean = true,
        packageType: String = "PSGD",
        name: String = "Test Game",
        conceptId: String = "",
        featureType: Int = 3,
        skuType: String = ""
    ) = PsCloudOwnership.Entitlement(
        id = id,
        productId = productId,
        activeFlag = activeFlag,
        packageType = packageType,
        name = name,
        conceptId = conceptId,
        featureType = featureType,
        skuType = skuType
    )

    @Test
    fun fullGameEntitlementsPassThrough() {
        val ents = listOf(entitlement(featureType = 3))
        assertEquals(1, PsCloudOwnership.filterOwnedPs5Games(ents).size)
    }

    @Test
    fun trialsByNameAreFilteredOut() {
        val ents = listOf(entitlement(name = "My Game Trial", featureType = 3))
        assertTrue(PsCloudOwnership.filterOwnedPs5Games(ents).isEmpty())
    }

    @Test
    fun demonsSoulsIsNotFilteredByDemoSubstring() {
        // "Demon's Souls" starts with "Demo" but "demo" must match as a whole word only
        val ents = listOf(entitlement(name = "Demon's Souls", featureType = 3))
        assertEquals(1, PsCloudOwnership.filterOwnedPs5Games(ents).size)
    }

    @Test
    fun gameTrialByPackageTypeGtIsFilteredOut() {
        val ents = listOf(entitlement(name = "Avatar: Frontiers of Pandora", packageType = "PSGT", featureType = 1))
        assertTrue(PsCloudOwnership.filterOwnedPs5Games(ents).isEmpty())
    }

    @Test
    fun gameTrialBySkuTypeIsFilteredOut() {
        // PSN entitlements with sku_type="GAME_TRIAL" are excluded (different from Game Trials
        // added via PS Store, which are valid streaming entries and should pass through).
        val ents = listOf(entitlement(name = "Avatar: Frontiers of Pandora", featureType = 1, skuType = "GAME_TRIAL"))
        assertTrue(PsCloudOwnership.filterOwnedPs5Games(ents).isEmpty())
    }

    @Test
    fun demoEntitlementsAreFilteredOut() {
        val ents = listOf(entitlement(name = "My Game Demo", featureType = 3))
        assertTrue(PsCloudOwnership.filterOwnedPs5Games(ents).isEmpty())
    }

    @Test
    fun discUpgradeEntitlementsPassThrough() {
        val ents = listOf(entitlement(featureType = 5))
        assertEquals(1, PsCloudOwnership.filterOwnedPs5Games(ents).size)
    }

    @Test
    fun dlcAddOnEntitlementsAreFilteredOut() {
        val ents = listOf(entitlement(featureType = 0))
        assertTrue(PsCloudOwnership.filterOwnedPs5Games(ents).isEmpty())
    }

    @Test
    fun inactiveEntitlementsAreFilteredOut() {
        val ents = listOf(entitlement(activeFlag = false, featureType = 3))
        assertTrue(PsCloudOwnership.filterOwnedPs5Games(ents).isEmpty())
    }

    @Test
    fun subscriptionEntitlementsWithFeatureType1PassThrough() {
        // PS Plus subscription access entitlements and Game Trials (both featureType=1) must
        // pass through — Game Trials are valid streaming entries on the PS Portal.
        val ents = listOf(entitlement(name = "Ghost of Tsushima Director's Cut", featureType = 1))
        assertEquals(1, PsCloudOwnership.filterOwnedPs5Games(ents).size)
    }

    @Test
    fun subscriptionEntitlementsWithIpPrefixPassThrough() {
        // IP-prefix product IDs are subscription markers; the imagic cross-reference
        // naturally excludes anything not in the streaming catalog
        val ents = listOf(entitlement(productId = "IP9000-GAME", featureType = 3))
        assertEquals(1, PsCloudOwnership.filterOwnedPs5Games(ents).size)
    }

    @Test
    fun mixedEntitlementsOnlyValidGamesRemain() {
        val ents = listOf(
            entitlement(id = "E1", productId = "PPSA00001_00", featureType = 3),
            entitlement(id = "E2", productId = "PPSA00001_01", featureType = 0),   // DLC → filtered
            entitlement(id = "E3", productId = "PPSA00002_00", featureType = 3, activeFlag = false),  // inactive → filtered
            entitlement(id = "E4", productId = "IP9000-GAME", featureType = 3),   // IP prefix → now kept
            entitlement(id = "E5", productId = "PPSA00003_00", featureType = 1, name = "My Game Trial"),  // trial name → filtered
        )
        val filtered = PsCloudOwnership.filterOwnedPs5Games(ents)
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.id == "E1" })
        assertTrue(filtered.any { it.id == "E4" })
    }

    private fun pscloudGame(
        productId: String,
        storeProductId: String = "",
        entitlementId: String = "",
        featureType: Int = 3
    ) = CloudGame(
        productId = productId,
        name = "Test",
        imageUrl = "",
        serviceType = "pscloud",
        storeProductId = storeProductId,
        entitlementId = entitlementId,
        featureType = featureType
    )

    @Test
    fun streamingIdentifierUsesProductIdForOrdinaryOwnedGame() {
        val game = pscloudGame(
            productId = "EP0082-PPSA08668_00-CATALOGID00000",
            storeProductId = "EP0082-PPSA08668_00-0978938405039882"
        )
        assertEquals("EP0082-PPSA08668_00-CATALOGID00000", PsCloudOwnership.streamingIdentifier(game))
    }

    @Test
    fun streamingIdentifierUsesStoreProductIdForDiscUpgradeRescue() {
        // featureType=5 and storeProductId differs from productId → disc-upgrade rescue
        val game = pscloudGame(
            productId = "EP0082-PPSA01521_00-DISCUPGRADE",
            storeProductId = "EP0082-PPSA17903_00-FULLGAME",
            featureType = 5
        )
        assertEquals("EP0082-PPSA17903_00-FULLGAME", PsCloudOwnership.streamingIdentifier(game))
    }

    @Test
    fun streamingIdentifierUsesProductIdWhenTitleIdsDifferButNotDiscUpgrade() {
        // Ghost of Tsushima case: two different PS5 SKUs for the same game (featureType=3).
        // The catalog productId from all-ps5-list must win, not the PS Plus subscription SKU.
        val game = pscloudGame(
            productId = "EP9000-PPSA03208_00-GHOSTDIRECTORPS5",
            storeProductId = "EP9000-PPSA05031_00-GHOSTDCPS5PSPLUS",
            featureType = 3
        )
        assertEquals("EP9000-PPSA03208_00-GHOSTDIRECTORPS5", PsCloudOwnership.streamingIdentifier(game))
    }

    @Test
    fun streamingIdentifierFallsBackToProductIdWhenStoreProductIdEmpty() {
        val game = pscloudGame(productId = "EP9000-PPSA01285_00-RETURNALGAME0001")
        assertEquals("EP9000-PPSA01285_00-RETURNALGAME0001", PsCloudOwnership.streamingIdentifier(game))
    }

    @Test
    fun streamingIdentifierUsesProductIdForPsnowGames() {
        val game = CloudGame(
            productId = "CUSA12345_00-MYGAME",
            name = "Test",
            imageUrl = "",
            serviceType = "psnow"
        )
        assertEquals("CUSA12345_00-MYGAME", PsCloudOwnership.streamingIdentifier(game))
    }

    @Test
    fun crossReferenceMatchesBareSkuToFullEpFormatCatalogEntry() {
        // PSN entitlement API returns bare "PPSA01350_00" but imagic catalog has
        // "EP0036-PPSA01350_00-DEMONSSOULSPS5" — the stable key must bridge the gap.
        val catalogGame = CloudGame(
            productId = "EP0036-PPSA01350_00-DEMONSSOULSPS5",
            name = "Demon's Souls",
            imageUrl = "",
            serviceType = "pscloud",
            conceptId = "10001234"
        )
        val ent = entitlement(
            id = "EP0036-PPSA01350_00-0123456789012345",
            productId = "PPSA01350_00",
            name = "Demon's Souls",
            conceptId = "10001234",
            featureType = 1
        )
        val result = PsCloudOwnership.crossReferenceOwnedGames(
            filteredEntitlements = listOf(ent),
            publicCatalog = listOf(catalogGame)
        )
        assertEquals(1, result.size)
        assertEquals("EP0036-PPSA01350_00-DEMONSSOULSPS5", result[0].productId)
    }

    @Test
    fun crossReferenceKeepsSeparateGamesWithSharedConceptId() {
        // TimeSplitters 1, 2, and FP share a conceptId in Sony's data.
        // Each must resolve to its own catalog entry via stable key (PPSA number) rather
        // than all collapsing to the first conceptId match.
        val ts1 = CloudGame(productId = "EP0082-PPSA11111_00-TIMESPLIT1", name = "TimeSplitters", imageUrl = "", serviceType = "pscloud", conceptId = "9000001")
        val ts2 = CloudGame(productId = "EP0082-PPSA22222_00-TIMESPLIT2", name = "TimeSplitters 2", imageUrl = "", serviceType = "pscloud", conceptId = "9000001")
        val tsFP = CloudGame(productId = "EP0082-PPSA33333_00-TIMESPLITFP", name = "TimeSplitters: Future Perfect", imageUrl = "", serviceType = "pscloud", conceptId = "9000001")
        val ent1 = entitlement(id = "E1", productId = "PPSA11111_00", name = "TimeSplitters", conceptId = "9000001", featureType = 1)
        val ent2 = entitlement(id = "E2", productId = "PPSA22222_00", name = "TimeSplitters 2", conceptId = "9000001", featureType = 1)
        val entFP = entitlement(id = "E3", productId = "PPSA33333_00", name = "TimeSplitters: Future Perfect", conceptId = "9000001", featureType = 1)
        val result = PsCloudOwnership.crossReferenceOwnedGames(
            filteredEntitlements = listOf(ent1, ent2, entFP),
            publicCatalog = listOf(ts1, ts2, tsFP)
        )
        assertEquals(3, result.size)
        assertTrue(result.any { it.productId == "EP0082-PPSA11111_00-TIMESPLIT1" })
        assertTrue(result.any { it.productId == "EP0082-PPSA22222_00-TIMESPLIT2" })
        assertTrue(result.any { it.productId == "EP0082-PPSA33333_00-TIMESPLITFP" })
    }

    @Test
    fun supplementMatchRequiresFeatureType3() {
        // Games in the supplement (streamingSupported=false in all-ps5-list) must only
        // appear for outright owners (featureType=3). Subscription access (featureType=1,
        // e.g. EA Play, Ubisoft+ Classics) must not match supplement entries.
        val supplementGame = CloudGame(
            productId = "UP0006-PPSA26127_00-MADDENNFL26GAME0",
            name = "Madden NFL 26",
            imageUrl = "",
            serviceType = "pscloud",
            conceptId = "10009999",
            plusCatalog = true
        )
        val ownedEnt = entitlement(
            id = "E1",
            productId = "PPSA26127_00",
            name = "Madden NFL 26",
            conceptId = "10009999",
            featureType = 3
        )
        val result = PsCloudOwnership.crossReferenceOwnedGames(
            filteredEntitlements = listOf(ownedEnt),
            publicCatalog = emptyList(),
            plusLibrarySupplement = listOf(supplementGame)
        )
        assertEquals(1, result.size)
        assertEquals("UP0006-PPSA26127_00-MADDENNFL26GAME0", result[0].productId)
    }

    @Test
    fun supplementDoesNotMatchFeatureType1() {
        // EA Play / Ubisoft+ Classics subscription access (featureType=1) must NOT match
        // supplement entries. These games are not streamable via PS Cloud for subscribers.
        val supplementGame = CloudGame(
            productId = "UP0006-PPSA26127_00-MADDENNFL26GAME0",
            name = "Madden NFL 26",
            imageUrl = "",
            serviceType = "pscloud",
            conceptId = "10009999",
            plusCatalog = true
        )
        val subscriptionEnt = entitlement(
            id = "E1",
            productId = "PPSA26127_00",
            name = "Madden NFL 26",
            conceptId = "10009999",
            featureType = 1
        )
        val result = PsCloudOwnership.crossReferenceOwnedGames(
            filteredEntitlements = listOf(subscriptionEnt),
            publicCatalog = emptyList(),
            plusLibrarySupplement = listOf(supplementGame)
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun platformTokenDetectsPs5() {
        assertEquals("ps5", PsCloudOwnership.platformToken("PPSA01234_00"))
    }

    @Test
    fun platformTokenDetectsPs4() {
        assertEquals("ps4", PsCloudOwnership.platformToken("CUSA12345_00"))
    }

    @Test
    fun platformTokenReturnsEmptyForUnknown() {
        assertEquals("", PsCloudOwnership.platformToken("NPUA12345"))
    }
}
