package com.metallic.chiaki.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A marquee only scrolls a TextView that is truly single-line: with just maxLines=1 the text wraps
 * to fit and never overflows, so the title would look static even while focused.
 */
class TileTitleMarqueeLayoutTest {

    private fun titleBlock(layout: String): String {
        val xml = File("src/main/res/layout/$layout").readText()
        val start = xml.indexOf("android:id=\"@+id/gameNameTextView\"")
        assertTrue("$layout has no gameNameTextView", start >= 0)
        return xml.substring(start, xml.indexOf("/>", start))
    }

    @Test
    fun `library and Add Game tile titles are single-line so their marquee can scroll`() {
        listOf("item_cloud_game.xml", "item_add_game.xml").forEach { layout ->
            val block = titleBlock(layout)
            assertTrue("$layout title must be singleLine", block.contains("android:singleLine=\"true\""))
            assertTrue("$layout title must not be limited by maxLines alone", !block.contains("android:maxLines"))
        }
    }

    @Test
    fun `tile titles start with a static trailing ellipsis`() {
        listOf("item_cloud_game.xml", "item_add_game.xml").forEach { layout ->
            assertTrue("$layout", titleBlock(layout).contains("android:ellipsize=\"end\""))
        }
    }
}
