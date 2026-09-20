package com.metallic.chiaki.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards values-in/strings.xml against the mistakes that only show up at runtime: a placeholder
 * that doesn't match the English string (crashes or garbles String.format), a key that no longer
 * exists in the default file, or the language not being offered. Coverage is deliberately not
 * enforced — an English string added without a translation just falls back to English.
 */
class IndonesianTranslationTest {

    private val resDir = File("src/main/res")

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private val english by lazy { strings(File(resDir, "values/strings.xml")) }
    private val indonesian by lazy { strings(File(resDir, "values-in/strings.xml")) }

    private fun specifiers(text: String) =
        Regex("%(?:\\d+\\$)?[sdf]|%%|\\\\n|\\\\u[0-9a-fA-F]{4}").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `translation file exists and has content`() {
        assertTrue(indonesian.size > 500)
    }

    @Test
    fun `every translated key exists in the default strings`() {
        val unknown = indonesian.keys.filter { it !in english }
        assertTrue("Keys not in values/strings.xml: $unknown", unknown.isEmpty())
    }

    @Test
    fun `placeholders and escapes match the English string`() {
        val mismatched = indonesian.filter { (key, value) ->
            english[key]?.let { specifiers(it) != specifiers(value) } ?: false
        }.keys
        assertTrue("Format specifiers differ from English for: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun `no quote or apostrophe is left unescaped`() {
        val bad = indonesian.filter { (_, v) -> Regex("(?<!\\\\)['\"]").containsMatchIn(v) }.keys
        assertTrue("Unescaped quotes in: $bad", bad.isEmpty())
    }

    @Test
    fun `preference keys and URLs are not translated`() {
        assertTrue(indonesian.keys.none { it.endsWith("_key") })
        assertTrue("app_name" !in indonesian)
    }

    @Test
    fun `Indonesian is offered as an app language`() {
        val config = File(resDir, "xml/locales_config.xml").readText()
        assertTrue(config.contains("android:name=\"id-ID\""))
        val settings = File("src/main/java/com/metallic/chiaki/settings/SettingsFragment.kt").readText()
        assertTrue(settings.contains("\"id-ID\" to \"Indonesia"))
    }

    private fun pluralItems(file: File, name: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val plurals = doc.getElementsByTagName("plurals")
        val node = (0 until plurals.length).map { plurals.item(it) as Element }.first { it.getAttribute("name") == name }
        val items = node.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it).textContent }
    }

    @Test
    fun `the games-displayed plural keeps both counts in every form`() {
        val forms = pluralItems(File(resDir, "values/strings.xml"), "add_game_count") +
            pluralItems(File(resDir, "values-in/strings.xml"), "add_game_count")

        assertEquals(3, forms.size)
        forms.forEach { assertEquals(listOf("%1\$s", "%2\$s"), specifiers(it)) }
    }

    @Test
    fun `key UI strings are translated`() {
        assertEquals("Pengaturan", indonesian["title_settings"])
        assertEquals("Tambah Game ke Pustaka PS5", indonesian["add_game_title"])
    }
}
