package com.metallic.chiaki.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The trophy popup switch must exist in both places the user can change it, and default to off. */
class TrophyPopupSettingTest {

    private val res = File("src/main/res")

    @Test
    fun `the settings page has a Trophy Popups switch that defaults to off`() {
        val xml = File(res, "xml/preferences.xml").readText()
        val block = xml.substringAfter("preferences_trophy_popups_enabled_key\"").substringBefore("/>")

        assertTrue("switch missing from preferences.xml", xml.contains("@string/preferences_trophy_popups_enabled_key"))
        assertTrue("must default to false", block.contains("app:defaultValue=\"false\""))
    }

    @Test
    fun `the quick menu settings tab has the switch`() {
        val layout = File(res, "layout/stream_quick_settings_panel.xml").readText()

        assertTrue(layout.contains("@+id/quickSettingsTrophyPopupsRow"))
    }

    @Test
    fun `the preference itself defaults to off`() {
        val source = File("src/main/java/com/metallic/chiaki/common/Preferences.kt").readText()
        val getter = source.substringAfter("var trophyPopupsEnabled").substringBefore("set(value)")

        assertTrue(getter.contains("getBoolean(trophyPopupsEnabledKey, false)"))
    }

    @Test
    fun `the setting is translated to Indonesian`() {
        val id = File(res, "values-in/strings.xml").readText()

        assertTrue(id.contains("preferences_trophy_popups_enabled_title"))
        assertTrue(id.contains("preferences_trophy_popups_enabled_summary"))
    }
}
