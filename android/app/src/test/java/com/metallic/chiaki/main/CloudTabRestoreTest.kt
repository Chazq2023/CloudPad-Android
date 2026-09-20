package com.metallic.chiaki.main

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudTabRestoreTest {

    @Test
    fun `signed out leaves everything to the login state`() {
        assertEquals(CloudTabRestore.NONE, CloudTabRestore.decide(hasToken = false, hasGames = true, isLoading = false))
        assertEquals(CloudTabRestore.NONE, CloudTabRestore.decide(hasToken = false, hasGames = false, isLoading = false))
    }

    @Test
    fun `games still held by the view model just reapply the selected tab`() {
        assertEquals(CloudTabRestore.REAPPLY, CloudTabRestore.decide(hasToken = true, hasGames = true, isLoading = false))
    }

    @Test
    fun `a fetch in flight reapplies the tab instead of starting another`() {
        assertEquals(CloudTabRestore.REAPPLY, CloudTabRestore.decide(hasToken = true, hasGames = false, isLoading = true))
    }

    @Test
    fun `nothing loaded and nothing loading loads like a fresh start`() {
        assertEquals(CloudTabRestore.LOAD, CloudTabRestore.decide(hasToken = true, hasGames = false, isLoading = false))
    }
}
