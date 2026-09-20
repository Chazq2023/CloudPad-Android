package com.metallic.chiaki.cloudplay.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PsCloudCatalogMembershipTest {

    private val service = PsCloudCatalogService()

    private fun game(productId: String, name: String, concept: Long = 1000) = JSONObject()
        .put("productId", productId).put("name", name).put("conceptId", concept)

    private fun membershipOf(vararg games: JSONObject): ListMembership {
        val members = ListMembership()
        games.forEach {
            val key = "${it.getLong("conceptId")}|${if (it.getString("productId").contains("PPSA")) "ps5" else "ps4"}|" +
                it.getString("name").lowercase().filter { c -> c.isLetterOrDigit() }
            members.editionKeys.add(key)
            Regex("(?:PPSA|CUSA)\\d+").find(it.getString("productId"))?.let { m -> members.stableKeys.add(m.value) }
        }
        return members
    }

    @Test
    fun `a catalog game matching a list entry by edition is tagged`() {
        val browse = game("EP9000-PPSA03208_00-GHOSTDIRECTORPS5", "Ghost of Tsushima", 500)
        val plusEntry = game("EP9000-PPSA05031_00-GHOSTDCPS5PSPLUS", "Ghost of Tsushima", 500)

        service.flagListMembership(listOf(browse), membershipOf(plusEntry), ListMembership())

        assertTrue(browse.optBoolean("psCatalog"))
        assertFalse(browse.optBoolean("freeToPlay"))
    }

    @Test
    fun `a catalog game matching by PPSA number is tagged even when the name differs`() {
        val browse = game("EP1-PPSA11111_00-RETAIL", "Some Game Standard Edition", 1)
        val listEntry = game("EP1-PPSA11111_00-SUBSCRIPTION", "Some Game", 2)

        service.flagListMembership(listOf(browse), ListMembership(), membershipOf(listEntry))

        assertTrue(browse.optBoolean("freeToPlay"))
        assertFalse(browse.optBoolean("psCatalog"))
    }

    @Test
    fun `an unrelated game is left untagged`() {
        val browse = game("EP2-PPSA22222_00-OTHER", "Other Game", 7)

        service.flagListMembership(
            listOf(browse), membershipOf(game("EP3-PPSA33333_00-X", "Plus Game", 8)), membershipOf(game("EP4-PPSA44444_00-Y", "Free Game", 9))
        )

        assertFalse(browse.has("psCatalog"))
        assertFalse(browse.has("freeToPlay"))
    }

    @Test
    fun `empty membership tags nothing`() {
        val browse = game("EP2-PPSA22222_00-OTHER", "Other Game", 7)

        service.flagListMembership(listOf(browse), ListMembership(), ListMembership())

        assertEquals(setOf("productId", "name", "conceptId"), browse.keys().asSequence().toSet())
    }
}
