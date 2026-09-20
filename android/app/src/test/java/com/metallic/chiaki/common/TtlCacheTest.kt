package com.metallic.chiaki.common

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtlCacheTest {

    private var clock = 1_000L
    private val cache = TtlCache<String, List<Int>>(ttlMs = 100) { clock }

    @Test
    fun `returns a stored value until it expires`() {
        cache.put("a", listOf(1))

        clock += 99
        assertEquals(listOf(1), cache.get("a"))
        clock += 1
        assertNull(cache.get("a"))
    }

    @Test
    fun `getOrLoad loads once and then serves from cache`() = runTest {
        var loads = 0
        repeat(3) { cache.getOrLoad("a") { loads++; listOf(7) } }

        assertEquals(1, loads)
    }

    @Test
    fun `getOrLoad reloads after expiry`() = runTest {
        var loads = 0
        cache.getOrLoad("a") { loads++; listOf(1) }
        clock += 100
        cache.getOrLoad("a") { loads++; listOf(2) }

        assertEquals(2, loads)
    }

    @Test
    fun `an answer that is not worth keeping is returned but not cached`() = runTest {
        var loads = 0
        repeat(2) { cache.getOrLoad("a", worthKeeping = { it.isNotEmpty() }) { loads++; emptyList() } }

        assertEquals(2, loads)
    }

    @Test
    fun `a clock that goes backwards does not serve stale data`() {
        cache.put("a", listOf(1))
        clock -= 500

        assertNull(cache.get("a"))
    }

    @Test
    fun `interval gate lets one through per interval`() {
        var t = 10_000L
        val gate = MinIntervalGate(60_000) { t }

        assertTrue(gate.tryAcquire())
        t += 59_999
        assertFalse(gate.tryAcquire())
        t += 1
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }
}
