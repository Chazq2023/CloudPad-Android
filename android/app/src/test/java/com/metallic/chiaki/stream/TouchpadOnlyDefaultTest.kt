package com.metallic.chiaki.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the "Touchpad Only" defaulting rule used by StreamViewModel and DualScreenController's
 * default-updating callback: a dual-screen device (real secondary display, or a foldable's
 * separating hinge) should default the switch on, a single-screen device should default it off,
 * and once the user has explicitly flipped it themselves, their stored choice always wins.
 */
class TouchpadOnlyDefaultTest {

    @Test
    fun `defaults on for a dual-screen device when never explicitly set`() {
        assertEquals(
            true,
            TouchpadOnlyDefault.resolve(explicitlySet = false, storedValue = false, isDualScreen = true)
        )
    }

    @Test
    fun `defaults off for a single-screen device when never explicitly set`() {
        assertEquals(
            false,
            TouchpadOnlyDefault.resolve(explicitlySet = false, storedValue = false, isDualScreen = false)
        )
    }

    @Test
    fun `stored value wins once explicitly set, even on a dual-screen device`() {
        assertEquals(
            false,
            TouchpadOnlyDefault.resolve(explicitlySet = true, storedValue = false, isDualScreen = true)
        )
    }

    @Test
    fun `stored value wins once explicitly set, even on a single-screen device`() {
        assertEquals(
            true,
            TouchpadOnlyDefault.resolve(explicitlySet = true, storedValue = true, isDualScreen = false)
        )
    }
}
