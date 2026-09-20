package com.metallic.chiaki.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A marquee only scrolls a TextView that is truly single-line: with just maxLines=1 the text wraps
 * to fit and never overflows, so the title would look static even while focused.
 */
class TileTitleMarqueeLayoutTest {

    /** Every copy of a tile layout — the landscape one (layout-land) is what a handheld in landscape actually uses. */
    private fun variants(layout: String): List<File> =
        File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!
            .map { File(it, layout) }.filter { it.exists() }

    private fun titleBlock(file: File): String {
        val xml = file.readText()
        val start = xml.indexOf("android:id=\"@+id/gameNameTextView\"")
        assertTrue("${file.path} has no gameNameTextView", start >= 0)
        return xml.substring(start, xml.indexOf("/>", start))
    }

    @Test
    fun `every version of the library and Add Game tile titles is single-line so the marquee can scroll`() {
        listOf("item_cloud_game.xml", "item_add_game.xml").forEach { layout ->
            val files = variants(layout)
            assertTrue("no $layout found", files.isNotEmpty())
            files.forEach { file ->
                val block = titleBlock(file)
                assertTrue("${file.path} title must be singleLine", block.contains("android:singleLine=\"true\""))
                assertTrue("${file.path} title must not be limited by maxLines alone", !block.contains("android:maxLines"))
            }
        }
    }

    @Test
    fun `tile titles start with a static trailing ellipsis`() {
        listOf("item_cloud_game.xml", "item_add_game.xml").forEach { layout ->
            variants(layout).forEach { assertTrue(it.path, titleBlock(it).contains("android:ellipsize=\"end\"")) }
        }
    }
}
