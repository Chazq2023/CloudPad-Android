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
        featureType: Int = 3
    ) = PsCloudOwnership.Entitlement(
        id = id,
        productId = productId,
        activeFlag = activeFlag,
        packageType = packageType,
        name = name,
        conceptId = conceptId,
        featureType = featureType
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
        // PS Plus subscription access entitlements (featureType=1) carry the Gaikai streaming key
        // in their id field — they must not be filtered so the pscloud retry can use that key
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
