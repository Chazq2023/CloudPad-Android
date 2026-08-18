// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.api

import org.json.JSONArray
import org.json.JSONObject

/** Builds the persisted datacenter list used by the Settings picker. */
internal object DatacenterPickerResults
{
	fun merge(
		apiDatacenters: JSONArray,
		pingResults: JSONArray,
		priorJson: String,
		preferPrior: Boolean = false
	): JSONArray
	{
		val prior = parseArray(priorJson)
		val merged = JSONArray()

		for (i in 0 until apiDatacenters.length())
		{
			val apiDatacenter = apiDatacenters.optJSONObject(i) ?: continue
			val name = apiDatacenter.optString("dataCenter")
			if (name.isEmpty()) continue

			val previous = findByName(prior, name)
			val current = findByName(pingResults, name)
			val selected = if (preferPrior) previous ?: current else current ?: previous
			merged.put(copy(selected ?: apiDatacenter))
		}

		// Preserve previously discovered datacenters that are absent from this title's API list.
		for (i in 0 until prior.length())
		{
			val previous = prior.optJSONObject(i) ?: continue
			val name = previous.optString("dataCenter")
			if (name.isNotEmpty() && findByName(merged, name) == null)
			{
				merged.put(copy(previous))
			}
		}

		return merged
	}

	private fun parseArray(json: String): JSONArray = try
	{
		if (json.isBlank()) JSONArray() else JSONArray(json)
	}
	catch (_: Exception)
	{
		JSONArray()
	}

	private fun findByName(datacenters: JSONArray, name: String): JSONObject?
	{
		for (i in 0 until datacenters.length())
		{
			val datacenter = datacenters.optJSONObject(i) ?: continue
			if (datacenter.optString("dataCenter") == name) return datacenter
		}
		return null
	}

	private fun copy(value: JSONObject) = JSONObject(value.toString())
}
