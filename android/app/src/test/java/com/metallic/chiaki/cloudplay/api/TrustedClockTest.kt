package com.metallic.chiaki.cloudplay.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrustedClockTest {

    private var elapsed = 0L

    // Thu, 01 Oct 2026 17:00:00 GMT
    private val sonyMs = 1790874000000L
    private val sonyDate = "Thu, 01 Oct 2026 17:00:00 GMT"

    @Before
    fun setUp() {
        TrustedClock.reset()
        TrustedClock.elapsedRealtime = { elapsed }
    }

    @After
    fun tearDown() = TrustedClock.reset()

    @Test
    fun `parses HTTP Date headers`() {
        assertEquals(sonyMs, TrustedClock.parseHttpDate(sonyDate))
        assertNull(TrustedClock.parseHttpDate("garbage"))
    }

    @Test
    fun `no Sony response yet means no trusted time`() {
        assertNull(TrustedClock.nowOrNull())
    }

    @Test
    fun `time comes from Sony and advances with elapsed time, not the wall clock`() {
        elapsed = 5_000
        TrustedClock.recordResponse("ca.account.sony.com", mapOf("Date" to listOf(sonyDate)))
        assertEquals(sonyMs, TrustedClock.nowOrNull())

        elapsed = 65_000
        assertEquals(sonyMs + 60_000, TrustedClock.nowOrNull())
    }

    @Test
    fun `header name match is case-insensitive and null status-line key is ignored`() {
        TrustedClock.recordResponse("cc.prod.gaikai.com", mapOf(null to listOf("HTTP/1.1 200 OK"), "date" to listOf(sonyDate)))
        assertEquals(sonyMs, TrustedClock.nowOrNull())
    }

    @Test
    fun `responses from non-Sony hosts are ignored`() {
        TrustedClock.recordResponse("example.com", mapOf("Date" to listOf(sonyDate)))
        TrustedClock.recordResponse("notsony.com", mapOf("Date" to listOf(sonyDate)))
        assertNull(TrustedClock.nowOrNull())
    }

    @Test
    fun `Sony host matching`() {
        assertTrue(TrustedClock.isSonyHost("commerce.api.np.km.playstation.net"))
        assertTrue(TrustedClock.isSonyHost("sony.com"))
        assertFalse(TrustedClock.isSonyHost("evilsony.com"))
    }
}
