package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.TrophyCounts
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrophyMatcherTest {

    private fun title(
        id: String,
        name: String,
        platform: String = "PS4"
    ) = TrophyTitleSummary(
        npCommunicationId = id,
        npServiceName = if (platform.contains("PS5")) "trophy2" else "trophy",
        trophyTitleName = name,
        trophyTitleIconUrl = "",
        trophyTitlePlatform = platform,
        hasTrophyGroups = false,
        definedTrophies = TrophyCounts(),
        earnedTrophies = TrophyCounts(),
        progressPercent = 0
    )

    // --- normalize() ---

    @Test
    fun `normalize lowercases and strips trademark symbols`() {
        assertEquals("god of war", TrophyMatcher.normalize("God of War™"))
    }

    @Test
    fun `normalize strips platform parenthetical suffix`() {
        assertEquals("horizon zero dawn", TrophyMatcher.normalize("Horizon Zero Dawn (PS4)"))
    }

    @Test
    fun `normalize strips edition suffix`() {
        assertEquals("last of us part ii", TrophyMatcher.normalize("The Last of Us Part II - Digital Edition"))
    }

    @Test
    fun `normalize drops standalone the tokens anywhere in the title`() {
        assertEquals(
            "tainted grail fall of avalon",
            TrophyMatcher.normalize("Tainted Grail: The Fall of Avalon")
        )
    }

    @Test
    fun `normalize collapses punctuation and extra whitespace`() {
        assertEquals("uncharted 4 a thief s end", TrophyMatcher.normalize("Uncharted 4:  A Thief's End"))
    }

    // --- findBestMatch() ---

    @Test
    fun `finds exact match by normalized name`() {
        val titles = listOf(title("NPWR001_00", "God of War"), title("NPWR002_00", "Spider-Man"))
        val match = TrophyMatcher.findBestMatch("God of War", "ps4", titles)
        assertEquals("NPWR001_00", match?.npCommunicationId)
    }

    @Test
    fun `match is case and trademark insensitive`() {
        val titles = listOf(title("NPWR001_00", "god of war"))
        val match = TrophyMatcher.findBestMatch("God Of War™", "ps4", titles)
        assertEquals("NPWR001_00", match?.npCommunicationId)
    }

    @Test
    fun `falls back to substring containment when no exact match`() {
        val titles = listOf(title("NPWR001_00", "Marvel's Spider-Man Remastered", platform = "PS5"))
        val match = TrophyMatcher.findBestMatch("Spider-Man", "ps5", titles)
        assertEquals("NPWR001_00", match?.npCommunicationId)
    }

    @Test
    fun `prefers title matching the requested platform when multiple candidates match`() {
        val titles = listOf(
            title("NPWR-PS4", "Horizon Zero Dawn", platform = "PS4"),
            title("NPWR-PS5", "Horizon Zero Dawn", platform = "PS5")
        )
        val match = TrophyMatcher.findBestMatch("Horizon Zero Dawn", "ps5", titles)
        assertEquals("NPWR-PS5", match?.npCommunicationId)
    }

    @Test
    fun `falls back to first candidate when the requested platform is unrecognised`() {
        val titles = listOf(title("NPWR-PS4", "Some Game", platform = "PS4"))
        val match = TrophyMatcher.findBestMatch("Some Game", "xbox", titles)
        assertEquals("NPWR-PS4", match?.npCommunicationId)
    }

    @Test
    fun `returns null instead of a different platform's trophy set when no candidate matches the requested platform`() {
        // Regression test: a game owned only on PS4 (e.g. GTA V) must not silently return the
        // PS4 trophy set when the PS5 Library entry asks for PS5 trophies — the two platforms'
        // progress must never be conflated.
        val titles = listOf(title("NPWR-PS4", "Grand Theft Auto V", platform = "PS4"))
        val match = TrophyMatcher.findBestMatch("Grand Theft Auto V", "ps5", titles)
        assertNull(match)
    }

    @Test
    fun `PS4 and PS5 entries for the same game resolve to their own distinct trophy sets`() {
        val titles = listOf(
            title("NPWR-PS4", "Grand Theft Auto V", platform = "PS4"),
            title("NPWR-PS5", "Grand Theft Auto V", platform = "PS5")
        )
        assertEquals(
            "NPWR-PS4",
            TrophyMatcher.findBestMatch("Grand Theft Auto V", "ps4", titles)?.npCommunicationId
        )
        assertEquals(
            "NPWR-PS5",
            TrophyMatcher.findBestMatch("Grand Theft Auto V", "ps5", titles)?.npCommunicationId
        )
    }

    @Test
    fun `matches a catalogue title using Roman numerals against a game name using Arabic numerals`() {
        val titles = listOf(title("NPWR001_00", "Alan Wake II", platform = "PS5"))
        val match = TrophyMatcher.findBestMatch("Alan Wake 2", "ps5", titles)
        assertEquals("NPWR001_00", match?.npCommunicationId)
    }

    @Test
    fun `matches a differently numbered catalogue title using Roman numerals against a game name using Arabic numerals`() {
        val titles = listOf(title("NPWR001_00", "Mafia III", platform = "PS4"))
        val match = TrophyMatcher.findBestMatch("Mafia 3", "ps4", titles)
        assertEquals("NPWR001_00", match?.npCommunicationId)
    }

    @Test
    fun `roman numeral fallback does not match a differently numbered sequel`() {
        val titles = listOf(title("NPWR001_00", "Uncharted 3", platform = "PS4"))
        val match = TrophyMatcher.findBestMatch("Uncharted II", "ps4", titles)
        assertNull(match)
    }

    @Test
    fun `matches a catalogue title missing a mid-title the against Sony's trophy title`() {
        val titles = listOf(title("NPWR001_00", "Tainted Grail: The Fall of Avalon", platform = "PS5"))
        val match = TrophyMatcher.findBestMatch("Tainted Grail: Fall of Avalon", "ps5", titles)
        assertEquals("NPWR001_00", match?.npCommunicationId)
    }

    @Test
    fun `prefers the more specific franchise entry over a short generic prefix match`() {
        // Regression test: PS3 "Ratchet & Clank" games all share the "Ratchet & Clank" prefix,
        // so a generic entry like the base "Ratchet & Clank" trophy title is a substring of
        // "Ratchet & Clank: Into the Nexus" and must not win over the actual matching title
        // when both exist in the account's trophy list.
        val titles = listOf(
            title("NPWR-BASE", "Ratchet & Clank", platform = "PS3"),
            title("NPWR-NEXUS", "Ratchet & Clank: Into the Nexus", platform = "PS3")
        )
        val match = TrophyMatcher.findBestMatch("Ratchet & Clank: Into the Nexus", "ps3", titles)
        assertEquals("NPWR-NEXUS", match?.npCommunicationId)
    }

    @Test
    fun `matches when the catalogue title drops a mid-title word Sony's trophy title includes`() {
        // Regression test (actual reported bug): CloudPad's PS3 catalogue lists this game as
        // "Ratchet & Clank: Nexus", but Sony's own trophy title is "Ratchet & Clank: Into the
        // Nexus" — "Into the" is inserted in the *middle*, not appended/truncated at either
        // end, so a plain substring check can't bridge it and previously fell back to the
        // unrelated base "Ratchet & Clank" trophy title, which also exists on the account.
        val titles = listOf(
            title("NPWR02335_00", "Ratchet & Clank", platform = "PS3"),
            title("NPWR04695_00", "Ratchet & Clank: Into the Nexus™", platform = "PS3,PSVITA"),
            title("NPWR07942_00", "Ratchet & Clank™", platform = "PS4")
        )
        val match = TrophyMatcher.findBestMatch("Ratchet & Clank™: Nexus (PS3)", "ps3", titles)
        assertEquals("NPWR04695_00", match?.npCommunicationId)
    }

    @Test
    fun `does not fall back to a short unrelated franchise entry when the catalogue title has its own distinct subtitle`() {
        // Regression test (actual reported bug): PS3 "Ratchet & Clank: Tools of Destruction",
        // "Quest for Booty" and "QForce" have no trophy title of their own on this account yet,
        // but the base "Ratchet & Clank" entry's tokens are a prefix of each catalogue title's
        // tokens, so the old bidirectional subsequence check wrongly matched them to the base
        // game's (unrelated) trophy list instead of correctly reporting no match.
        val titles = listOf(
            title("NPWR02335_00", "Ratchet & Clank", platform = "PS3"),
            title("NPWR04695_00", "Ratchet & Clank: Into the Nexus™", platform = "PS3,PSVITA")
        )

        assertNull(TrophyMatcher.findBestMatch("Ratchet & Clank™: Tools of Destruction", "ps3", titles))
        assertNull(TrophyMatcher.findBestMatch("Ratchet & Clank™: Quest for Booty", "ps3", titles))
        assertNull(TrophyMatcher.findBestMatch("Ratchet & Clank™: QForce", "ps3", titles))
    }

    @Test
    fun `matches a franchise title released under a different regional subtitle`() {
        // Regression test: the PS3 "Ratchet" spin-off shipped as "Gladiator" in EU catalogues
        // but Sony's own trophy title (confirmed from a real account) uses the NA name
        // "Deadlocked" — the two names share no words at all, so no amount of token/substring
        // matching can bridge them without an explicit alias.
        val titles = listOf(
            title("NPWR02335_00", "Ratchet & Clank", platform = "PS3"),
            title("NPWR02348_00", "Ratchet: Deadlocked™", platform = "PS3")
        )
        val match = TrophyMatcher.findBestMatch("Ratchet™: Gladiator", "ps3", titles)
        assertEquals("NPWR02348_00", match?.npCommunicationId)
    }

    @Test
    fun `returns null when nothing matches`() {
        val titles = listOf(title("NPWR001_00", "Completely Different Game"))
        val match = TrophyMatcher.findBestMatch("God of War", "ps4", titles)
        assertNull(match)
    }

    @Test
    fun `returns null for an empty titles list`() {
        assertNull(TrophyMatcher.findBestMatch("God of War", "ps4", emptyList()))
    }

    @Test
    fun `returns null when the game name normalizes to empty`() {
        val titles = listOf(title("NPWR001_00", "God of War"))
        assertNull(TrophyMatcher.findBestMatch("™®©", "ps4", titles))
    }
}
