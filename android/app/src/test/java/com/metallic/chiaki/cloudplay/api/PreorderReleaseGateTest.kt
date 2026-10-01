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
    fun `missing or garbage dates mean no restriction`() {
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
        val game = PsCloudOwnership.buildOwnedGamesFromEntitlements(
            listOf(PsCloudOwnership.parseEntitlement(JSONObject(preorderJson))!!)
        ).single()
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
        val game = PsCloudOwnership.buildOwnedGamesFromEntitlements(listOf(ent)).single()
        assertTrue(PsCloudOwnership.isReleased(game, releaseMs))
    }

    @Test
    fun `ordinary purchase without a date is not locked`() {
        val ent = PsCloudOwnership.parseEntitlement(entitlementJson(preorder = false, activeDate = null))!!
        assertEquals(0L, ent.activeDateMs)
    }

    @Test
    fun `an owned copy still wins over a dateless pre-order of the same game`() {
        val games = PsCloudOwnership.buildOwnedGamesFromEntitlements(listOf(
            entitlement("UP0001-PPSA00001_00-GAME000000000000", PsCloudOwnership.RELEASE_DATE_UNKNOWN),
            entitlement("UP0001-PPSA00001_00-GAME000000000000", 1_000L)
        ))
        assertEquals(1_000L, games.single().availableFromMs)
    }
}
