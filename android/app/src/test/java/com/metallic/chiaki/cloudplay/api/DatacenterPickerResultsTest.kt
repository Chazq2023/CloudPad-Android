package com.metallic.chiaki.cloudplay.api

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class DatacenterPickerResultsTest
{
	@Test
	fun `current ping results replace prior measurements and retain all api datacenters`()
	{
		val api = JSONArray("""[
			{"dataCenter":"lonb","publicIp":"1.1.1.1","port":2053},
			{"dataCenter":"frpa","publicIp":"2.2.2.2","port":2053}
		]""")
		val current = JSONArray("""[{"dataCenter":"lonb","rtt":15}]""")
		val prior = """[{"dataCenter":"lonb","rtt":30},{"dataCenter":"frpa","rtt":40}]"""

		val merged = DatacenterPickerResults.merge(api, current, prior)

		assertEquals(15, merged.getJSONObject(0).getInt("rtt"))
		assertEquals(40, merged.getJSONObject(1).getInt("rtt"))
	}

	@Test
	fun `empty ping pass does not erase prior measurements`()
	{
		val api = JSONArray("""[{"dataCenter":"lonb","publicIp":"1.1.1.1","port":2053}]""")
		val prior = """[{"dataCenter":"lonb","rtt":15}]"""

		val merged = DatacenterPickerResults.merge(api, JSONArray(), prior)

		assertEquals(15, merged.getJSONObject(0).getInt("rtt"))
	}

	@Test
	fun `new api datacenter without a measurement remains selectable`()
	{
		val api = JSONArray("""[{"dataCenter":"lonb","publicIp":"1.1.1.1","port":2053}]""")

		val merged = DatacenterPickerResults.merge(api, JSONArray(), "")

		assertEquals("lonb", merged.getJSONObject(0).getString("dataCenter"))
		assertEquals(false, merged.getJSONObject(0).has("rtt"))
	}

	@Test
	fun `forced datacenter dummy does not replace a real prior measurement`()
	{
		val api = JSONArray("""[{"dataCenter":"lonb","publicIp":"1.1.1.1","port":2053}]""")
		val dummy = JSONArray("""[{"dataCenter":"lonb","rtt":20}]""")
		val prior = """[{"dataCenter":"lonb","rtt":15}]"""

		val merged = DatacenterPickerResults.merge(api, dummy, prior, preferPrior = true)

		assertEquals(15, merged.getJSONObject(0).getInt("rtt"))
	}
}
