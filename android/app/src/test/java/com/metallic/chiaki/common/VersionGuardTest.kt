package com.metallic.chiaki.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionGuardTest {

    @Test
    fun `compares release versions numerically, not as text`() {
        assertTrue(VersionGuard.compareVersions("0.1.9", "0.1.10")!! < 0)
        assertTrue(VersionGuard.compareVersions("0.1.60", "0.1.59")!! > 0)
        assertEquals(0, VersionGuard.compareVersions("0.1.59", "0.1.59"))
        assertTrue(VersionGuard.compareVersions("0.2.0", "0.1.99")!! > 0)
        assertEquals(0, VersionGuard.compareVersions("0.1", "0.1.0"))
    }

    @Test
    fun `malformed versions don't compare`() {
        assertNull(VersionGuard.compareVersions("0.1.x", "0.1.2"))
        assertNull(VersionGuard.compareVersions("", "0.1.2"))
    }

    @Test
    fun `older release than the minimum is outdated`() {
        assertTrue(VersionGuard.isOutdated("0.1.58", "0.1.59"))
        assertFalse(VersionGuard.isOutdated("0.1.59", "0.1.59"))
        assertFalse(VersionGuard.isOutdated("0.1.60", "0.1.59"))
    }

    @Test
    fun `anything uncertain lets the app run`() {
        assertFalse(VersionGuard.isOutdated("0.1.10", null))
        assertFalse(VersionGuard.isOutdated("0.1.10", ""))
        assertFalse(VersionGuard.isOutdated("", "0.1.59"))
        assertFalse(VersionGuard.isOutdated("0.1.10", "garbage"))
    }
}
