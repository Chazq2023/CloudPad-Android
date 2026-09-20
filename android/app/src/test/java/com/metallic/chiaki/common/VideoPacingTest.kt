package com.metallic.chiaki.common

import com.metallic.chiaki.common.Preferences.VideoPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPacingTest {

    @Test
    fun `stored value wins over the legacy switch`() {
        assertEquals(VideoPacing.STANDARD, VideoPacing.fromStored("standard", legacyAdaptiveEnabled = true))
        assertEquals(VideoPacing.SMOOTH, VideoPacing.fromStored("smooth", legacyAdaptiveEnabled = false))
    }

    @Test
    fun `legacy adaptive pacing on migrates to Smooth`() {
        assertEquals(VideoPacing.SMOOTH, VideoPacing.fromStored(null, legacyAdaptiveEnabled = true))
    }

    @Test
    fun `nothing stored and legacy off is Standard`() {
        assertEquals(VideoPacing.STANDARD, VideoPacing.fromStored(null, legacyAdaptiveEnabled = false))
    }

    @Test
    fun `an unrecognised stored value falls back to the legacy switch`() {
        assertEquals(VideoPacing.SMOOTH, VideoPacing.fromStored("bogus", legacyAdaptiveEnabled = true))
        assertEquals(VideoPacing.STANDARD, VideoPacing.fromStored("bogus", legacyAdaptiveEnabled = false))
    }

    @Test
    fun `only Smooth reports isSmooth`() {
        assertTrue(VideoPacing.SMOOTH.isSmooth)
        assertFalse(VideoPacing.STANDARD.isSmooth)
    }

    @Test
    fun `stored values are stable`() {
        assertEquals(listOf("standard", "smooth"), VideoPacing.values().map { it.value })
    }
}
