// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * The first non-default display flagged for content presentation — e.g. the second panel of
 * an LG Dual Screen accessory or a ZTE Axon M — or null if this device only exposes one screen.
 * Works from any [Context] since [DisplayManager] reports displays system-wide.
 */
fun Context.secondaryPresentationDisplay(): Display?
{
	val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
	return displayManager.displays.firstOrNull {
		it.displayId != Display.DEFAULT_DISPLAY && (it.flags and Display.FLAG_PRESENTATION) != 0
	}
}
