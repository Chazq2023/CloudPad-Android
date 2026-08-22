package com.metallic.chiaki.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers FriendsService's cache serialize/deserialize round trip — the only part of the network
 * layer that's pure enough to unit test without hitting Sony's live API (mirrors
 * TrophyServiceParsingTest for the same reasoning).
 */
class FriendsServiceParsingTest {

    private val sample = listOf(
        Friend(
            accountId = "7165161910242240557",
            onlineId = "Minionbanana27",
            avatarUrl = "https://static-resource.np.community.playstation.net/avatar_m/SCEI/I0005_m.png",
            isOnline = true,
            isBusy = true,
            currentGame = "Marvel Rivals",
            lastOnlineDateMs = null
        ),
        Friend(
            accountId = "874106239698932950",
            onlineId = "CKGray",
            avatarUrl = "",
            isOnline = false,
            isBusy = false,
            currentGame = "",
            lastOnlineDateMs = 1731000000000L
        )
    )

    @Test
    fun `serialize then deserialize round trips every field`() {
        val json = FriendsService.serializeFriends(sample)
        val parsed = FriendsService.deserializeFriends(json)

        assertEquals(sample.size, parsed.size)
        assertEquals(sample, parsed)
    }

    @Test
    fun `deserialize returns empty list for malformed json`() {
        val parsed = FriendsService.deserializeFriends("not valid json")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `deserialize returns empty list for empty array`() {
        val parsed = FriendsService.deserializeFriends("[]")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `negative lastOnlineDateMs sentinel round trips to null`() {
        val json = FriendsService.serializeFriends(listOf(sample[0]))
        val parsed = FriendsService.deserializeFriends(json)

        assertEquals(null, parsed.single().lastOnlineDateMs)
    }
}
