package com.metallic.chiaki.cloudplay.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the favorite-game persistence logic that lives in Preferences
 * (getFavoriteGameSnapshots/writeFavoriteGameSnapshots) and the merge logic in
 * CloudPlayFragment (mergeFavorites). Mirrored here as pure functions so they
 * can be verified without a Context/SharedPreferences or a Fragment (this repo
 * has no Robolectric set up — see CloudGameFilteringTest for the same pattern).
 *
 * Regression coverage for: favorited games disappearing from the Favorites list
 * when the live catalog hasn't loaded/matched a productId yet (e.g. right after
 * an app update, cold/invalidated catalog cache). The fix stores a metadata
 * snapshot of each favorited game so it can still be displayed from persisted
 * data alone.
 */
class CloudGameFavoriteSnapshotTest {

    // Mirrors Preferences.writeFavoriteGameSnapshots
    private fun serializeSnapshots(snapshots: Map<String, CloudGame>): String {
        val jsonArray = JSONArray()
        for (game in snapshots.values) {
            val obj = JSONObject()
            obj.put("productId", game.productId)
            obj.put("name", game.name)
            obj.put("imageUrl", game.imageUrl)
            obj.put("landscapeImageUrl", game.landscapeImageUrl)
            obj.put("thumbnailUrl", game.thumbnailUrl)
            obj.put("platform", game.platform)
            obj.put("serviceType", game.serviceType)
            obj.put("conceptUrl", game.conceptUrl)
            obj.put("conceptId", game.conceptId)
            obj.put("isOwned", game.isOwned)
            obj.put("storeProductId", game.storeProductId)
            obj.put("plusCatalog", game.plusCatalog)
            obj.put("featureType", game.featureType)
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    // Mirrors Preferences.getFavoriteGameSnapshots
    private fun deserializeSnapshots(json: String): Map<String, CloudGame> {
        val jsonArray = JSONArray(json)
        val snapshots = LinkedHashMap<String, CloudGame>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val game = CloudGame(
                productId = obj.getString("productId"),
                name = obj.getString("name"),
                imageUrl = obj.getString("imageUrl"),
                landscapeImageUrl = obj.optString("landscapeImageUrl", obj.getString("imageUrl")),
                thumbnailUrl = obj.optString("thumbnailUrl", obj.getString("imageUrl")),
                platform = obj.optString("platform", "ps4"),
                serviceType = obj.optString("serviceType", "psnow"),
                conceptUrl = obj.optString("conceptUrl", ""),
                conceptId = obj.optString("conceptId", ""),
                isOwned = obj.optBoolean("isOwned", false),
                storeProductId = obj.optString("storeProductId", ""),
                plusCatalog = obj.optBoolean("plusCatalog", false),
                featureType = obj.optInt("featureType", 0)
            )
            snapshots[game.productId] = game
        }
        return snapshots
    }

    // Mirrors CloudPlayFragment.mergeFavorites
    private fun mergeFavorites(
        favoriteIds: Set<String>,
        games: List<CloudGame>,
        snapshots: Map<String, CloudGame>
    ): List<CloudGame> {
        val liveFavorites = games.filter { favoriteIds.contains(it.productId) }
        val liveIds = liveFavorites.map { it.productId }.toSet()
        val missingFavorites = favoriteIds.filter { it !in liveIds }.mapNotNull { snapshots[it] }
        return liveFavorites + missingFavorites
    }

    private val godOfWar = CloudGame(
        productId = "CUSA00001", name = "God of War", imageUrl = "https://img/gow.jpg",
        platform = "ps4", serviceType = "psnow"
    )
    private val spiderMan = CloudGame(
        productId = "CUSA00002", name = "Spider-Man", imageUrl = "https://img/sm.jpg",
        platform = "ps4", serviceType = "psnow"
    )

    // --- JSON round trip ---

    @Test
    fun `snapshot round trip preserves all fields`() {
        val json = serializeSnapshots(linkedMapOf(godOfWar.productId to godOfWar))
        val restored = deserializeSnapshots(json)

        assertEquals(1, restored.size)
        val game = restored.getValue(godOfWar.productId)
        assertEquals(godOfWar.name, game.name)
        assertEquals(godOfWar.imageUrl, game.imageUrl)
        assertEquals(godOfWar.platform, game.platform)
        assertEquals(godOfWar.serviceType, game.serviceType)
    }

    @Test
    fun `serializing empty snapshot map round trips to empty map`() {
        val json = serializeSnapshots(emptyMap())
        assertTrue(deserializeSnapshots(json).isEmpty())
    }

    @Test
    fun `removing a favorite drops it from the serialized snapshot`() {
        val snapshots = linkedMapOf(godOfWar.productId to godOfWar, spiderMan.productId to spiderMan)
            .toMutableMap()
        snapshots.remove(godOfWar.productId)

        val restored = deserializeSnapshots(serializeSnapshots(snapshots))

        assertEquals(1, restored.size)
        assertNull(restored[godOfWar.productId])
        assertTrue(restored.containsKey(spiderMan.productId))
    }

    // --- Merge behavior (the actual bug fix) ---

    @Test
    fun `favorited game missing from live catalog is filled in from snapshot`() {
        // Simulates: user favorited God of War, then app updates and the freshly
        // loaded catalog is cold/incomplete and doesn't include it yet.
        val favoriteIds = setOf(godOfWar.productId)
        val liveCatalog = emptyList<CloudGame>()
        val snapshots = mapOf(godOfWar.productId to godOfWar)

        val result = mergeFavorites(favoriteIds, liveCatalog, snapshots)

        assertEquals(1, result.size)
        assertEquals(godOfWar.productId, result.first().productId)
        assertEquals("God of War", result.first().name)
    }

    @Test
    fun `favorited game present in live catalog prefers live data over snapshot`() {
        val staleSnapshot = godOfWar.copy(name = "Stale Name")
        val liveGame = godOfWar.copy(name = "God of War (Live)")

        val result = mergeFavorites(
            favoriteIds = setOf(godOfWar.productId),
            games = listOf(liveGame),
            snapshots = mapOf(godOfWar.productId to staleSnapshot)
        )

        assertEquals(1, result.size)
        assertEquals("God of War (Live)", result.first().name)
    }

    @Test
    fun `non-favorited games are excluded even if present in catalog`() {
        val result = mergeFavorites(
            favoriteIds = setOf(godOfWar.productId),
            games = listOf(godOfWar, spiderMan),
            snapshots = emptyMap()
        )

        assertEquals(1, result.size)
        assertEquals(godOfWar.productId, result.first().productId)
    }

    @Test
    fun `favorite with no snapshot and not in live catalog is silently omitted`() {
        // Defensive: shouldn't crash if a favorite id somehow has no snapshot yet.
        val result = mergeFavorites(
            favoriteIds = setOf("UNKNOWN-ID"),
            games = emptyList(),
            snapshots = emptyMap()
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `mix of live and snapshot-only favorites are all returned`() {
        val result = mergeFavorites(
            favoriteIds = setOf(godOfWar.productId, spiderMan.productId),
            games = listOf(godOfWar),
            snapshots = mapOf(spiderMan.productId to spiderMan)
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.productId == godOfWar.productId })
        assertTrue(result.any { it.productId == spiderMan.productId })
    }
}
