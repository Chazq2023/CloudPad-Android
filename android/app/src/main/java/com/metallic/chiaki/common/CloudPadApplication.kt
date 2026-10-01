// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.app.Application

class CloudPadApplication : Application()
{
	override fun onCreate()
	{
		super.onCreate()
		ClockGuard.install(this)
	}
}
