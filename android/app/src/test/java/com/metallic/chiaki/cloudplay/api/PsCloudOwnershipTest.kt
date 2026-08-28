package com.metallic.chiaki.cloudplay.api

import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.cloudplay.model.StreamableStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsCloudOwnershipTest {

    private fun entitlement(
        id: String = "PPSA00001_00",
        productId: String = "PPSA00001_00",
        activeFlag: Boolean = true,
        packageType: String = "PSGD",
        name: String = "Test Game",
        conceptId: String = "",
        featureType: Int = 3,
        skuType: String = "",
        iconUrl: String = ""
    ) = PsCloudOwnership.Entitlement(
        id = id,
        productId = productId,
        activeFlag = activeFlag,
        packageType = packageType,
        name = name,
        conceptId = conceptId,
        featureType = featureType,
        skuType = skuType,
        iconUrl = iconUrl
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
    fun residentEvil7GoldEditionMatchesPs5EntitlementIdOverride() {
        // Regression for a real report: RE7 Gold Edition's PS4GD and PSGD entitlements both
        // carry product_id=EP0102-PPSA01557_00-RE7VILLAGECOMPGE, which has no imagic catalog
        // entry at all (neither direct productId nor stable-key PPSA01557 match). The catalog
        // override is keyed by the PS5-native entitlement's own id (PPSA04405), so only the
        // PSGD entitlement resolves — same shape as the Nioh 2 PS4-purchase-entitles-PS5-upgrade case.
        val catalogOverride = CloudGame(
            productId = "EP0102-PPSA04405_00-BH7G000000000001",
            name = "RESIDENT EVIL 7 biohazard Gold Edition",
            imageUrl = "",
            serviceType = "pscloud"
        )
        val ps4Ent = entitlement(
            id = "EP0102-CUSA09473_00-BH7G000000000001",
            productId = "EP0102-PPSA01557_00-RE7VILLAGECOMPGE",
            packageType = "PS4GD",
            name = "RESIDENT EVIL 7 biohazard Gold Edition",
            featureType = 3
        )
        val ps5Ent = entitlement(
            id = "EP0102-PPSA04405_00-BH7G000000000001",
            productId = "EP0102-PPSA01557_00-RE7VILLAGECOMPGE",
            packageType = "PSGD",
            name = "RESIDENT EVIL 7 biohazard Gold Edition",
            featureType = 3
        )
        val result = PsCloudOwnership.crossReferenceOwnedGames(
            filteredEntitlements = listOf(ps4Ent, ps5Ent),
            publicCatalog = listOf(catalogOverride)
        )
        assertEquals(1, result.size)
        assertEquals("EP0102-PPSA04405_00-BH7G000000000001", result[0].productId)
        assertEquals("EP0102-PPSA04405_00-BH7G000000000001", PsCloudOwnership.streamingIdentifier(result[0]))
    }

    @Test
    fun residentEvil7GoldEditionStreamsWithOwnRegionalIdForNonEuUser() {
        // Non-EU regression: unlike Witcher 3/Nioh 2, RE7 Gold Edition matches via the PS5
        // entitlement's own id (not product_id, which is a cross-platform bundle SKU with an
        // unrelated PPSA number). A US owner's storeProductId (UP0102-PPSA01557...) shares no
        // PPSA number with the hardcoded EU catalog productId, so the existing storeProductId
        // regional-prefix check can't fire — entitlementId must be used instead.
        val catalogOverride = CloudGame(
            productId = "EP0102-PPSA04405_00-BH7G000000000001",
            name = "RESIDENT EVIL 7 biohazard Gold Edition",
            imageUrl = "",
            serviceType = "pscloud"
        )
        val ps5EntUs = entitlement(
            id = "UP0102-PPSA04405_00-BH7G000000000001",
            productId = "UP0102-PPSA01557_00-RE7VILLAGECOMPGE",
            packageType = "PSGD",
            name = "RESIDENT EVIL 7 biohazard Gold Edition",
            featureType = 3
        )
        val result = PsCloudOwnership.crossReferenceOwnedGames(
            filteredEntitlements = listOf(ps5EntUs),
            publicCatalog = listOf(catalogOverride)
        )
        assertEquals(1, result.size)
        assertEquals("UP0102-PPSA04405_00-BH7G000000000001", PsCloudOwnership.streamingIdentifier(result[0]))
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
    fun streamingIdentifierUsesRegionalStoreProductIdForNonEuUser() {
        // US user owns UP-prefix variant of a game whose catalog entry is hardcoded with EP prefix.
        // The two productIds are identical except the first two chars (regional publisher code).
        // We must send the user's own UP productId to Gaikai so their entitlement validates.
        val game = pscloudGame(
            productId = "EP9000-PPSA02630_00-DALLSTARSPLUS001",
            storeProductId = "UP9000-PPSA02630_00-DALLSTARSPLUS001"
        )
        assertEquals("UP9000-PPSA02630_00-DALLSTARSPLUS001", PsCloudOwnership.streamingIdentifier(game))
    }

    @Test
    fun streamingIdentifierKeepsCatalogProductIdWhenSuffixAlsoDiffers() {
        // storeProductId shares the same PPSA but has a different suffix — not a pure regional
        // prefix swap, so the catalog productId wins (avoids changing behaviour for normal games).
        val game = pscloudGame(
            productId = "EP0082-PPSA08668_00-CATALOGID00000",
            storeProductId = "EP0082-PPSA08668_00-0978938405039882"
        )
        assertEquals("EP0082-PPSA08668_00-CATALOGID00000", PsCloudOwnership.streamingIdentifier(game))
    }

    @Test
    fun streamingIdentifierUsesEntitlementIdForCusaToPs5Upgrade() {
        // Non-EU user: PS4 purchase (CUSA storeProductId) entitles a PS5 upgrade (PPSA entitlementId).
        // Catalog productId is a hardcoded EU PPSA; user's entitlementId is their regional PPSA.
        // Gaikai validates against what the user actually owns, so entitlementId must win.
        val game = CloudGame(
            productId = "EP9000-PPSA02488_00-NIOH2EU000000000",
            name = "Nioh 2 Remastered",
            imageUrl = "",
            serviceType = "pscloud",
            storeProductId = "UP9000-CUSA15526_00-NIOH2US000000000",
            entitlementId = "UP9000-PPSA02488_00-NIOH2US000000001",
            featureType = 3
        )
        assertEquals("UP9000-PPSA02488_00-NIOH2US000000001", PsCloudOwnership.streamingIdentifier(game))
    }

    @Test
    fun remasteredUpgradeWithCusaStoreProductIdRoutesPscloud() {
        // Nioh 2 Remastered: the PS4 purchase (CUSA15526) entitles a PS5 Remastered upgrade
        // whose entitlement id is PPSA02488. storeProductId is CUSA so streamPlatform must not
        // fall through to ps4/psnow — the PPSA productId + PPSA entitlementId wins.
        val game = CloudGame(
            productId = "EP9000-PPSA02488_00-NIOH2EU000000000",
            name = "Nioh 2 Remastered",
            imageUrl = "",
            serviceType = "pscloud",
            storeProductId = "EP9000-CUSA15526_00-NIOH2EU100000000",
            entitlementId = "EP9000-PPSA02488_00-NIOH2EU000000000",
            featureType = 3
        )
        assertEquals("ps5", PsCloudOwnership.streamPlatform(game))
        assertEquals("pscloud", PsCloudOwnership.streamServiceType(game))
        assertEquals("EP9000-PPSA02488_00-NIOH2EU000000000", PsCloudOwnership.streamingIdentifier(game))
    }

    @Test
    fun ps4OnlyPurchaseStillRoutesPsnow() {
        // GoT PS4-only purchase matched via conceptId to a PS5 catalog entry. The entitlementId
        // is CUSA (PS4 purchase id) so streamPlatform must not treat this as ps5 — psnow is correct.
        val game = CloudGame(
            productId = "EP9000-PPSA03208_00-GHOSTDIRECTORPS5",
            name = "Ghost of Tsushima",
            imageUrl = "",
            serviceType = "pscloud",
            storeProductId = "EP9000-CUSA15439_00-GHOSTOFTSUSHIMAA",
            entitlementId = "EP9000-CUSA15439_00-GHOSTOFTSUSHIMAA",
            featureType = 3
        )
        assertEquals("ps4", PsCloudOwnership.streamPlatform(game))
        assertEquals("psnow", PsCloudOwnership.streamServiceType(game))
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

    // --- buildOwnedGamesFromEntitlements: builds the Library directly from entitlements,
    // no catalog cross-reference — regression coverage for the Witcher 3 / Nioh 2 / RE7 Gold
    // class of bug where a hardcoded productId override was needed because the public catalog
    // listed a different SKU than the one the user actually owns. Shapes below are taken from a
    // real account's entitlement dump.

    @Test
    fun `two entitlements sharing a product id collapse into one owned game`() {
        // Witcher 3: PS4 disc entitlement and PS5-native entitlement both carry the same
        // product_id — they represent the same purchase and must not show as two rows.
        val ents = listOf(
            entitlement(
                id = "EP4497-CUSA05571_00-00000000000GOTY1",
                productId = "EP4497-PPSA03977_00-00000000000GOTY8",
                packageType = "PS4GD",
                name = "The Witcher 3: Wild Hunt – Game of the Year Edition",
                iconUrl = "https://img/gotY.png"
            ),
            entitlement(
                id = "EP4497-PPSA03977_00-00000000000GOTY8",
                productId = "EP4497-PPSA03977_00-00000000000GOTY8",
                packageType = "PSGD",
                name = "The Witcher 3: Wild Hunt - Complete Edition",
                iconUrl = "https://img/complete.png"
            )
        )

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)

        assertEquals(1, games.size)
        val game = games.first()
        assertEquals("EP4497-PPSA03977_00-00000000000GOTY8", game.productId)
        assertEquals("ps5", game.platform)
        assertEquals("pscloud", game.serviceType)
        assertTrue(game.isOwned)
        // The self-matching (id == productId), PS5-native, full-game entitlement outranks the
        // cross-platform disc entitlement, so its name/art represents the game.
        assertEquals("The Witcher 3: Wild Hunt - Complete Edition", game.name)
        assertEquals("https://img/complete.png", game.imageUrl)
    }

    @Test
    fun `entitlement whose id is PS5-native but product id is a legacy PS4 SKU streams via the PS5 id`() {
        // Nioh 2-style upgrade: the PS4 purchase's product_id (CUSA) doesn't reflect the PS5
        // entitlement Gaikai actually validates against — that's carried in the entitlement's id.
        val ents = listOf(
            entitlement(
                id = "EP9000-PPSA02488_00-NIOH2EU000000000",
                productId = "EP9000-CUSA15526_00-NIOH2CROSSBUY00",
                packageType = "PS4GD",
                name = "Nioh 2 Remastered – The Complete Edition",
                iconUrl = "https://img/nioh2.png"
            )
        )

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)

        assertEquals(1, games.size)
        val game = games.first()
        assertEquals("EP9000-PPSA02488_00-NIOH2EU000000000", game.productId)
        assertEquals("ps5", game.platform)
        assertEquals("pscloud", game.serviceType)
        assertEquals("https://img/nioh2.png", game.imageUrl)
    }

    @Test
    fun `PS5-native entitlement whose product id is a bundle SKU streams via its own id`() {
        // RE2 Remake-style: both id and product_id are PPSA, but product_id is a cross-edition
        // bundle SKU the catalog doesn't recognize, while id matches the catalog's productId
        // exactly. Same shape as RE7 Gold Edition. id must win, not product_id.
        val ents = listOf(
            entitlement(
                id = "EP0102-PPSA04289_00-BH2R000000000001",
                productId = "EP0102-PPSA03953_00-BHB0000000000002",
                packageType = "PSGD",
                name = "RESIDENT EVIL 2"
            )
        )

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)

        assertEquals(1, games.size)
        assertEquals("EP0102-PPSA04289_00-BH2R000000000001", games.first().productId)
    }

    @Test
    fun `distinct games bundled under the same product id are not merged into one`() {
        // Real-world case: Sony sold RE2 Remake, RE3 Remake, and RE Resistance as a promo bundle
        // and assigned all three the same product_id — grouping by product_id would wrongly
        // collapse three separate games down to one. Each entitlement's own (PS5-native) id is
        // what actually distinguishes them and must drive grouping instead.
        val ents = listOf(
            entitlement(
                id = "EP0102-CUSA09171_00-BH2R000000000001",
                productId = "EP0102-PPSA03953_00-BHB0000000000002",
                packageType = "PS4GD", name = "RESIDENT EVIL 2"
            ),
            entitlement(
                id = "EP0102-PPSA04289_00-BH2R000000000001",
                productId = "EP0102-PPSA03953_00-BHB0000000000002",
                packageType = "PSGD", name = "RESIDENT EVIL 2"
            ),
            entitlement(
                id = "EP0102-CUSA14123_00-BH3ROFF000000001",
                productId = "EP0102-PPSA03953_00-BHB0000000000002",
                packageType = "PS4GD", name = "RESIDENT EVIL 3"
            ),
            entitlement(
                id = "EP0102-PPSA03953_00-BH3ROFF000000001",
                productId = "EP0102-PPSA03953_00-BHB0000000000002",
                packageType = "PSGD", name = "RESIDENT EVIL 3"
            ),
            entitlement(
                id = "EP0102-CUSA14122_00-BH3RON0000000001",
                productId = "EP0102-PPSA03953_00-BHB0000000000002",
                packageType = "PS4GD", name = "RESIDENT EVIL RESISTANCE"
            )
        )

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)

        // Resistance has no PS5-native entitlement here, so only RE2 and RE3 survive the PS5 filter.
        assertEquals(2, games.size)
        assertTrue(games.any { it.name == "RESIDENT EVIL 2" && it.productId == "EP0102-PPSA04289_00-BH2R000000000001" })
        assertTrue(games.any { it.name == "RESIDENT EVIL 3" && it.productId == "EP0102-PPSA03953_00-BH3ROFF000000001" })
    }

    @Test
    fun `PS4-only entitlement is excluded from the PS Cloud library`() {
        // This is the PS Cloud tab, not PSNow — PS3/PS4-only titles don't belong here.
        val ents = listOf(
            entitlement(
                id = "CUSA12345_00",
                productId = "CUSA12345_00",
                packageType = "PS4GD",
                name = "Some PS4 Game"
            )
        )

        assertTrue(PsCloudOwnership.buildOwnedGamesFromEntitlements(ents).isEmpty())
    }

    @Test
    fun `legacy media app entitlement with no PS5-native id is excluded`() {
        // BT Sport-style entry: same shape as a real PS4 game entitlement (package_type=PS4GD,
        // featureType=1), but never received a PS5-native upgrade id the way real games did.
        val ents = listOf(
            entitlement(
                id = "EP8846-CUSA14890_00-2819123350041251",
                productId = "EP8846-CUSA14890_00-2819123350041251",
                packageType = "PS4GD",
                name = "BT Sport",
                featureType = 1
            )
        )

        assertTrue(PsCloudOwnership.buildOwnedGamesFromEntitlements(ents).isEmpty())
    }

    @Test
    fun `PS5-native media app is excluded via PSMEDIA package type`() {
        // Netflix-style entry: has a real PS5-native (PPSA) id, so the platform filter alone
        // wouldn't catch it — Sony classifies these under package_type "PSMEDIA", not PSGD.
        val ents = listOf(
            entitlement(
                id = "EP4350-PPSA01615_00-NETFLIXGEMINI001",
                productId = "EP4350-PPSA01615_00-NETFLIXGEMINI001",
                packageType = "PSMEDIA",
                name = "Netflix"
            )
        )

        assertTrue(PsCloudOwnership.buildOwnedGamesFromEntitlements(ents).isEmpty())
    }

    @Test
    fun `PSTRACK placeholder entitlement does not create a duplicate row for the real game`() {
        // Mortal Kombat 11-style: Sony issues a PSTRACK analytics placeholder alongside the real
        // purchase, sharing its product_id and carrying featureType=3 like a real game — it must
        // not show up as a second row for the same title.
        val ents = listOf(
            entitlement(
                id = "EP1018-PPSA01619_00-00MORTALKOMBAT11",
                productId = "EP1018-PPSA01619_00-00MORTALKOMBAT11",
                packageType = "PSGD",
                name = "Mortal Kombat 11"
            ),
            entitlement(
                id = "EP1018-PPSA01619_00-PSTRACK000000000",
                productId = "EP1018-PPSA01619_00-00MORTALKOMBAT11",
                packageType = "PSTRACK",
                name = "Mortal Kombat 11"
            )
        )

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)

        assertEquals(1, games.size)
        assertEquals("EP1018-PPSA01619_00-00MORTALKOMBAT11", games.first().productId)
    }

    @Test
    fun `digital extras bundled with a real game are excluded but the game itself is kept`() {
        // Horizon Zero Dawn Remastered-style: the artbook shares the game's product_id and has
        // the exact same PSGD/featureType=3 shape as a real game entitlement.
        val ents = listOf(
            entitlement(
                id = "EP9000-PPSA13427_00-HORIZONREMASTER1",
                productId = "EP9000-PPSA13427_00-HORIZONREMASTER1",
                packageType = "PSGD",
                name = "Horizon Zero Dawn™ Remastered"
            ),
            entitlement(
                id = "EP9000-PPSA24690_00-0000000000000000",
                productId = "EP9000-PPSA13427_00-HORIZONREMASTER1",
                packageType = "PSGD",
                name = "Horizon Zero Dawn™ Artbook"
            )
        )

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)

        assertEquals(1, games.size)
        assertEquals("Horizon Zero Dawn™ Remastered", games.first().name)
    }

    @Test
    fun `standalone digital extra with no attached real game is excluded entirely`() {
        // Square Enix's "Digital Content Viewer" — a standalone bonus-content app, not bundled
        // with any single game's product_id.
        val ents = listOf(
            entitlement(
                id = "EP0082-PPSA13290_00-DVIEWEREU0000001",
                productId = "EP0082-PPSA13290_00-DVIEWEREU0000001",
                packageType = "PSGD",
                name = "DIGITAL CONTENT VIEWER"
            )
        )

        assertTrue(PsCloudOwnership.buildOwnedGamesFromEntitlements(ents).isEmpty())
    }

    @Test
    fun `soundtrack digital extra is excluded`() {
        val ents = listOf(
            entitlement(
                id = "EP0177-PPSA18182_00-APPLICATION00000",
                productId = "EP0177-PPSA10873_00-ADDCONTENT200011",
                packageType = "PSGD",
                name = "PERSONA 3 RELOAD DIGITAL ORIGINAL SOUNDTRACK"
            )
        )

        assertTrue(PsCloudOwnership.buildOwnedGamesFromEntitlements(ents).isEmpty())
    }

    @Test
    fun `'The Art of X' artbook naming style is excluded`() {
        val ents = listOf(
            entitlement(
                id = "UP1003-PPSA28897_00-HELIUMSOUNDTRACK",
                productId = "UP1003-PPSA28897_00-HELIUMSOUNDTRACK",
                packageType = "PSGD",
                name = "The Art of Starfield"
            )
        )

        assertTrue(PsCloudOwnership.buildOwnedGamesFromEntitlements(ents).isEmpty())
    }

    @Test
    fun `'BONUS CONTENT' naming style is excluded`() {
        // A generic "... BONUS CONTENT" entitlement (cosmetic extras/digital manual), same
        // PSGD/featureType=3 shape as a real game.
        val ents = listOf(
            entitlement(
                id = "EP0101-PPSA16257_00-GENERICBONUSCONT",
                productId = "EP0101-PPSA16257_00-GENERICBONUSCONT",
                packageType = "PSGD",
                name = "SOME GAME: DELUXE EDITION BONUS CONTENT"
            )
        )

        assertTrue(PsCloudOwnership.buildOwnedGamesFromEntitlements(ents).isEmpty())
    }

    @Test
    fun `Metal Gear Solid Master Collection 'BONUS CONTENT' is not excluded`() {
        // Unlike a typical "BONUS CONTENT" entitlement, the MGS Master Collection Vol.1/Vol.2
        // bonus content actually bundles playable classic titles, so it must still show up.
        val ents = listOf(
            entitlement(
                id = "EP0101-PPSA16257_00-MGSBONUSCONTENTS",
                productId = "EP0101-PPSA16257_00-MGSBONUSCONTENTS",
                packageType = "PSGD",
                name = "METAL GEAR SOLID: MASTER COLLECTION Vol.1 BONUS CONTENT"
            ),
            entitlement(
                id = "EP0101-PPSA16258_00-MGSBONUSCONTENTS2",
                productId = "EP0101-PPSA16258_00-MGSBONUSCONTENTS2",
                packageType = "PSGD",
                name = "METAL GEAR SOLID: MASTER COLLECTION Vol.2 BONUS CONTENT"
            )
        )

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)

        assertEquals(2, games.size)
        assertTrue(games.any { it.name == "METAL GEAR SOLID: MASTER COLLECTION Vol.1 BONUS CONTENT" })
        assertTrue(games.any { it.name == "METAL GEAR SOLID: MASTER COLLECTION Vol.2 BONUS CONTENT" })
    }

    @Test
    fun `real PS5 game with PSGD package type is not affected by the media-app filter`() {
        val ents = listOf(
            entitlement(
                id = "EP0006-PPSA07784_00-RESPAWNAPPLEJACK",
                productId = "EP0006-PPSA07784_00-APPLEJACKGAME000",
                packageType = "PSGD",
                name = "STAR WARS Jedi: Survivor"
            )
        )

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)
        assertEquals(1, games.size)
        assertEquals("STAR WARS Jedi: Survivor", games.first().name)
    }

    @Test
    fun `independent games with different product ids each get their own row`() {
        val ents = listOf(
            entitlement(id = "PPSA00001_00", productId = "PPSA00001_00", name = "Game A"),
            entitlement(id = "PPSA00002_00", productId = "PPSA00002_00", name = "Game B")
        )

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)

        assertEquals(2, games.size)
        assertTrue(games.any { it.name == "Game A" })
        assertTrue(games.any { it.name == "Game B" })
    }

    @Test
    fun `PS Plus subscription-access entitlement is flagged as plusCatalog`() {
        val ents = listOf(entitlement(featureType = 1, name = "Subscription Game"))

        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(ents)

        assertEquals(1, games.size)
        assertTrue(games.first().plusCatalog)
    }

    // --- enrichWithCatalogArt: best-effort box art upgrade, never required for a game to show up

    @Test
    fun `exact product id match upgrades art to the catalog cover image`() {
        val owned = CloudGame(
            productId = "EP0006-PPSA07784_00-APPLEJACKGAME000", name = "STAR WARS Jedi: Survivor",
            imageUrl = "https://icon/square.png", landscapeImageUrl = "https://icon/square.png",
            thumbnailUrl = "https://icon/square.png", platform = "ps5", serviceType = "pscloud", isOwned = true
        )
        val catalogEntry = CloudGame(
            productId = "EP0006-PPSA07784_00-APPLEJACKGAME000", name = "STAR WARS Jedi: Survivor",
            imageUrl = "https://cover/art.jpg", landscapeImageUrl = "https://cover/landscape.jpg",
            thumbnailUrl = "https://cover/thumb.jpg", platform = "ps5", serviceType = "pscloud"
        )

        val result = PsCloudOwnership.enrichWithCatalogArt(listOf(owned), listOf(catalogEntry))

        assertEquals("https://cover/art.jpg", result.first().imageUrl)
        assertEquals("https://cover/landscape.jpg", result.first().landscapeImageUrl)
        assertEquals("https://cover/thumb.jpg", result.first().thumbnailUrl)
    }

    @Test
    fun `stable key match upgrades art when the full product id differs by region prefix`() {
        val owned = CloudGame(
            productId = "UP0006-PPSA07784_00-APPLEJACKGAME000", name = "STAR WARS Jedi: Survivor",
            imageUrl = "https://icon/square.png", platform = "ps5", serviceType = "pscloud", isOwned = true
        )
        val catalogEntry = CloudGame(
            productId = "EP0006-PPSA07784_00-APPLEJACKGAME000", name = "STAR WARS Jedi: Survivor",
            imageUrl = "https://cover/art.jpg", platform = "ps5", serviceType = "pscloud"
        )

        val result = PsCloudOwnership.enrichWithCatalogArt(listOf(owned), listOf(catalogEntry))

        assertEquals("https://cover/art.jpg", result.first().imageUrl)
    }

    @Test
    fun `no catalog match keeps the entitlement icon unchanged`() {
        // Witcher 3-style mismatch: owned product id has no counterpart in the catalog at all.
        val owned = CloudGame(
            productId = "EP4497-PPSA03977_00-00000000000GOTY8", name = "The Witcher 3: Wild Hunt",
            imageUrl = "https://icon/square.png", platform = "ps5", serviceType = "pscloud", isOwned = true
        )
        val catalogEntry = CloudGame(
            productId = "EP4497-PPSA10408_00-00000000000000N1", name = "The Witcher 3: Wild Hunt",
            imageUrl = "https://cover/art.jpg", platform = "ps5", serviceType = "pscloud"
        )

        val result = PsCloudOwnership.enrichWithCatalogArt(listOf(owned), listOf(catalogEntry))

        assertEquals("https://icon/square.png", result.first().imageUrl)
    }

    @Test
    fun `bundle sibling without its own catalog listing keeps its own icon, not the bundle's art`() {
        // GTA Trilogy-style: San Andreas and Vice City are both entitled under the same bundle
        // product_id (storeProductId), but only the bundle itself has a catalog listing — San
        // Andreas has none of its own. Falling back to storeProductId must not hand it the
        // bundle's box art, since that would also collide with every other bundle sibling.
        val sanAndreas = CloudGame(
            productId = "EP1004-PPSA03525_00-GTASANANDREAS001", name = "Grand Theft Auto: San Andreas – The Definitive Edition",
            imageUrl = "https://icon/sanandreas.png", platform = "ps5", serviceType = "pscloud", isOwned = true,
            storeProductId = "EP1004-PPSA05805_00-GTATRILOGYBUNDLE"
        )
        val viceCity = CloudGame(
            productId = "EP1004-PPSA03531_00-GTAVICECITY00001", name = "Grand Theft Auto: Vice City – The Definitive Edition",
            imageUrl = "https://icon/vicecity.png", platform = "ps5", serviceType = "pscloud", isOwned = true,
            storeProductId = "EP1004-PPSA05805_00-GTATRILOGYBUNDLE"
        )
        val bundleCatalogEntry = CloudGame(
            productId = "EP1004-PPSA05805_00-GTATRILOGYBUNDLE", name = "Grand Theft Auto: The Trilogy – The Definitive Edition",
            imageUrl = "https://cover/trilogy-bundle.jpg", platform = "ps5", serviceType = "pscloud"
        )

        val result = PsCloudOwnership.enrichWithCatalogArt(listOf(sanAndreas, viceCity), listOf(bundleCatalogEntry))

        assertEquals("https://icon/sanandreas.png", result[0].imageUrl)
        assertEquals("https://icon/vicecity.png", result[1].imageUrl)
    }

    @Test
    fun `empty catalog returns owned games unchanged`() {
        val owned = CloudGame(
            productId = "PPSA00001_00", name = "Some Game", imageUrl = "https://icon/square.png",
            platform = "ps5", serviceType = "pscloud", isOwned = true
        )

        val result = PsCloudOwnership.enrichWithCatalogArt(listOf(owned), emptyList())

        assertEquals(listOf(owned), result)
    }

    @Test
    fun `art match found only in the PS Plus supplement list is still used`() {
        // Classic Resident Evil 2-style: only ever appears in the supplement list, never the
        // main browse catalog.
        val owned = CloudGame(
            productId = "UP0102-PPSA07813_00-CLASSICRE2000001", name = "Resident Evil 2",
            imageUrl = "https://icon/square.png", platform = "ps5", serviceType = "pscloud", isOwned = true
        )
        val supplementEntry = CloudGame(
            productId = "UP0102-PPSA07813_00-CLASSICRE2000001", name = "Resident Evil 2",
            imageUrl = "https://cover/classic-re2.jpg", platform = "ps5", serviceType = "pscloud",
            plusCatalog = true
        )

        val result = PsCloudOwnership.enrichWithCatalogArt(
            ownedGames = listOf(owned), catalog = emptyList(), supplement = listOf(supplementEntry)
        )

        assertEquals("https://cover/classic-re2.jpg", result.first().imageUrl)
    }

    // --- applyStreamabilityHints: Library tile badge derivation

    @Test
    fun `catalog match sets STREAMABLE`() {
        val owned = CloudGame(
            productId = "PPSA00001_00", name = "Some Game", imageUrl = "", platform = "ps5", serviceType = "pscloud"
        )
        val catalogEntry = CloudGame(
            productId = "PPSA00001_00", name = "Some Game", imageUrl = "", platform = "ps5", serviceType = "pscloud"
        )

        val result = PsCloudOwnership.applyStreamabilityHints(listOf(owned), listOf(catalogEntry))

        assertEquals(StreamableStatus.STREAMABLE, result.first().streamableStatus)
    }

    @Test
    fun `no catalog match and no confirmed override is UNKNOWN, not an assumed cross`() {
        val owned = CloudGame(
            productId = "PPSA00001_00", name = "Some Game", imageUrl = "", platform = "ps5", serviceType = "pscloud"
        )

        val result = PsCloudOwnership.applyStreamabilityHints(listOf(owned), catalog = emptyList())

        assertEquals(StreamableStatus.UNKNOWN, result.first().streamableStatus)
    }

    @Test
    fun `confirmed override wins over a catalog match`() {
        val owned = CloudGame(
            productId = "PPSA00001_00", name = "Some Game", imageUrl = "", platform = "ps5", serviceType = "pscloud"
        )
        val catalogEntry = owned.copy()

        val result = PsCloudOwnership.applyStreamabilityHints(
            listOf(owned), listOf(catalogEntry), confirmedOverrides = mapOf("PPSA00001_00" to false)
        )

        // A real failed launch attempt overrides even a confident catalog "streamable" match.
        assertEquals(StreamableStatus.NOT_STREAMABLE, result.first().streamableStatus)
    }

    @Test
    fun `confirmed streamable override applies even with no catalog match at all`() {
        val owned = CloudGame(
            productId = "PPSA00001_00", name = "Some Game", imageUrl = "", platform = "ps5", serviceType = "pscloud"
        )

        val result = PsCloudOwnership.applyStreamabilityHints(
            listOf(owned), catalog = emptyList(), confirmedOverrides = mapOf("PPSA00001_00" to true)
        )

        assertEquals(StreamableStatus.STREAMABLE, result.first().streamableStatus)
    }

    @Test
    fun `unrelated confirmed overrides do not affect other games`() {
        val gameA = CloudGame(productId = "PPSA00001_00", name = "Game A", imageUrl = "", platform = "ps5", serviceType = "pscloud")
        val gameB = CloudGame(productId = "PPSA00002_00", name = "Game B", imageUrl = "", platform = "ps5", serviceType = "pscloud")

        val result = PsCloudOwnership.applyStreamabilityHints(
            listOf(gameA, gameB), catalog = emptyList(), confirmedOverrides = mapOf("PPSA00001_00" to false)
        )

        assertEquals(StreamableStatus.NOT_STREAMABLE, result.first { it.productId == "PPSA00001_00" }.streamableStatus)
        assertEquals(StreamableStatus.UNKNOWN, result.first { it.productId == "PPSA00002_00" }.streamableStatus)
    }
}
