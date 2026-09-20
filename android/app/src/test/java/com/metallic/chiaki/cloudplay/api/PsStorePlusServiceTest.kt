package com.metallic.chiaki.cloudplay.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PsStorePlusServiceTest {

    private fun product(id: String, upsell: String = "NONE", upsellText: String? = null, service: String = "NONE") = """
        {"__typename":"Product","id":"$id","name":"x","price":{"upsellServiceBranding":["$upsell"],"serviceBranding":["$service"],"upsellText":${if (upsellText == null) "null" else "\"$upsellText\""}}}
    """.trimIndent()

    private fun page(total: Int, vararg products: String) = """
        {"data":{"categoryGridRetrieve":{"pageInfo":{"totalCount":$total},"products":[${products.joinToString(",")}]}}}
    """.trimIndent()

    private fun keysOf(vararg products: String) = PsStorePlusService.parsePage(page(products.size, *products)).plusKeys

    @Test
    fun `a Game Catalog (Extra) title is included and collected by its PPSA number`() {
        val parsed = PsStorePlusService.parsePage(page(3, product("UP3643-PPSA16785_00-AAA", upsell = "PS_PLUS", upsellText = "Extra")))

        assertEquals(setOf("PPSA16785"), parsed.plusKeys)
        assertEquals(3, parsed.totalCount)
    }

    @Test
    fun `a monthly Essential title is included`() {
        assertEquals(setOf("PPSA00010"), keysOf(product("EP1-PPSA00010_00-A", upsell = "PS_PLUS", upsellText = "Essential")))
    }

    @Test
    fun `a price tied to the subscription is included whatever the upsell text`() {
        assertEquals(setOf("PPSA00002"), keysOf(product("EP1-PPSA00002_00-B", upsell = "PS_PLUS", upsellText = "Premium", service = "PS_PLUS")))
        assertEquals(setOf("PPSA00011"), keysOf(product("EP1-PPSA00011_00-B", service = "PS_PLUS")))
    }

    @Test
    fun `a Premium game trial is not included (Mafia The Old Country)`() {
        assertTrue(keysOf(product("EP1001-PPSA14299_00-00000NEROPS5SIEE", upsell = "PS_PLUS", upsellText = "Premium")).isEmpty())
    }

    @Test
    fun `member discounts are not inclusion`() {
        assertTrue(keysOf(
            product("EP1-PPSA00020_00-A", upsell = "PS_PLUS", upsellText = "Save 10% more"),
            product("EP1-PPSA00021_00-B", upsell = "PS_PLUS", upsellText = "Save 20%")
        ).isEmpty())
    }

    @Test
    fun `other subscriptions and untagged games are ignored`() {
        assertTrue(keysOf(
            product("EP1-PPSA00003_00-C", upsell = "EA_ACCESS", upsellText = "Extra"),
            product("EP1-PPSA00004_00-D"),
            product("EP1-PPSA00005_00-E", upsell = "GTA_PLUS")
        ).isEmpty())
    }

    @Test
    fun `a product without a price or a PPSA number is skipped`() {
        val parsed = PsStorePlusService.parsePage(page(
            2, """{"id":"EP1-PPSA00006_00-F","name":"no price"}""", product("no-key-here", upsell = "PS_PLUS", upsellText = "Extra")
        ))

        assertTrue(parsed.plusKeys.isEmpty())
    }

    @Test
    fun `an API error is reported instead of read as no PS Plus titles`() {
        val error = """{"errors":[{"message":"Category ID x was not found"}],"data":{"categoryGridRetrieve":null}}"""
        try {
            PsStorePlusService.parsePage(error)
            org.junit.Assert.fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not found"))
        }
    }

    @Test
    fun `the request asks for full games in the PS5 category with the persisted query`() {
        val url = PsStorePlusService.pageUrl(offset = 400, size = 200)
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")

        assertTrue(decoded.contains("operationName=categoryGridRetrieve"))
        assertTrue(decoded.contains("\"offset\":400"))
        assertTrue(decoded.contains("\"size\":200"))
        assertTrue(decoded.contains("storeDisplayClassification:FULL_GAME"))
        assertTrue(decoded.contains("persistedQuery"))
    }

    @Test
    fun `the cache round-trips and rejects garbage`() {
        val entry = PsPlusCache.Entry("en-GB", 1_000L, setOf("PPSA1", "PPSA2"))

        assertEquals(entry, PsPlusCache.decode(PsPlusCache.encode(entry)))
        assertNull(PsPlusCache.decode("not json"))
        assertNull(PsPlusCache.decode("""{"storeLocale":"en-GB"}"""))
    }

    @Test
    fun `a lookup saved under an older rule is discarded`() {
        val oldFormat = """{"storeLocale":"en-GB","fetchedAtMs":1000,"keys":["PPSA1"]}"""
        val olderRule = """{"storeLocale":"en-GB","fetchedAtMs":1000,"ruleVersion":1,"keys":["PPSA1"]}"""

        assertNull(PsPlusCache.decode(oldFormat))
        assertNull(PsPlusCache.decode(olderRule))
    }

    @Test
    fun `the cache is fresh for a week and stale after`() {
        val entry = PsPlusCache.Entry("en-GB", 1_000_000L, emptySet())

        assertTrue(PsPlusCache.isFresh(entry, 1_000_000L + PsPlusCache.MAX_AGE_MS - 1))
        assertFalse(PsPlusCache.isFresh(entry, 1_000_000L + PsPlusCache.MAX_AGE_MS))
        assertFalse("a clock set before the fetch is not trusted", PsPlusCache.isFresh(entry, 999_999L))
    }
}
