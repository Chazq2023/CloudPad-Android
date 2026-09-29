package com.metallic.chiaki.cloudplay.api

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

	private val usBatch = JSONArray("""[
		{"dataCenter":"lgab","publicIp":"1.1.1.1","port":40101},
		{"dataCenter":"iadb","publicIp":"2.2.2.2","port":40101}
	]""")

	@Test
	fun `closer datacenter measured in an earlier session flags a distant batch`()
	{
		val prior = """[{"dataCenter":"lgab","rtt":78,"mtu_out":1454},{"dataCenter":"lonb","rtt":10,"mtu_out":1454},{"dataCenter":"parb","rtt":18,"mtu_out":1454}]"""

		val closer = DatacenterPickerResults.closerKnownDatacenter(prior, usBatch, 78, 30)

		assertEquals("lonb", closer?.getString("dataCenter"))
	}

	@Test
	fun `batch within the margin of known datacenters is accepted`()
	{
		val prior = """[{"dataCenter":"lonb","rtt":10,"mtu_out":1454}]"""

		assertNull(DatacenterPickerResults.closerKnownDatacenter(prior, usBatch, 35, 30))
	}

	@Test
	fun `synthetic and failed prior results are not treated as measurements`()
	{
		val prior = """[{"dataCenter":"lonb","rtt":20,"mtu_out":1254},{"dataCenter":"parb","rtt":999,"mtu_out":0}]"""

		assertNull(DatacenterPickerResults.closerKnownDatacenter(prior, usBatch, 78, 30))
	}

	@Test
	fun `datacenters in the current batch are not compared against themselves`()
	{
		val prior = """[{"dataCenter":"lgab","rtt":10,"mtu_out":1454}]"""

		assertNull(DatacenterPickerResults.closerKnownDatacenter(prior, usBatch, 78, 30))
	}

	@Test
	fun `only real measurements outside the batch count as another region`()
	{
		assertFalse(DatacenterPickerResults.hasMeasurementOutside("""[{"dataCenter":"lgab","rtt":78,"mtu_out":1454}]""", usBatch))
		assertFalse(DatacenterPickerResults.hasMeasurementOutside("""[{"dataCenter":"lonb","rtt":20,"mtu_out":1254}]""", usBatch))
		assertTrue(DatacenterPickerResults.hasMeasurementOutside("""[{"dataCenter":"lonb","rtt":10,"mtu_out":1454}]""", usBatch))
	}
}
