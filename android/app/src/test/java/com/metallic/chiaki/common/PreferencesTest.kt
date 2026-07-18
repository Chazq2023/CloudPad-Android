package com.metallic.chiaki.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesTest {

    // --- recommendedCloudBitrateKbps() ---

    @Test
    fun `recommends 10000 kbps at and below 720p`() {
        assertEquals(10000, Preferences.recommendedCloudBitrateKbps(720))
        assertEquals(10000, Preferences.recommendedCloudBitrateKbps(540))
    }

    @Test
    fun `recommends 15000 kbps at 1080p`() {
        assertEquals(15000, Preferences.recommendedCloudBitrateKbps(1080))
    }

    @Test
    fun `recommends 25000 kbps at 1440p`() {
        assertEquals(25000, Preferences.recommendedCloudBitrateKbps(1440))
    }

    @Test
    fun `recommends 40000 kbps at 2160p`() {
        assertEquals(40000, Preferences.recommendedCloudBitrateKbps(2160))
        assertEquals(40000, Preferences.recommendedCloudBitrateKbps(4320))
    }
}
