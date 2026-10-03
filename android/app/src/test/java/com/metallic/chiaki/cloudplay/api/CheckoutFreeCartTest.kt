package com.metallic.chiaki.cloudplay.api

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutFreeCartTest {

    private fun cart(json: String) = JSONObject(json)

    @Test
    fun `a zero-priced cart is free`() {
        // Shape of the real preview cart seen for Dying Light 2 / AC Syndicate (£0.00).
        assertTrue(PSKamajiSession.isFreeCart(cart("""{"total_price_value":0,"total_price":"£0.00"}""")))
    }

    @Test
    fun `a priced cart is not free`() {
        assertFalse(PSKamajiSession.isFreeCart(cart("""{"total_price_value":1499,"total_price":"£14.99"}""")))
    }

    @Test
    fun `a missing price is not read as free`() {
        assertFalse(PSKamajiSession.isFreeCart(cart("""{"total_price":"£0.00"}""")))
        assertFalse(PSKamajiSession.isFreeCart(cart("""{"totalPrice":0}""")))
    }

    @Test
    fun `a non-numeric or null price is not read as free`() {
        assertFalse(PSKamajiSession.isFreeCart(cart("""{"total_price_value":"0"}""")))
        assertFalse(PSKamajiSession.isFreeCart(cart("""{"total_price_value":"free"}""")))
        assertFalse(PSKamajiSession.isFreeCart(cart("""{"total_price_value":null}""")))
    }

    @Test
    fun `a fractional price is not free`() {
        assertFalse(PSKamajiSession.isFreeCart(cart("""{"total_price_value":0.5}""")))
    }
}
