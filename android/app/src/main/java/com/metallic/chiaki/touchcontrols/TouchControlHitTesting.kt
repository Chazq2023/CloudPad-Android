// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

internal fun isInsideDrawableBounds(
	x: Float,
	y: Float,
	left: Int,
	top: Int,
	right: Int,
	bottom: Int
) = x >= left && x < right && y >= top && y < bottom
