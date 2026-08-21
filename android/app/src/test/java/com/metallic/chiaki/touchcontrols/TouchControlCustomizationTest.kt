// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchControlCustomizationTest
{
	@Test
	fun analogSticksAreAlwaysShownByDefault()
	{
		assertTrue(TouchControl.LEFT_STICK.defaultAlwaysShow)
		assertTrue(TouchControl.RIGHT_STICK.defaultAlwaysShow)
	}

	@Test
	fun otherControlsAreNotAlwaysShownByDefault()
	{
		TouchControl.values()
			.filterNot { it == TouchControl.LEFT_STICK || it == TouchControl.RIGHT_STICK }
			.forEach { assertFalse(it.defaultAlwaysShow) }
	}
}
