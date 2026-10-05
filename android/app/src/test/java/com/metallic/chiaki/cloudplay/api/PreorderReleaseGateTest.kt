package com.metallic.chiaki.cloudplay.api

import com.metallic.chiaki.cloudplay.model.CloudGame
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreorderReleaseGateTest {

    // 2026-10-05T23:00:00Z — the real active_date Sony sent for a pre-ordered Kingdom Hearts title.
    private val releaseMs = 1791241200000L

    // Trimmed copy of the real pre-order entitlement JSON.
    private val preorderJson = """
        {"active_date":"2026-10-05T23:00:00Z","active_flag":true,"entitlement_type":5,"feature_type":3,
         "id":"UP0082-PPSA35901_00-KHHD1525RMX00000","preorder_flag":true,
         "product_id":"UP0082-PPSA35901_00-KHCOLLECTION00EN",
         "game_meta":{"name":"KINGDOM HEARTS -HD 1.5+2.5 ReMIX-","package_type":"PSGD"}}
    """.trimIndent()

    private fun entitlement(id: String, activeDateMs: Long) = PsCloudOwnership.Entitlement(
        id = id, productId = id, activeFlag = true, packageType = "PSGD", name = "Game",
        conceptId = "", featureType = 3, activeDateMs = activeDateMs
    )

    @Test
    fun `parses Sony timestamps with and without millis`() {
        assertEquals(releaseMs, PsCloudOwnership.parseSonyDate("2026-10-05T23:00:00Z"))
        assertEquals(releaseMs + 123, PsCloudOwnership.parseSonyDate("2026-10-05T23:00:00.123Z"))
    }

    @Test
    fun `missing or garbage dates parse as zero`() {
        assertEquals(0L, PsCloudOwnership.parseSonyDate(""))
        assertEquals(0L, PsCloudOwnership.parseSonyDate("not a date"))
    }

    @Test
    fun `pre-order entitlement carries its release time`() {
        val ent = PsCloudOwnership.parseEntitlement(JSONObject(preorderJson))!!
        assertEquals(releaseMs, ent.activeDateMs)
    }

    @Test
    fun `pre-ordered game is blocked before release and allowed from release`() {
        // Fetched from Sony a minute after release.
        val game = PsCloudOwnership.buildOwnedGamesFromEntitlements(
            listOf(PsCloudOwnership.parseEntitlement(JSONObject(preorderJson))!!)
        ).single().copy(releaseCheckedAtMs = releaseMs + 60_000)
        assertFalse(PsCloudOwnership.isReleased(game, releaseMs - 1))
        assertTrue(PsCloudOwnership.isReleased(game, releaseMs))
        assertTrue(PsCloudOwnership.isReleased(game, releaseMs + 60_000))
    }

    @Test
    fun `ordinary purchase is never blocked`() {
        assertTrue(PsCloudOwnership.isReleased(CloudGame(productId = "x", name = "x", imageUrl = ""), 0L))
    }

    @Test
    fun `an already active entitlement for the same game wins over a pre-order`() {
        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(listOf(
            entitlement("UP0001-PPSA00001_00-GAME000000000000", releaseMs),
            entitlement("UP0001-PPSA00001_00-GAME000000000000", 1_000L)
        ))
        assertEquals(1_000L, games.single().availableFromMs)
    }

    @Test
    fun `release time survives merging into the browse catalog`() {
        val catalogEntry = CloudGame(productId = "UP0082-PPSA35901_00-KHHD1525RMX00000", name = "KH", imageUrl = "")
        val owned = catalogEntry.copy(isOwned = true, availableFromMs = releaseMs)
        val merged = PsCloudOwnership.mergeOwnedIntoBrowseCatalog(listOf(catalogEntry), listOf(owned))
        assertEquals(releaseMs, merged.single().availableFromMs)
    }

    private fun entitlementJson(preorder: Boolean, activeDate: String?) = JSONObject().apply {
        put("id", "UP0001-PPSA00001_00-GAME000000000000")
        put("product_id", "UP0001-PPSA00001_00-GAME000000000000")
        put("active_flag", true)
        put("feature_type", 3)
        put("preorder_flag", preorder)
        if (activeDate != null) put("active_date", activeDate)
        put("game_meta", JSONObject().put("name", "Game").put("package_type", "PSGD"))
    }

    @Test
    fun `pre-order without a release date stays locked`() {
        val ent = PsCloudOwnership.parseEntitlement(entitlementJson(preorder = true, activeDate = null))!!
        assertEquals(PsCloudOwnership.RELEASE_DATE_UNKNOWN, ent.activeDateMs)
        val game = PsCloudOwnership.buildOwnedGamesFromEntitlements(listOf(ent)).single()
        assertFalse(PsCloudOwnership.isReleased(game, Long.MAX_VALUE - 1))
    }

    @Test
    fun `released pre-order keeps its flag but is playable`() {
        // Real shape from an account: FINAL FANTASY VII REBIRTH, preorder_flag still true years later.
        val ent = PsCloudOwnership.parseEntitlement(entitlementJson(preorder = true, activeDate = "2024-02-29T00:00:00Z"))!!
        val game = PsCloudOwnership.stampReleaseChecked(
            PsCloudOwnership.buildOwnedGamesFromEntitlements(listOf(ent)), releaseMs
        ).single()
        assertTrue(PsCloudOwnership.isReleased(game, releaseMs))
    }

    @Test
    fun `a game with no date stays locked even without the pre-order flag`() {
        // Sony dates every entitlement; one arriving without a date might be an unreleased
        // pre-order Sony forgot to flag, so it stays hidden until a date arrives.
        val ent = PsCloudOwnership.parseEntitlement(entitlementJson(preorder = false, activeDate = null))!!
        assertEquals(PsCloudOwnership.RELEASE_DATE_UNKNOWN, ent.activeDateMs)
        val game = PsCloudOwnership.buildOwnedGamesFromEntitlements(listOf(ent)).single()
        assertTrue(PsCloudOwnership.withoutUnreleased(listOf(game), Long.MAX_VALUE - 1).isEmpty())
    }

    @Test
    fun `a game with an unreadable date stays locked`() {
        val ent = PsCloudOwnership.parseEntitlement(entitlementJson(preorder = false, activeDate = "not a date"))!!
        assertEquals(PsCloudOwnership.RELEASE_DATE_UNKNOWN, ent.activeDateMs)
    }

    @Test
    fun `an ordinary purchase is dated by when it was added and plays normally`() {
        // Real shape: Ghost of Tsushima's active_date is when it was added to the account.
        val ent = PsCloudOwnership.parseEntitlement(entitlementJson(preorder = false, activeDate = "2025-01-11T22:23:17Z"))!!
        val game = PsCloudOwnership.stampReleaseChecked(PsCloudOwnership.buildOwnedGamesFromEntitlements(listOf(ent)), releaseMs).single()
        assertTrue(PsCloudOwnership.isReleased(game, releaseMs))
    }

    @Test
    fun `an owned copy still wins over a dateless pre-order of the same game`() {
        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(listOf(
            entitlement("UP0001-PPSA00001_00-GAME000000000000", PsCloudOwnership.RELEASE_DATE_UNKNOWN),
            entitlement("UP0001-PPSA00001_00-GAME000000000000", 1_000L)
        ))
        assertEquals(1_000L, games.single().availableFromMs)
    }

    private fun owned(id: String, availableFromMs: Long, checkedAtMs: Long = 0L) =
        CloudGame(productId = id, name = id, imageUrl = "", isOwned = true,
            availableFromMs = availableFromMs, releaseCheckedAtMs = checkedAtMs)

    @Test
    fun `unreleased pre-order tile is hidden until a refresh after its unlock time`() {
        // Refreshed from Sony at the unlock time.
        val games = listOf(owned("released", 0L), owned("preorder", releaseMs, checkedAtMs = releaseMs))

        assertEquals(listOf("released"), PsCloudOwnership.withoutUnreleased(games, releaseMs - 1).map { it.productId })
        assertEquals(listOf("released", "preorder"), PsCloudOwnership.withoutUnreleased(games, releaseMs).map { it.productId })
    }

    @Test
    fun `a copy saved before the unlock time never reveals the game`() {
        // Saved at 10:00 saying it unlocks at release; Sony may have moved the date since.
        val savedEarly = owned("preorder", releaseMs, checkedAtMs = releaseMs - 7 * 3_600_000L)

        assertFalse(PsCloudOwnership.isReleased(savedEarly, releaseMs + 3_600_000L))
        assertTrue(PsCloudOwnership.withoutUnreleased(listOf(savedEarly), releaseMs + 3_600_000L).isEmpty())
    }

    @Test
    fun `a refresh that brings a moved release date keeps the game hidden`() {
        val movedRelease = releaseMs + 3 * 24 * 3_600_000L
        // Refreshed an hour after the original time: Sony now says it unlocks three days later.
        val refreshed = owned("preorder", movedRelease, checkedAtMs = releaseMs + 3_600_000L)

        assertFalse(PsCloudOwnership.isReleased(refreshed, releaseMs + 3_600_000L))
        assertTrue(PsCloudOwnership.isReleased(refreshed.copy(releaseCheckedAtMs = movedRelease + 60_000), movedRelease + 60_000))
    }

    @Test
    fun `games released long ago show from any saved copy fetched after their date`() {
        val oldGame = owned("old", 1_000L, checkedAtMs = releaseMs - 7 * 24 * 3_600_000L)

        assertTrue(PsCloudOwnership.isReleased(oldGame, releaseMs))
    }

    @Test
    fun `stamping only touches owned games`() {
        val games = listOf(owned("mine", releaseMs), CloudGame(productId = "catalog", name = "c", imageUrl = ""))

        val stamped = PsCloudOwnership.stampReleaseChecked(games, 42L)

        assertEquals(listOf(42L, 0L), stamped.map { it.releaseCheckedAtMs })
    }

    @Test
    fun `dateless pre-order stays hidden`() {
        val games = listOf(owned("dateless", PsCloudOwnership.RELEASE_DATE_UNKNOWN))

        assertTrue(PsCloudOwnership.withoutUnreleased(games, Long.MAX_VALUE - 1).isEmpty())
    }

    @Test
    fun `games not owned are never hidden`() {
        // Browse-catalog entries the user doesn't own carry no release lock.
        val games = listOf(CloudGame(productId = "catalog", name = "catalog", imageUrl = "", availableFromMs = releaseMs))

        assertEquals(games, PsCloudOwnership.withoutUnreleased(games, 0L))
    }
}
