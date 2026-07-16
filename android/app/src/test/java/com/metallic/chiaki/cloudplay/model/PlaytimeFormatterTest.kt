package com.metallic.chiaki.cloudplay.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaytimeFormatterTest {

    @Test
    fun `total playtime of zero is not yet played`() {
        assertEquals("Not yet played", PlaytimeFormatter.formatTotalPlaytime(0L))
        assertEquals("Not yet played", PlaytimeFormatter.formatTotalPlaytime(-1L))
    }

    @Test
    fun `total playtime under an hour shows minutes only`() {
        assertEquals("5m", PlaytimeFormatter.formatTotalPlaytime(5 * 60_000L))
    }

    @Test
    fun `total playtime under a day shows hours and minutes but no days`() {
        val ms = (3 * 60 + 20) * 60_000L // 3h 20m
        assertEquals("3h 20m", PlaytimeFormatter.formatTotalPlaytime(ms))
    }

    @Test
    fun `total playtime over a day shows days hours and minutes`() {
        val ms = (26 * 60 + 15) * 60_000L // 1d 2h 15m
        assertEquals("1d 2h 15m", PlaytimeFormatter.formatTotalPlaytime(ms))
    }

    @Test
    fun `total playtime with whole days and no leftover hours still prints 0h`() {
        val ms = 48 * 60 * 60_000L // exactly 2 days
        assertEquals("2d 0h 0m", PlaytimeFormatter.formatTotalPlaytime(ms))
    }

    @Test
    fun `session duration of zero is not yet played`() {
        assertEquals("Not yet played", PlaytimeFormatter.formatSessionDuration(0L))
        assertEquals("Not yet played", PlaytimeFormatter.formatSessionDuration(-5L))
    }

    @Test
    fun `session duration under an hour omits the hours component`() {
        assertEquals("45m", PlaytimeFormatter.formatSessionDuration(45 * 60_000L))
    }

    @Test
    fun `session duration over an hour shows hours and minutes`() {
        val ms = (2 * 60 + 5) * 60_000L // 2h 5m
        assertEquals("2h 5m", PlaytimeFormatter.formatSessionDuration(ms))
    }

    @Test
    fun `session duration rounds down partial minutes`() {
        // 90 seconds -> 1 minute (integer division truncates, matches total playtime rounding)
        assertEquals("1m", PlaytimeFormatter.formatSessionDuration(90_000L))
    }
}
