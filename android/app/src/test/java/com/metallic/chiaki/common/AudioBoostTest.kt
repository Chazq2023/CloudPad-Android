package com.metallic.chiaki.common

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBoostTest {

    @Test
    fun `boost gain actually makes audio louder`() {
        // The native side clamps anything below 1.0 to unity, so a gain at or below 1 would
        // silently make the toggle do nothing.
        assertTrue(Preferences.AUDIO_BOOST_GAIN > 1.0f)
    }

    @Test
    fun `boost gain stays in a range the limiter can handle without heavy distortion`() {
        assertTrue(Preferences.AUDIO_BOOST_GAIN <= 3.0f)
    }
}
