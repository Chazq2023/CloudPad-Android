package com.metallic.chiaki.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards every translated strings.xml against runtime-only mistakes (mismatched placeholders,
 * unescaped quotes, keys the default file no longer has) and makes sure the Add Game page and
 * Video Pacing strings are translated in every language, not just Indonesian.
 */
class AllTranslationsTest {

    private val resDir = File("src/main/res")

    private val locales = listOf("de", "es", "fi", "fr", "it", "ja", "ko", "nl", "b+pt+BR", "in")

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun strings(file: File): Map<String, String> {
        val nodes = parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private fun pluralItems(file: File, name: String): Map<String, String> {
        val plurals = parse(file).getElementsByTagName("plurals")
        val node = (0 until plurals.length).map { plurals.item(it) as Element }.firstOrNull { it.getAttribute("name") == name }
            ?: return emptyMap()
        val items = node.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("quantity") to it.textContent }
    }

    private fun localeFile(locale: String) = File(resDir, "values-$locale/strings.xml")

    private val english by lazy { strings(File(resDir, "values/strings.xml")) }

    /** Format specifiers and escapes, with a \\uXXXX escape treated the same as the literal character. */
    private fun specifiers(text: String) =
        Regex("%(?:\\d+\\$)?[sdf]|%%|\\\\n").findAll(
            Regex("\\\\u([0-9a-fA-F]{4})").replace(text) { it.groupValues[1].toInt(16).toChar().toString() }
        ).map { it.value }.sorted().toList()

    /** Strings added with the Add Game page, Video Pacing setting and trophy refresh button. */
    private val recentKeys by lazy {
        english.keys.filter { (it.startsWith("add_game_") || it.startsWith("preferences_video_pacing_")) && !it.endsWith("_key") } +
            listOf("cloud_add_game_button_content_description", "trophy_refresh_content_description")
    }

    @Test
    fun `every locale has valid keys, placeholders and escaping`() {
        for (locale in locales) {
            val translated = strings(localeFile(locale))
            val unknown = translated.keys.filter { it !in english }
            assertTrue("$locale: keys not in default: $unknown", unknown.isEmpty())

            val mismatched = translated.filter { (k, v) -> english[k]?.let { specifiers(it) != specifiers(v) } ?: false }.keys
            assertTrue("$locale: format specifiers differ for $mismatched", mismatched.isEmpty())

            val unescaped = translated.filter { (_, v) -> Regex("(?<!\\\\)['\"]").containsMatchIn(v) }.keys
            assertTrue("$locale: unescaped quotes in $unescaped", unescaped.isEmpty())
        }
    }

    @Test
    fun `Add Game and Video Pacing strings are translated in every locale`() {
        assertTrue(recentKeys.size >= 26)
        for (locale in locales) {
            val translated = strings(localeFile(locale))
            val missing = recentKeys.filter { it !in translated }
            assertTrue("$locale is missing: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `the games-displayed plural exists in every locale and keeps both counts`() {
        val onlyOther = setOf("ja", "ko", "in")
        for (locale in locales) {
            val forms = pluralItems(localeFile(locale), "add_game_count")
            val expected = if (locale in onlyOther) setOf("other") else setOf("one", "other")
            assertEquals("$locale plural quantities", expected, forms.keys)
            forms.values.forEach { assertEquals("$locale plural placeholders", listOf("%1\$s", "%2\$s"), specifiers(it)) }
        }
    }

    @Test
    fun `translated Add Game titles differ from English`() {
        val title = english.getValue("add_game_title")
        for (locale in locales)
            assertTrue("$locale add_game_title is still English", strings(localeFile(locale))["add_game_title"] != title)
    }
}
