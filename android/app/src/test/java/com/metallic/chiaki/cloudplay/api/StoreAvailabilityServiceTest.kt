package com.metallic.chiaki.cloudplay.api

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreAvailabilityServiceTest {

    private val twt2Ps5 = "EP4008-PPSA02019_00-TWT2SIEE00000000"

    private fun page(vararg ctaProducts: String) =
        ctaProducts.joinToString(" ") { """"GameCTA:ADD_TO_CART:ADD_TO_CART:$it-E002":{"id":"x"}""" }

    @Test
    fun `buttons for this game's own product mean available`() {
        assertEquals(StoreVerdict.AVAILABLE, StoreAvailabilityService.verdictFromPage(page(twt2Ps5), twt2Ps5))
    }

    @Test
    fun `buttons with a trailing purchase-type suffix still count`() {
        val html = """GameCTA:ADD_TO_CART:ADD_TO_CART:JP0013-PPSA35615_00-JP0AKUPARA000PS5-E001:OUTRIGHT"""

        assertEquals(StoreVerdict.AVAILABLE, StoreAvailabilityService.verdictFromPage(html, "JP0013-PPSA35615_00-JP0AKUPARA000PS5"))
    }

    @Test
    fun `only other editions being sold means unavailable (Tennis World Tour 2)`() {
        val html = page("EP4008-CUSA23445_00-TWT2SIEE00000000", "EP4008-CUSA23445_00-TWT2SIEEDELUXEED")

        assertEquals(StoreVerdict.UNAVAILABLE, StoreAvailabilityService.verdictFromPage(html, twt2Ps5))
    }

    @Test
    fun `a page with no buttons at all is unknown, never unavailable`() {
        assertEquals(StoreVerdict.UNKNOWN, StoreAvailabilityService.verdictFromPage("<html>no buttons here</html>", twt2Ps5))
        assertEquals(StoreVerdict.UNKNOWN, StoreAvailabilityService.verdictFromPage("", twt2Ps5))
    }

    @Test
    fun `a product id without a PPSA or CUSA number is unknown`() {
        assertEquals(StoreVerdict.UNKNOWN, StoreAvailabilityService.verdictFromPage(page(twt2Ps5), "no-number-here"))
    }

    @Test
    fun `the same game under another region's PPSA number is matched by number so US and EU pages agree`() {
        // same PPSA number, different region prefix / suffix
        val html = page("UP4008-PPSA02019_00-TWT2USUSUSUSUSUS")

        assertEquals(StoreVerdict.AVAILABLE, StoreAvailabilityService.verdictFromPage(html, twt2Ps5))
    }

    @Test
    fun `status codes map to verdicts`() {
        val ok = page(twt2Ps5)
        assertEquals(StoreVerdict.AVAILABLE, StoreAvailabilityService.verdictFromStatus(200, ok, twt2Ps5))
        assertEquals(StoreVerdict.UNAVAILABLE, StoreAvailabilityService.verdictFromStatus(404, "", twt2Ps5))
        assertEquals(StoreVerdict.UNAVAILABLE, StoreAvailabilityService.verdictFromStatus(410, "", twt2Ps5))
        assertEquals(StoreVerdict.UNKNOWN, StoreAvailabilityService.verdictFromStatus(403, ok, twt2Ps5))
        assertEquals(StoreVerdict.UNKNOWN, StoreAvailabilityService.verdictFromStatus(429, "", twt2Ps5))
        assertEquals(StoreVerdict.UNKNOWN, StoreAvailabilityService.verdictFromStatus(503, "", twt2Ps5))
    }
}
