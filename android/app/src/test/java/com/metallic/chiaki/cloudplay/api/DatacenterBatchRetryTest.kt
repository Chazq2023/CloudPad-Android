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
		val result = retryUnusableDatacenterBatches(4, { false }, { "exhausted" }) {
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
				retryUnusableDatacenterBatches(4, { false }, { "exhausted" }) {
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
		val result = retryUnusableDatacenterBatches(4, { false }, { "exhausted" }) {
			calls++
			throw DatacenterNotOfferedException("lonb not available")
		}

		assertEquals("exhausted", result)
		assertEquals(4, calls)
	}

	@Test
	fun `cancellation stops retrying`() = runTest {
		var calls = 0
		val result = retryUnusableDatacenterBatches(4, { calls >= 1 }, { "exhausted" }) {
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
				retryUnusableDatacenterBatches(4, { false }, { "exhausted" }) {
					calls++
					throw GaikaiAllocationException("Lock failed")
				}
			}
		}
		assertEquals(1, calls)
	}
}
