package com.metallic.chiaki.friends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendProfilePlanTest {

    private val ids = listOf("a", "b", "c", "d")
    private val day = FriendProfilePlan.PROFILE_MAX_AGE_MS
    private val now = 10 * day

    @Test
    fun `with fresh saved profiles only new friends are fetched`() {
        val fetch = FriendProfilePlan.idsToFetch(ids, knownIds = setOf("a", "b", "c"), profilesFetchedAtMs = now - 1000, nowMs = now)

        assertEquals(listOf("d"), fetch)
    }

    @Test
    fun `with nothing new no profile requests are made`() {
        assertTrue(FriendProfilePlan.idsToFetch(ids, ids.toSet(), now - 1000, now).isEmpty())
    }

    @Test
    fun `profiles older than a day are all refetched`() {
        assertEquals(ids, FriendProfilePlan.idsToFetch(ids, ids.toSet(), profilesFetchedAtMs = now - day, nowMs = now))
    }

    @Test
    fun `no profile ever fetched means fetch them all`() {
        assertEquals(ids, FriendProfilePlan.idsToFetch(ids, emptySet(), profilesFetchedAtMs = 0, nowMs = now))
    }

    @Test
    fun `a clock before the last fetch does not trust the saved profiles`() {
        assertEquals(ids, FriendProfilePlan.idsToFetch(ids, ids.toSet(), profilesFetchedAtMs = now + 5000, nowMs = now))
    }

    @Test
    fun `only a fetch of everyone counts as a full refetch`() {
        assertTrue(FriendProfilePlan.isFullRefetch(ids, ids))
        assertFalse(FriendProfilePlan.isFullRefetch(listOf("d"), ids))
        assertFalse(FriendProfilePlan.isFullRefetch(emptyList(), ids))
    }
}
