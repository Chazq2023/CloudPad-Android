package com.metallic.chiaki.friends

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Friends messaging was removed on purpose: sending messages is an automated write action on the
 * user's PSN account, which isn't worth the risk. These file-based guards fail if any of it is
 * quietly reintroduced.
 */
class FriendsMessagingRemovedTest {

    private val src = File("src/main")

    private fun sourceFiles() = File(src, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no code talks to the gaming lounge messaging API`() {
        val offenders = sourceFiles().filter { it.readText().contains("gamingLoungeGroups", ignoreCase = true) }
        assertTrue("Messaging endpoint referenced in: $offenders", offenders.isEmpty())
    }

    @Test
    fun `there is no chat screen, adapter or send call`() {
        val names = sourceFiles().map { it.name }
        assertFalse(names.contains("FriendChatActivity.kt"))
        assertFalse(names.contains("ChatMessageAdapter.kt"))
        val sends = sourceFiles().filter { Regex("\\bfun (sendMessage|createOrGetDmGroup|fetchConversation)\\b").containsMatchIn(it.readText()) }
        assertTrue("Messaging functions found in: $sends", sends.isEmpty())
    }

    @Test
    fun `the manifest and quick menu layout have no chat screen`() {
        assertFalse(File(src, "AndroidManifest.xml").readText().contains("FriendChatActivity"))
        assertFalse(File(src, "res/layout/stream_quick_settings_panel.xml").readText().contains("FriendChat"))
    }

    @Test
    fun `chat strings are gone from every language`() {
        val offenders = File(src, "res").listFiles { f -> f.name.startsWith("values") }.orEmpty()
            .map { File(it, "strings.xml") }.filter { it.exists() }
            .filter { Regex("name=\"(friend_chat_|quick_settings_friend_chat_)").containsMatchIn(it.readText()) }
        assertTrue("Chat strings still in: $offenders", offenders.isEmpty())
    }
}
