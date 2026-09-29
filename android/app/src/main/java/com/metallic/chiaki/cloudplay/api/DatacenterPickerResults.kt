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

	/**
	 * Returns the closest previously measured datacenter that isn't in [batch] and beat
	 * [batchBestRtt] by more than [marginMs], or null. Synthetic results (the 20 ms / MTU-out 1254
	 * placeholder written for manual or fallback selections) aren't real measurements and are ignored.
	 */
	fun closerKnownDatacenter(priorJson: String, batch: JSONArray, batchBestRtt: Int, marginMs: Int): JSONObject?
	{
		val prior = parseArray(priorJson)
		var closest: JSONObject? = null
		for (i in 0 until prior.length())
		{
			val previous = prior.optJSONObject(i) ?: continue
			val name = previous.optString("dataCenter")
			val rtt = previous.optInt("rtt", -1)
			if (name.isEmpty() || findByName(batch, name) != null) continue
			if (!isRealMeasurement(previous)) continue
			if (rtt + marginMs >= batchBestRtt) continue
			if (closest == null || rtt < closest.getInt("rtt")) closest = previous
		}
		return closest?.let { copy(it) }
	}

	/** True when [priorJson] holds a real measurement for any datacenter outside [batch]. */
	fun hasMeasurementOutside(priorJson: String, batch: JSONArray): Boolean
	{
		val prior = parseArray(priorJson)
		for (i in 0 until prior.length())
		{
			val previous = prior.optJSONObject(i) ?: continue
			val name = previous.optString("dataCenter")
			if (name.isEmpty() || findByName(batch, name) != null) continue
			if (isRealMeasurement(previous)) return true
		}
		return false
	}

	private fun isRealMeasurement(result: JSONObject): Boolean
	{
		val rtt = result.optInt("rtt", -1)
		if (rtt <= 0 || rtt >= 999) return false
		return !(rtt == SYNTHETIC_RTT && result.optInt("mtu_out") == SYNTHETIC_MTU_OUT)
	}

	private const val SYNTHETIC_RTT = 20
	private const val SYNTHETIC_MTU_OUT = 1254

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
