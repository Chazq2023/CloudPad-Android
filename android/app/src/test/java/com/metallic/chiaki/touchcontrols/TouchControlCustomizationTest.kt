// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import org.junit.Assert.assertEquals
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

	@Test
	fun touchpadOffersAlwaysShowAndIsHiddenByDefault()
	{
		assertTrue(TouchControl.TOUCHPAD.hasAlwaysShowOption)
		assertTrue(TouchControl.TOUCHPAD.alwaysShowGatesStyle)
		assertFalse(TouchControl.TOUCHPAD.defaultAlwaysShow)
	}

	@Test
	fun onlySticksAndTouchpadOfferAlwaysShow()
	{
		val expected = setOf(TouchControl.LEFT_STICK, TouchControl.RIGHT_STICK, TouchControl.TOUCHPAD)
		assertEquals(expected, TouchControl.values().filter { it.hasAlwaysShowOption }.toSet())
	}

	@Test
	fun touchpadSlidersAreIgnoredWhileNotAlwaysShown()
	{
		val style = TouchControlStyle(sizePercent = 140, opacityPercent = 90, offsetXPermille = 12, alwaysShow = false)
		val effective = style.effectiveFor(TouchControl.TOUCHPAD)
		assertEquals(TouchControlStyle.DEFAULT_SIZE_PERCENT, effective.sizePercent)
		assertEquals(TouchControlStyle.DEFAULT_OPACITY_PERCENT, effective.opacityPercent)
		assertEquals(12, effective.offsetXPermille)
	}

	@Test
	fun touchpadSlidersApplyWhileAlwaysShown()
	{
		val style = TouchControlStyle(sizePercent = 140, opacityPercent = 90, alwaysShow = true)
		assertEquals(style, style.effectiveFor(TouchControl.TOUCHPAD))
	}

	@Test
	fun otherControlsAlwaysUseTheirSliders()
	{
		val style = TouchControlStyle(sizePercent = 140, opacityPercent = 90, alwaysShow = false)
		TouchControl.values().filterNot { it == TouchControl.TOUCHPAD }
			.forEach { assertEquals(style, style.effectiveFor(it)) }
	}
}
