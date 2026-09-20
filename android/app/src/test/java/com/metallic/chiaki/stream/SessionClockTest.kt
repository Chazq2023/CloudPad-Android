package com.metallic.chiaki.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionClockTest {

    private var nowMs = 1_000_000L
    private val clock = SessionClock { nowMs }

    @Test
    fun `reads zero before the stream has connected`() {
        nowMs += 45_000
        assertEquals(0L, clock.elapsedSeconds())
    }

    @Test
    fun `starts at zero when the stream connects`() {
        clock.markConnected()
        assertEquals(0L, clock.elapsedSeconds())
    }

    @Test
    fun `counts whole seconds since connecting`() {
        clock.markConnected()
        nowMs += 61_999
        assertEquals(61L, clock.elapsedSeconds())
    }

    @Test
    fun `a later connect (restart or resume) does not reset the clock`() {
        clock.markConnected()
        nowMs += 30_000
        clock.markConnected()
        nowMs += 30_000
        assertEquals(60L, clock.elapsedSeconds())
    }

    @Test
    fun `time before connecting is not counted`() {
        nowMs += 90_000 // e.g. cloud allocation
        clock.markConnected()
        nowMs += 5_000
        assertEquals(5L, clock.elapsedSeconds())
    }

    @Test
    fun `a separate clock starts fresh`() {
        clock.markConnected()
        nowMs += 100_000
        val next = SessionClock { nowMs }
        next.markConnected()
        assertEquals(0L, next.elapsedSeconds())
    }

    @Test
    fun `formats as hh mm ss`() {
        assertEquals("00:00:00", SessionClock.format(0))
        assertEquals("00:00:09", SessionClock.format(9))
        assertEquals("00:01:05", SessionClock.format(65))
        assertEquals("01:00:00", SessionClock.format(3600))
        assertEquals("01:02:03", SessionClock.format(3723))
        assertEquals("12:34:56", SessionClock.format(12 * 3600 + 34 * 60 + 56))
    }

    @Test
    fun `hours grow past two digits instead of wrapping`() {
        assertEquals("100:00:00", SessionClock.format(100 * 3600L))
    }

    @Test
    fun `negative input formats as zero`() {
        assertEquals("00:00:00", SessionClock.format(-5))
    }
}
