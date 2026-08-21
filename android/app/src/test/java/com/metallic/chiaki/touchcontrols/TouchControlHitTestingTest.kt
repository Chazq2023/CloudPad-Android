// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchControlHitTestingTest
{
	@Test
	fun acceptsPointsWithinDrawableBounds()
	{
		assertTrue(isInsideDrawableBounds(10f, 20f, 10, 20, 50, 60))
		assertTrue(isInsideDrawableBounds(49.9f, 59.9f, 10, 20, 50, 60))
	}

	@Test
	fun rejectsPointsInPaddingOrBeyondDrawableBounds()
	{
		assertFalse(isInsideDrawableBounds(9.9f, 30f, 10, 20, 50, 60))
		assertFalse(isInsideDrawableBounds(30f, 19.9f, 10, 20, 50, 60))
		assertFalse(isInsideDrawableBounds(50f, 30f, 10, 20, 50, 60))
		assertFalse(isInsideDrawableBounds(30f, 60f, 10, 20, 50, 60))
	}
}
