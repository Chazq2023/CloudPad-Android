package com.metallic.chiaki.common

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateNotifierTest {

    // The body format the release workflow publishes (copied from v0.1.62).
    private val body = """
        ## CloudPad Beta Release 0.1.62

        ### Updates
        - The launch disclaimer and README now also ask you not to use CloudPad to get around release-date restrictions.
        - The disclaimer's checklist is **clearer**: see the [PlayStation Store](https://store.playstation.com).
        - The disclaimer now fits on landscape screens, such as handhelds and TVs, without scrolling.

        ### APK
        Attached release asset:
        `cloudpad-android.apk`
    """.trimIndent()

    private fun releaseJson(tag: String, draft: Boolean = false, prerelease: Boolean = false) = JSONObject()
        .put("tag_name", tag)
        .put("html_url", "https://github.com/Chazq2023/CloudPad-Android/releases/tag/$tag")
        .put("body", body)
        .put("draft", draft)
        .put("prerelease", prerelease)

    @Test
    fun `takes only the Updates bullets, as plain text`() {
        assertEquals(listOf(
            "The launch disclaimer and README now also ask you not to use CloudPad to get around release-date restrictions.",
            "The disclaimer's checklist is clearer: see the PlayStation Store.",
            "The disclaimer now fits on landscape screens, such as handhelds and TVs, without scrolling."
        ), UpdateNotifier.parseUpdateNotes(body))
    }

    @Test
    fun `parses the release version without the v prefix`() {
        val release = UpdateNotifier.parseRelease(releaseJson("v0.1.62"))!!
        assertEquals("0.1.62", release.version)
        assertEquals("https://github.com/Chazq2023/CloudPad-Android/releases/tag/v0.1.62", release.url)
        assertEquals(3, release.notes.size)
    }

    @Test
    fun `draft and prerelease releases are ignored`() {
        assertNull(UpdateNotifier.parseRelease(releaseJson("v0.1.62", draft = true)))
        assertNull(UpdateNotifier.parseRelease(releaseJson("v0.1.62", prerelease = true)))
    }

    @Test
    fun `only a newer release is offered`() {
        val release = UpdateNotifier.parseRelease(releaseJson("v0.1.62"))
        assertEquals("0.1.62", UpdateNotifier.newerRelease("0.1.61", release)?.version)
        assertNull(UpdateNotifier.newerRelease("0.1.62", release))
        assertNull(UpdateNotifier.newerRelease("0.1.63", release))
        assertNull(UpdateNotifier.newerRelease("0.1.61", null))
    }
}
