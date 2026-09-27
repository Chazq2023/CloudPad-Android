package com.metallic.chiaki.common

import com.metallic.chiaki.common.Preferences.CongestionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CongestionModeTest {

    @Test
    fun `nothing stored defaults to Adapt to network`() {
        assertEquals(CongestionMode.ADAPT, CongestionMode.fromStored(null))
    }

    @Test
    fun `stored values round-trip`() {
        assertEquals(CongestionMode.ADAPT, CongestionMode.fromStored("adapt"))
        assertEquals(CongestionMode.HOLD, CongestionMode.fromStored("hold"))
    }

    @Test
    fun `an unrecognised stored value falls back to the default`() {
        assertEquals(CongestionMode.ADAPT, CongestionMode.fromStored("bogus"))
    }

    @Test
    fun `presets map to the expected packet loss limits`() {
        // Hold must stay exactly 0 — that's the pre-setting behaviour (client never reports loss).
        assertEquals(0.05f, CongestionMode.ADAPT.packetLossMax, 0f)
        assertEquals(0.0f, CongestionMode.HOLD.packetLossMax, 0f)
    }

    @Test
    fun `stored values are stable`() {
        assertEquals(listOf("adapt", "hold"), CongestionMode.values().map { it.value })
    }
}
