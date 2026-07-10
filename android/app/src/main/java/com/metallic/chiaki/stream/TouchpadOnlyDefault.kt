// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

/**
 * Decides the effective "Touchpad Only" value: the user's stored choice once they've actually
 * flipped the switch themselves, otherwise a device-appropriate default — on for dual-screen
 * devices (a persistent touchpad belongs on the second screen), off everywhere else.
 */
object TouchpadOnlyDefault
{
	fun resolve(explicitlySet: Boolean, storedValue: Boolean, isDualScreen: Boolean): Boolean =
		if(explicitlySet) storedValue else isDualScreen
}
