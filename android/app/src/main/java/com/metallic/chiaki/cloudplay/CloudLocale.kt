// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay

import java.util.Locale

object CloudLocale {
	fun parseStorePath(stored: String): Pair<String, String> {
		val trimmed = stored.trim()

		if (trimmed.isEmpty()) {
			return "US" to "en"
		}

		val locale = Locale.forLanguageTag(trimmed)

		val language = locale.language
			.lowercase()
			.takeIf { it.isNotEmpty() }
			?: trimmed.substringBefore("-").lowercase().takeIf { it.isNotEmpty() }
			?: "en"

		val country = locale.country
			.uppercase()
			.takeIf { it.isNotEmpty() }
			?: trimmed.substringAfter("-", "US").uppercase().takeIf { it.length == 2 }
			?: "US"

		return country to language
	}

	fun fromSession(language: String?, country: String?): String? {
		val lang = language?.trim()?.lowercase().orEmpty()
		val cty = country?.trim()?.uppercase().orEmpty()

		if (lang.isEmpty() || cty.isEmpty()) return null

		return "$lang-$cty"
	}

	fun gaikaiLanguageForLocale(locale: String): String {
		val parsed = Locale.forLanguageTag(locale.trim())

		val language = parsed.language
			.lowercase()
			.takeIf { it.isNotEmpty() }
			?: locale.substringBefore("-").lowercase().takeIf { it.isNotEmpty() }
			?: "en"

		return language
	}
}
