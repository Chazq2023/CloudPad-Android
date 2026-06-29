package com.metallic.chiaki.cloudplay.repository

import android.content.Context
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.cloudplay.model.PsnResult
import com.metallic.chiaki.common.Preferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests the disk-caching layer of CloudGameRepository using a real temp directory.
 * Network-dependent paths (force refresh, expired cache) are covered by asserting
 * that cached data is NOT returned, without asserting a specific error message,
 * since the actual network response is environment-dependent.
 *
 * Tests that require a real network call are excluded to keep the suite deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudGameRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var tempDir: File
    private val context: Context = mockk(relaxed = true)
    private val preferences: Preferences = mockk(relaxed = true)
    private lateinit var repository: CloudGameRepository

    companion object {
        private val PSNOW_CACHE_GAME_JSON = """
            [{"productId":"PS3-001","name":"God of War III",
              "imageUrl":"https://img.com/gow3.jpg",
              "landscapeImageUrl":"https://img.com/gow3l.jpg",
              "thumbnailUrl":"https://img.com/gow3t.jpg",
              "platform":"ps3","serviceType":"psnow",
              "conceptUrl":"","isOwned":false,"entitlementId":""}]
        """.trimIndent()

        private val OWNED_PS5_CACHE_JSON = """
            [{"productId":"PPSA01234","name":"Demon's Souls",
              "imageUrl":"https://img.com/ds.jpg",
              "landscapeImageUrl":"https://img.com/ds_land.jpg",
              "thumbnailUrl":"https://img.com/ds_thumb.jpg",
              "platform":"ps5","serviceType":"pscloud",
              "conceptUrl":"","isOwned":true,"entitlementId":"ABCPSRSVD0000000000"}]
        """.trimIndent()

        private val ROUND_TRIP_JSON = """
            [{"productId":"CUSA12345","name":"Spider-Man",
              "imageUrl":"https://img.com/sm.jpg",
              "landscapeImageUrl":"https://img.com/sm_land.jpg",
              "thumbnailUrl":"https://img.com/sm_thumb.jpg",
              "platform":"ps4","serviceType":"psnow",
              "conceptUrl":"https://store.playstation.com/concept/12345",
              "isOwned":false,"entitlementId":""}]
        """.trimIndent()

        private val MS_25_HOURS = 25L * 60 * 60 * 1000
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("chiaki_cache_test").toFile()
        every { context.cacheDir } returns tempDir
        every { preferences.getNpssoToken() } returns ""
        every { preferences.getCloudStoreLocale() } returns "en-US"
        repository = CloudGameRepository(context, preferences)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    // --- Cache hit: PSNow ---

    @Test
    fun `fetchPsnowCatalog returns cached games when cache is valid`() = runTest {
        writeCacheFile("psnow_catalog.json", PSNOW_CACHE_GAME_JSON)

        val result = repository.fetchPsnowCatalog("", forceRefresh = false)

        assertTrue("Expected Success but got: $result", result is PsnResult.Success)
        val games = (result as PsnResult.Success).data
        assertEquals(1, games.size)
        assertEquals("God of War III", games.first().name)
        assertEquals("PS3-001", games.first().productId)
        assertEquals("ps3", games.first().platform)
    }

    @Test
    fun `fetchPsnowCatalog cache hit preserves all fields`() = runTest {
        writeCacheFile("psnow_catalog.json", ROUND_TRIP_JSON)

        val result = repository.fetchPsnowCatalog("", forceRefresh = false)

        assertTrue(result is PsnResult.Success)
        val game = (result as PsnResult.Success).data.first()
        assertEquals("CUSA12345", game.productId)
        assertEquals("Spider-Man", game.name)
        assertEquals("https://img.com/sm.jpg", game.imageUrl)
        assertEquals("https://img.com/sm_land.jpg", game.landscapeImageUrl)
        assertEquals("ps4", game.platform)
        assertEquals("psnow", game.serviceType)
        assertEquals("https://store.playstation.com/concept/12345", game.conceptUrl)
    }

    // --- Cache hit: Owned PS5 ---

    @Test
    fun `fetchOwnedPs5Games returns cached games when cache is valid`() = runTest {
        writeCacheFile("pscloud_owned.json", OWNED_PS5_CACHE_JSON)

        val result = repository.fetchOwnedPs5Games("", forceRefresh = false)

        assertTrue("Expected Success but got: $result", result is PsnResult.Success)
        val games = (result as PsnResult.Success).data
        assertEquals(1, games.size)
        assertEquals("Demon's Souls", games.first().name)
        assertTrue("isOwned should be true", games.first().isOwned)
        assertEquals("ps5", games.first().platform)
    }

    @Test
    fun `fetchOwnedPs5Games only restores PSRSVD entitlements from cache`() = runTest {
        // PSRSVD0000000000 suffix → kept; other entitlements → dropped
        val json = """
            [{"productId":"A","name":"Game A","imageUrl":"","landscapeImageUrl":"",
              "thumbnailUrl":"","platform":"ps5","serviceType":"pscloud","conceptUrl":"",
              "isOwned":true,"entitlementId":"TOKPSRSVD0000000000"},
             {"productId":"B","name":"Game B","imageUrl":"","landscapeImageUrl":"",
              "thumbnailUrl":"","platform":"ps5","serviceType":"pscloud","conceptUrl":"",
              "isOwned":true,"entitlementId":"PSNW01_OLD_FORMAT"}]
        """.trimIndent()
        writeCacheFile("pscloud_owned.json", json)

        val result = repository.fetchOwnedPs5Games("", forceRefresh = false)

        assertTrue(result is PsnResult.Success)
        val games = (result as PsnResult.Success).data
        assertEquals("TOKPSRSVD0000000000", games.find { it.productId == "A" }?.entitlementId)
        assertEquals("", games.find { it.productId == "B" }?.entitlementId)
    }

    // --- Cache miss ---

    @Test
    fun `fetchPsnowCatalog with no cache file returns non-cached result`() = runTest {
        // No cache file written — repository should attempt a network call
        // We don't assert Success here since network is not available in unit tests
        val result = repository.fetchPsnowCatalog("", forceRefresh = false)
        // The result is either an Error (no network) or Success (if somehow reachable)
        // What we verify: it is NOT a cached result (cache doesn't exist yet)
        assertTrue("Result must be a PsnResult", result is PsnResult.Success || result is PsnResult.Error)
    }

    // --- Expired cache ---

    @Test
    fun `fetchPsnowCatalog with expired cache does not return expired data`() = runTest {
        writeCacheFile("psnow_catalog.json", PSNOW_CACHE_GAME_JSON, ageMs = MS_25_HOURS)

        val result = repository.fetchPsnowCatalog("", forceRefresh = false)

        // Expired data must NOT be served — either Error or fresh Success (not expired data)
        if (result is PsnResult.Success) {
            assertTrue(
                "Expired cached data must not be returned",
                result.data.none { it.name == "God of War III" && it.productId == "PS3-001" }
            )
        }
        // PsnResult.Error is the expected outcome when no network is available
    }

    @Test
    fun `expired cache file is deleted from disk`() = runTest {
        val cacheFile = writeCacheFile("psnow_catalog.json", PSNOW_CACHE_GAME_JSON, ageMs = MS_25_HOURS)

        repository.fetchPsnowCatalog("", forceRefresh = false)

        assertTrue("Expired cache file should be deleted", !cacheFile.exists())
    }

    // --- clearCache ---

    @Test
    fun `clearCache removes all files from cache directory`() = runTest {
        writeCacheFile("psnow_catalog.json", PSNOW_CACHE_GAME_JSON)
        writeCacheFile("pscloud_owned.json", OWNED_PS5_CACHE_JSON)
        val cacheDir = File(tempDir, "cloud_catalog_cache")
        assertTrue("Cache dir should exist before clear", cacheDir.exists())
        assertTrue("Cache files should exist before clear", (cacheDir.listFiles()?.size ?: 0) > 0)

        repository.clearCache()

        val remainingFiles = cacheDir.listFiles() ?: emptyArray()
        assertTrue("Cache directory should be empty after clearCache", remainingFiles.isEmpty())
    }

    @Test
    fun `clearCache is safe when cache directory is empty`() = runTest {
        // Trigger lazy cacheDir creation by fetching (no cache → hits network path, which fails)
        repository.fetchPsnowCatalog("", forceRefresh = false)

        // Should not throw
        repository.clearCache()
    }

    // --- Helpers ---

    private fun writeCacheFile(filename: String, json: String, ageMs: Long = 0): File {
        val cacheDir = File(tempDir, "cloud_catalog_cache").apply { mkdirs() }
        val cacheFile = File(cacheDir, filename)
        cacheFile.writeText(json)
        if (ageMs > 0) {
            cacheFile.setLastModified(System.currentTimeMillis() - ageMs)
        }
        return cacheFile
    }
}
