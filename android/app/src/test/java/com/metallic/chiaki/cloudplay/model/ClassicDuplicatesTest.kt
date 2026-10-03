package com.metallic.chiaki.cloudplay.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicDuplicatesTest {

    private fun game(id: String, name: String, platform: String = "ps4") =
        CloudGame(id, name, "", platform = platform, serviceType = "psnow")

    @Test
    fun `keeps the newer re-release of a same-named classic`() {
        val games = listOf(
            game("UP9000-CUSA48754_00-SCUS973550000000", "Forbidden Siren"),
            game("EP9000-CUSA02274_00-SCES519200000001", "Forbidden Siren"),
            game("EP9000-CUSA01981_00-SCES512240000001", "War of the Monsters"),
            game("UP9000-CUSA49383_00-SCUS971970000000", "War of the Monsters")
        )

        assertEquals(
            listOf("UP9000-CUSA48754_00-SCUS973550000000", "UP9000-CUSA49383_00-SCUS971970000000"),
            games.withoutSupersededClassics().map { it.productId }
        )
    }

    @Test
    fun `a remake sharing a classic's name is kept`() {
        val games = listOf(
            game("EP9000-CUSA12982_00-MEDIEVILHD000001", "MediEvil"),
            game("UP9000-CUSA42759_00-SCUS942270000000", "MediEvil")
        )

        assertEquals(games, games.withoutSupersededClassics())
    }

    @Test
    fun `names match ignoring trademark symbols and case`() {
        val games = listOf(
            game("UP9000-CUSA47431_00-SCUS971980000000", "Sly Raccoon™"),
            game("EP9000-CUSA02200_00-SCES511150000001", "SLY RACCOON")
        )

        assertEquals(listOf("UP9000-CUSA47431_00-SCUS971980000000"), games.withoutSupersededClassics().map { it.productId })
    }

    @Test
    fun `same name on different platforms is not a duplicate`() {
        val games = listOf(
            game("UP9000-CUSA48754_00-SCUS973550000000", "Forbidden Siren", platform = "ps4"),
            game("EP9000-NPEB00001_00-SCES519200000001", "Forbidden Siren", platform = "ps3")
        )

        assertEquals(games, games.withoutSupersededClassics())
    }

    @Test
    fun `unique games and non-classics pass through in order`() {
        val games = listOf(
            game("EP2911-CUSA12555_00-DL2SHPSPLUS00000", "Dying Light 2 Stay Human"),
            game("UP9000-CUSA43359_00-SCUS943040000000", "Twisted Metal"),
            game("EP9000-CUSA11995_00-MARVELSSPIDERMAN", "Marvel's Spider-Man")
        )

        assertEquals(games, games.withoutSupersededClassics())
    }

    @Test
    fun `classic detection reads the disc code after the second dash`() {
        assertTrue(isEmulatedClassic("UP9000-CUSA48754_00-SCUS973550000000"))
        assertTrue(isEmulatedClassic("UP9000-CUSA41018_00-UCUS987000000000"))
        assertFalse(isEmulatedClassic("EP9000-CUSA12982_00-MEDIEVILHD000001"))
        assertFalse(isEmulatedClassic("UP9000-CUSA52896_00-0000000000000000"))
        assertFalse(isEmulatedClassic("not-a-product"))
    }
}
