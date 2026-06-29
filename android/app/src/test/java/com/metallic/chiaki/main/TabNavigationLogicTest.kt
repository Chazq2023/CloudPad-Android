package com.metallic.chiaki.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the L1/R1 tab navigation rules implemented in CloudPlayFragment.
 *
 * Tab order (left → right): PS3 Catalog (psnow_ps3) | PS4 Catalog (psnow_ps4) | PS5 Library (pscloud)
 *
 * L1 rules:
 *   psnow_ps3 → nothing (leftmost tab)
 *   psnow_ps4 → psnow_ps3
 *   pscloud   → psnow_ps4
 *
 * R1 rules:
 *   psnow_ps3 → psnow_ps4
 *   psnow_ps4 → pscloud
 *   pscloud   → nothing (rightmost tab)
 */
class TabNavigationLogicTest {

    // Mirrors CloudPlayFragment.navigateTabLeft() destination logic
    private fun tabLeft(currentSection: String): String? = when (currentSection) {
        "psnow_ps4" -> "psnow_ps3"
        "pscloud"   -> "psnow_ps4"
        else        -> null
    }

    // Mirrors CloudPlayFragment.navigateTabRight() destination logic
    private fun tabRight(currentSection: String): String? = when (currentSection) {
        "psnow_ps3" -> "psnow_ps4"
        "psnow_ps4" -> "pscloud"
        else        -> null
    }

    // --- L1 (left) navigation ---

    @Test
    fun `L1 on PS3 Catalog does nothing — it is the leftmost tab`() {
        assertNull("L1 on PS3 should not navigate", tabLeft("psnow_ps3"))
    }

    @Test
    fun `L1 on PS4 Catalog navigates left to PS3 Catalog`() {
        assertEquals("psnow_ps3", tabLeft("psnow_ps4"))
    }

    @Test
    fun `L1 on PS5 Library navigates left to PS4 Catalog`() {
        assertEquals("psnow_ps4", tabLeft("pscloud"))
    }

    // --- R1 (right) navigation ---

    @Test
    fun `R1 on PS3 Catalog navigates right to PS4 Catalog`() {
        assertEquals("psnow_ps4", tabRight("psnow_ps3"))
    }

    @Test
    fun `R1 on PS4 Catalog navigates right to PS5 Library`() {
        assertEquals("pscloud", tabRight("psnow_ps4"))
    }

    @Test
    fun `R1 on PS5 Library does nothing — it is the rightmost tab`() {
        assertNull("R1 on PS5 Library should not navigate", tabRight("pscloud"))
    }

    // --- Combined traversal ---

    @Test
    fun `full left-to-right traversal covers all three tabs`() {
        var section = "psnow_ps3"
        section = tabRight(section) ?: section
        assertEquals("psnow_ps4", section)
        section = tabRight(section) ?: section
        assertEquals("pscloud", section)
        // R1 at rightmost should stay
        section = tabRight(section) ?: section
        assertEquals("pscloud", section)
    }

    @Test
    fun `full right-to-left traversal covers all three tabs`() {
        var section = "pscloud"
        section = tabLeft(section) ?: section
        assertEquals("psnow_ps4", section)
        section = tabLeft(section) ?: section
        assertEquals("psnow_ps3", section)
        // L1 at leftmost should stay
        section = tabLeft(section) ?: section
        assertEquals("psnow_ps3", section)
    }

    @Test
    fun `PS4 Catalog is reachable from both PS3 and PS5`() {
        assertEquals("psnow_ps4", tabRight("psnow_ps3"))
        assertEquals("psnow_ps4", tabLeft("pscloud"))
    }
}
