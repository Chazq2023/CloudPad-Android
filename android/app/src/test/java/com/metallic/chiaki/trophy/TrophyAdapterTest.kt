package com.metallic.chiaki.trophy

import android.content.Context
import com.metallic.chiaki.trophy.model.Trophy
import com.metallic.chiaki.trophy.model.TrophyCounts
import com.metallic.chiaki.trophy.model.TrophyGroup
import com.metallic.chiaki.trophy.model.TrophyTitleDetail
import com.metallic.chiaki.trophy.model.TrophyTitleSummary
import com.metallic.chiaki.trophy.model.TrophyType
import com.pylux.stream.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class TrophyAdapterTest {

    private val context: Context = mockk(relaxed = true) {
        every { getString(R.string.trophy_group_fallback_header) } returns "Trophies"
    }

    private fun group(id: String, name: String) = TrophyGroup(
        groupId = id,
        groupName = name,
        groupIconUrl = "",
        definedTrophies = TrophyCounts(),
        earnedTrophies = TrophyCounts()
    )

    private fun trophy(
        id: Int,
        groupId: String,
        type: TrophyType = TrophyType.BRONZE,
        earned: Boolean = false,
        earnedDateTimeMs: Long? = null
    ) = Trophy(
        trophyId = id,
        groupId = groupId,
        type = type,
        name = "Trophy $id",
        detail = "",
        iconUrl = "",
        hidden = false,
        earned = earned,
        earnedDateTimeMs = earnedDateTimeMs
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

        val items = buildTrophyListItems(context, detail(groups, trophies))

        val headers = items.filterIsInstance<TrophyListItem.GroupHeader>()
        assertEquals(listOf("Trophies"), headers.map { it.name })
        assertEquals(3, items.filterIsInstance<TrophyListItem.TrophyRow>().size)
    }

    @Test
    fun `named groups keep their own headers alongside a single fallback header`() {
        val groups = listOf(group("default", ""), group("001", "Expansion Pack"), group("002", ""))
        val trophies = listOf(trophy(1, "default"), trophy(2, "001"), trophy(3, "002"))

        val items = buildTrophyListItems(context, detail(groups, trophies))

        val headers = items.filterIsInstance<TrophyListItem.GroupHeader>()
        assertEquals(listOf("Trophies", "Expansion Pack"), headers.map { it.name })
    }

    @Test
    fun `single group with a name is used as-is`() {
        val groups = listOf(group("default", "My Game"))
        val trophies = listOf(trophy(1, "default"))

        val items = buildTrophyListItems(context, detail(groups, trophies))

        val headers = items.filterIsInstance<TrophyListItem.GroupHeader>()
        assertEquals(listOf("My Game"), headers.map { it.name })
    }

    @Test
    fun `no groups produces trophy rows with no header at all`() {
        val trophies = listOf(trophy(1, "default"))

        val items = buildTrophyListItems(context, detail(emptyList(), trophies))

        assertEquals(0, items.filterIsInstance<TrophyListItem.GroupHeader>().size)
        assertEquals(1, items.filterIsInstance<TrophyListItem.TrophyRow>().size)
    }

    @Test
    fun `earned date sort flattens headers, puts unlocked trophies first by most recent`() {
        val groups = listOf(group("default", "My Game"))
        val trophies = listOf(
            trophy(1, "default", earned = true, earnedDateTimeMs = 1000L),
            trophy(2, "default", earned = false),
            trophy(3, "default", earned = true, earnedDateTimeMs = 3000L),
            trophy(4, "default", earned = false)
        )

        val items = buildTrophyListItems(context, detail(groups, trophies), sortMode = TrophySortMode.EARNED_DATE)

        assertEquals(0, items.filterIsInstance<TrophyListItem.GroupHeader>().size)
        val order = items.filterIsInstance<TrophyListItem.TrophyRow>().map { it.trophy.trophyId }
        assertEquals(listOf(3, 1, 2, 4), order)
    }

    @Test
    fun `default sort with earned date mode selected but no groups still flattens`() {
        val trophies = listOf(
            trophy(1, "default", earned = true, earnedDateTimeMs = 500L),
            trophy(2, "default", earned = true, earnedDateTimeMs = 1500L)
        )

        val items = buildTrophyListItems(context, detail(emptyList(), trophies), sortMode = TrophySortMode.EARNED_DATE)

        val order = items.filterIsInstance<TrophyListItem.TrophyRow>().map { it.trophy.trophyId }
        assertEquals(listOf(2, 1), order)
    }

    @Test
    fun `filtering by rarity keeps only that rarity's trophies`() {
        val groups = listOf(group("default", "My Game"))
        val trophies = listOf(
            trophy(1, "default", type = TrophyType.BRONZE),
            trophy(2, "default", type = TrophyType.GOLD),
            trophy(3, "default", type = TrophyType.GOLD),
            trophy(4, "default", type = TrophyType.PLATINUM)
        )

        val items = buildTrophyListItems(context, detail(groups, trophies), filterMode = TrophyFilterMode.GOLD)

        val order = items.filterIsInstance<TrophyListItem.TrophyRow>().map { it.trophy.trophyId }
        assertEquals(listOf(2, 3), order)
    }

    @Test
    fun `filtering to a rarity with no matches in a group skips that group's header`() {
        val groups = listOf(group("default", ""), group("001", "Expansion Pack"))
        val trophies = listOf(
            trophy(1, "default", type = TrophyType.BRONZE),
            trophy(2, "001", type = TrophyType.SILVER)
        )

        val items = buildTrophyListItems(context, detail(groups, trophies), filterMode = TrophyFilterMode.SILVER)

        val headers = items.filterIsInstance<TrophyListItem.GroupHeader>()
        assertEquals(listOf("Expansion Pack"), headers.map { it.name })
        assertEquals(listOf(2), items.filterIsInstance<TrophyListItem.TrophyRow>().map { it.trophy.trophyId })
    }

    @Test
    fun `default filter mode is unaffected by empty-group skipping`() {
        val groups = listOf(group("default", ""), group("001", ""), group("002", ""))
        val trophies = listOf(trophy(1, "default"), trophy(2, "001"), trophy(3, "002"))

        val items = buildTrophyListItems(context, detail(groups, trophies), filterMode = TrophyFilterMode.DEFAULT)

        val headers = items.filterIsInstance<TrophyListItem.GroupHeader>()
        assertEquals(listOf("Trophies"), headers.map { it.name })
        assertEquals(3, items.filterIsInstance<TrophyListItem.TrophyRow>().size)
    }
}
