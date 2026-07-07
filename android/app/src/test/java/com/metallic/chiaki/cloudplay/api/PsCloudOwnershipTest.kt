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
    fun trialEntitlementsAreFilteredOut() {
        val ents = listOf(entitlement(featureType = 1))
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
    fun ipProductIdEntitlementsAreFilteredOut() {
        val ents = listOf(entitlement(productId = "IP9000-GAME", featureType = 3))
        assertTrue(PsCloudOwnership.filterOwnedPs5Games(ents).isEmpty())
    }

    @Test
    fun subProductIdEntitlementsAreFilteredOut() {
        val ents = listOf(entitlement(productId = "SUB-PREMIUM", featureType = 3))
        assertTrue(PsCloudOwnership.filterOwnedPs5Games(ents).isEmpty())
    }

    @Test
    fun mixedEntitlementsOnlyValidGamesRemain() {
        val ents = listOf(
            entitlement(id = "E1", productId = "PPSA00001_00", featureType = 3),
            entitlement(id = "E2", productId = "PPSA00001_01", featureType = 0),
            entitlement(id = "E3", productId = "PPSA00002_00", featureType = 3, activeFlag = false),
            entitlement(id = "E4", productId = "IP9000-GAME", featureType = 3),
            entitlement(id = "E5", productId = "PPSA00003_00", featureType = 1),
        )
        val filtered = PsCloudOwnership.filterOwnedPs5Games(ents)
        assertEquals(1, filtered.size)
        assertTrue(filtered.any { it.id == "E1" })
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
    fun streamPlatformReturnsPsnowForPs5CatalogWithPs4Entitlement() {
        // GoT: catalog=PPSA03208 (PS5) but user only has a PS4 license (CUSA entitlementId, empty storeProductId)
        val game = pscloudGame(
            productId = "EP9000-PPSA03208_00-GHOSTDIRECTORPS5",
            storeProductId = "",
            entitlementId = "EP9000-CUSA32709_00-GHOSTSHIP0000000",
            featureType = 3
        )
        assertEquals("ps4", PsCloudOwnership.streamPlatform(game))
        assertEquals("psnow", PsCloudOwnership.streamServiceType(game))
    }

    @Test
    fun streamIdentifierSendsCusaEntitlementIdToPsnowForPs5CatalogGame() {
        // GoT: when routed to PSNOW because user has PS4 license, send the CUSA entitlementId
        // to Kamaji — NOT the PS5 catalog productId, which Gaikai would reject
        val game = pscloudGame(
            productId = "EP9000-PPSA03208_00-GHOSTDIRECTORPS5",
            storeProductId = "",
            entitlementId = "EP9000-CUSA32709_00-GHOSTSHIP0000000",
            featureType = 3
        )
        assertEquals("EP9000-CUSA32709_00-GHOSTSHIP0000000", PsCloudOwnership.streamIdentifier(game))
    }

    @Test
    fun streamPlatformReturnsPsnowForCusaEntitlementEvenWithPpsaStoreProductId() {
        // PSN can assign a PS5 ent.productId to a cross-gen entitlement whose ent.id is still PS4.
        // The CUSA entitlementId must take priority over the PPSA storeProductId.
        val game = pscloudGame(
            productId = "EP9000-PPSA03208_00-GHOSTDIRECTORPS5",
            storeProductId = "EP9000-PPSA03208_00-GHOSTDIRECTORPS5",
            entitlementId = "EP9000-CUSA32709_00-GHOSTSHIP0000000",
            featureType = 3
        )
        assertEquals("ps4", PsCloudOwnership.streamPlatform(game))
        assertEquals("psnow", PsCloudOwnership.streamServiceType(game))
    }

    @Test
    fun streamIdentifierSendsCusaEntitlementIdEvenWithNonEmptyPpsaStoreProductId() {
        // Same GoT scenario but ent.productId is non-empty PPSA — must still send CUSA to Kamaji
        val game = pscloudGame(
            productId = "EP9000-PPSA03208_00-GHOSTDIRECTORPS5",
            storeProductId = "EP9000-PPSA03208_00-GHOSTDIRECTORPS5",
            entitlementId = "EP9000-CUSA32709_00-GHOSTSHIP0000000",
            featureType = 3
        )
        assertEquals("EP9000-CUSA32709_00-GHOSTSHIP0000000", PsCloudOwnership.streamIdentifier(game))
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
