package com.metallic.chiaki.trophy

import org.junit.Assert.assertEquals
import org.junit.Test

class PollBackoffTest {

    private val base = 30_000L
    private val max = 300_000L

    @Test
    fun `stays at the base interval while polls succeed`() {
        val b = PollBackoff(base, max)
        repeat(5) { b.onSuccess(); assertEquals(base, b.nextDelayMs()) }
    }

    @Test
    fun `doubles after each consecutive failure up to the maximum`() {
        val b = PollBackoff(base, max)
        val delays = (1..7).map { b.onFailure(); b.nextDelayMs() }

        assertEquals(listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L, 300_000L, 300_000L), delays)
    }

    @Test
    fun `a success resets it to the base interval`() {
        val b = PollBackoff(base, max)
        repeat(4) { b.onFailure() }
        b.onSuccess()

        assertEquals(base, b.nextDelayMs())
    }

    @Test
    fun `many failures never overflow or exceed the maximum`() {
        val b = PollBackoff(base, max)
        repeat(1000) { b.onFailure() }

        assertEquals(max, b.nextDelayMs())
    }
}
