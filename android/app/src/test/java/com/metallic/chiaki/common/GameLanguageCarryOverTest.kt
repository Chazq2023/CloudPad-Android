package com.metallic.chiaki.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameLanguageCarryOverTest {

    @Test
    fun `a different language picked under the old Locale setting becomes the game language`() {
        // German account that had picked English (UK) to get English games.
        assertEquals("en", Preferences.gameLanguageCarriedOver("en-GB", "de-DE", ""))
    }

    @Test
    fun `picking another country in the same language carries nothing over`() {
        // German account that had picked the UK store: only the country differed.
        assertNull(Preferences.gameLanguageCarriedOver("en-GB", "en-US", ""))
        assertNull(Preferences.gameLanguageCarriedOver("de-DE", "de-AT", ""))
    }

    @Test
    fun `an existing game language is never overwritten`() {
        assertNull(Preferences.gameLanguageCarriedOver("en-GB", "de-DE", "fr"))
    }
}
