package com.metallic.chiaki.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Settings bitrate sliders are declared in preferences.xml, which can't reference
 * [Preferences.CLOUD_BITRATE_MAX_KBPS] — make sure they can't drift away from the enforced limit.
 */
class CloudBitrateLimitTest {

    private val bitrateKeys = listOf("@string/preferences_cloud_bitrate_pscloud_key", "@string/preferences_cloud_bitrate_psnow_key")

    private fun sliders(): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/preferences.xml"))
        val nodes = doc.getElementsByTagName("SeekBarPreference")
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("app:key") in bitrateKeys }
    }

    @Test
    fun `both cloud bitrate sliders exist`() {
        assertEquals(2, sliders().size)
    }

    @Test
    fun `slider range matches the enforced limits`() {
        for (slider in sliders()) {
            val key = slider.getAttribute("app:key")
            assertEquals("$key max", Preferences.CLOUD_BITRATE_MAX_KBPS / 1000, slider.getAttribute("android:max").toInt())
            assertEquals("$key min", Preferences.CLOUD_BITRATE_MIN_KBPS / 1000, slider.getAttribute("app:min").toInt())
        }
    }

    @Test
    fun `cap is 50 Mbps and the default fits under it`() {
        assertEquals(50000, Preferences.CLOUD_BITRATE_MAX_KBPS)
        assertTrue(Preferences.CLOUD_BITRATE_DEFAULT_KBPS in Preferences.CLOUD_BITRATE_MIN_KBPS..Preferences.CLOUD_BITRATE_MAX_KBPS)
    }
}
