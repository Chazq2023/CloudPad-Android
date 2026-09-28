// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalogStickGeometryTest
{
	@Test
	fun unclampedBaseKeepsNaturalHandleGeometry()
	{
		val (travel, handle) = scaledHandleGeometry(80f, 40f, 120f)
		assertEquals(80f, travel, 0.001f)
		assertEquals(40f, handle, 0.001f)
	}

	@Test
	fun clampedBaseScalesHandleAndTravelProportionally()
	{
		val (travel, handle) = scaledHandleGeometry(80f, 40f, 60f)
		assertEquals(40f, travel, 0.001f)
		assertEquals(20f, handle, 0.001f)
	}

	@Test
	fun fullyDeflectedHandleStaysInsideClampedBase()
	{
		val circleRadius = 45f
		val (travel, handle) = scaledHandleGeometry(80f, 40f, circleRadius)
		assertTrue(travel + handle <= circleRadius + 0.001f)
	}
}
