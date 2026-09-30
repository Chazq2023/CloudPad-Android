package com.metallic.chiaki.cloudplay.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DatacenterBatchRetryTest
{
	@Test
	fun `distant batch is retried until a usable one is offered`() = runTest {
		var calls = 0
		val result = retryUnusableDatacenterBatches(4, { false }, { "exhausted" }) { _, _ ->
			calls++
			if (calls < 3) throw PingTimeoutException("lgab 83ms")
			"lonb"
		}

		assertEquals("lonb", result)
		assertEquals(3, calls)
	}

	@Test
	fun `ping failure is rethrown once attempts run out`() = runTest {
		var calls = 0
		assertThrows(PingTimeoutException::class.java) {
			kotlinx.coroutines.runBlocking {
				retryUnusableDatacenterBatches(4, { false }, { "exhausted" }) { _, _ ->
					calls++
					throw PingTimeoutException("lgab 83ms")
				}
			}
		}
		assertEquals(4, calls)
	}

	@Test
	fun `missing manual datacenter is retried then resolves to the exhausted result`() = runTest {
		var calls = 0
		val result = retryUnusableDatacenterBatches(4, { false }, { "exhausted" }) { _, _ ->
			calls++
			throw DatacenterNotOfferedException("lonb not available")
		}

		assertEquals("exhausted", result)
		assertEquals(4, calls)
	}

	@Test
	fun `cancellation stops retrying`() = runTest {
		var calls = 0
		val result = retryUnusableDatacenterBatches(4, { calls >= 1 }, { "exhausted" }) { _, _ ->
			calls++
			throw DatacenterNotOfferedException("lonb not available")
		}

		assertEquals("exhausted", result)
		assertEquals(1, calls)
	}

	@Test
	fun `other failures are not retried`() = runTest {
		var calls = 0
		assertThrows(GaikaiAllocationException::class.java) {
			kotlinx.coroutines.runBlocking {
				retryUnusableDatacenterBatches(4, { false }, { "exhausted" }) { _, _ ->
					calls++
					throw GaikaiAllocationException("Lock failed")
				}
			}
		}
		assertEquals(1, calls)
	}

	@Test
	fun `distant batch is rejected until the last attempt accepts it`() = runTest {
		val lastFlags = mutableListOf<Boolean>()
		val result = retryUnusableDatacenterBatches(4, { false }, { "exhausted" }) { _, isLastAttempt ->
			lastFlags += isLastAttempt
			if (!isLastAttempt) throw DistantDatacenterBatchException("lgab 78ms, lonb 10ms before")
			"lgab"
		}

		assertEquals("lgab", result)
		assertEquals(listOf(false, false, false, true), lastFlags)
	}

	// Retry for nearest datacenter off → maxAttempts = 1: the pre-retry behaviour.

	@Test
	fun `single attempt treats the first attempt as last so a distant batch is accepted`() = runTest {
		var calls = 0
		val result = retryUnusableDatacenterBatches(1, { false }, { "exhausted" }) { attempt, isLastAttempt ->
			calls++
			assertEquals(1, attempt)
			if (!isLastAttempt) throw DistantDatacenterBatchException("lgab 78ms, lonb 10ms before")
			"lgab"
		}

		assertEquals("lgab", result)
		assertEquals(1, calls)
	}

	@Test
	fun `single attempt surfaces ping failure without retrying`() = runTest {
		var calls = 0
		assertThrows(PingTimeoutException::class.java) {
			kotlinx.coroutines.runBlocking {
				retryUnusableDatacenterBatches(1, { false }, { "exhausted" }) { _, _ ->
					calls++
					throw PingTimeoutException("lgab 83ms")
				}
			}
		}
		assertEquals(1, calls)
	}

	@Test
	fun `single attempt resolves a missing manual datacenter without retrying`() = runTest {
		var calls = 0
		val result = retryUnusableDatacenterBatches(1, { false }, { "exhausted" }) { _, _ ->
			calls++
			throw DatacenterNotOfferedException("lonb not available")
		}

		assertEquals("exhausted", result)
		assertEquals(1, calls)
	}
}
