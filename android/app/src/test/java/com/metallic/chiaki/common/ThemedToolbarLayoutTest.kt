package com.metallic.chiaki.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A toolbar themed with a fixed @style/AppTheme.Toolbar inherits from the default (pink) theme, so
 * everything inside it — notably the focus highlight on its buttons — stays pink whatever theme
 * colour the user picked. Toolbars must use ?attr/pyluxToolbarTheme, which each theme colour points
 * at its own matching toolbar theme.
 */
class ThemedToolbarLayoutTest {

    @Test
    fun `no layout hardcodes the pink toolbar theme`() {
        val offenders = File("src/main/res")
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("layout") }
            .filter { it.readText().contains("@style/AppTheme.Toolbar\"") }
            .map { it.name }
            .toList()

        assertTrue("Use ?attr/pyluxToolbarTheme instead in: $offenders", offenders.isEmpty())
    }
}
