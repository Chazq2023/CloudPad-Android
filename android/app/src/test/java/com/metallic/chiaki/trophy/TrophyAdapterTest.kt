package com.metallic.chiaki.trophy

import com.metallic.chiaki.trophy.model.Trophy
import com.metallic.chiaki.trophy.model.TrophyCounts
import com.metallic.chiaki.trophy.model.TrophyGroup
import com.metallic.chiaki.trophy.model.TrophyTitleDetail
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import com.metallic.chiaki.trophy.model.TrophyType
import org.junit.Assert.assertEquals
import org.junit.Test

class TrophyAdapterTest {

    private fun group(id: String, name: String) = TrophyGroup(
        groupId = id,
        groupName = name,
        groupIconUrl = "",
        definedTrophies = TrophyCounts(),
        earnedTrophies = TrophyCounts()
    )

    private fun trophy(id: Int, groupId: String) = Trophy(
        trophyId = id,
        groupId = groupId,
        type = TrophyType.BRONZE,
        name = "Trophy $id",
        detail = "",
        iconUrl = "",
        hidden = false,
        earned = false,
        earnedDateTimeMs = null
    )

    private fun detail(groups: List<TrophyGroup>, trophies: List<Trophy>) = TrophyTitleDetail(
        summary = TrophyTitleSummary(
            npCommunicationId = "",
            npServiceName = "",
            trophyTitleName = "",
            trophyTitleIconUrl = "",
            trophyTitlePlatform = "",
            hasTrophyGroups = groups.size > 1,
            definedTrophies = TrophyCounts(),
            earnedTrophies = TrophyCounts(),
            progressPercent = 0
        ),
        groups = groups,
        trophies = trophies
    )

    @Test
    fun `multiple groups with empty names collapse into a single Trophies header`() {
        val groups = listOf(group("default", ""), group("001", ""), group("002", ""))
        val trophies = listOf(trophy(1, "default"), trophy(2, "001"), trophy(3, "002"))

        val items = buildTrophyListItems(detail(groups, trophies))

        val headers = items.filterIsInstance<TrophyListItem.GroupHeader>()
        assertEquals(listOf("Trophies"), headers.map { it.name })
        assertEquals(3, items.filterIsInstance<TrophyListItem.TrophyRow>().size)
    }

    @Test
    fun `named groups keep their own headers alongside a single fallback header`() {
        val groups = listOf(group("default", ""), group("001", "Expansion Pack"), group("002", ""))
        val trophies = listOf(trophy(1, "default"), trophy(2, "001"), trophy(3, "002"))

        val items = buildTrophyListItems(detail(groups, trophies))

        val headers = items.filterIsInstance<TrophyListItem.GroupHeader>()
        assertEquals(listOf("Trophies", "Expansion Pack"), headers.map { it.name })
    }

    @Test
    fun `single group with a name is used as-is`() {
        val groups = listOf(group("default", "My Game"))
        val trophies = listOf(trophy(1, "default"))

        val items = buildTrophyListItems(detail(groups, trophies))

        val headers = items.filterIsInstance<TrophyListItem.GroupHeader>()
        assertEquals(listOf("My Game"), headers.map { it.name })
    }

    @Test
    fun `no groups produces trophy rows with no header at all`() {
        val trophies = listOf(trophy(1, "default"))

        val items = buildTrophyListItems(detail(emptyList(), trophies))

        assertEquals(0, items.filterIsInstance<TrophyListItem.GroupHeader>().size)
        assertEquals(1, items.filterIsInstance<TrophyListItem.TrophyRow>().size)
    }
}
