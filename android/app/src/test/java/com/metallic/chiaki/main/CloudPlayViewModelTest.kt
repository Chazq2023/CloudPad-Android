package com.metallic.chiaki.main

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.common.Preferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CloudPlayViewModelTest {

    @get:Rule
    val instantRule = InstantTaskExecutorRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val preferences: Preferences = mockk(relaxed = true)

    private lateinit var viewModel: CloudPlayViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { preferences.getLastCloudSection() } returns "psnow_ps3"
        every { preferences.setLastCloudSection(any()) } just runs
        every { context.cacheDir } returns File(System.getProperty("java.io.tmpdir")!!)
        viewModel = CloudPlayViewModel(context, preferences)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Section management ---

    @Test
    fun `initial section is loaded from preferences`() {
        assertEquals("psnow_ps3", viewModel.getCurrentSection())
    }

    @Test
    fun `setCurrentSection updates in-memory section`() {
        viewModel.setCurrentSection("pscloud")
        assertEquals("pscloud", viewModel.getCurrentSection())
    }

    @Test
    fun `setCurrentSection persists to preferences`() {
        viewModel.setCurrentSection("psnow_ps4")
        verify { preferences.setLastCloudSection("psnow_ps4") }
    }

    @Test
    fun `setCurrentSection can switch between all three sections`() {
        viewModel.setCurrentSection("psnow_ps3")
        assertEquals("psnow_ps3", viewModel.getCurrentSection())

        viewModel.setCurrentSection("psnow_ps4")
        assertEquals("psnow_ps4", viewModel.getCurrentSection())

        viewModel.setCurrentSection("pscloud")
        assertEquals("pscloud", viewModel.getCurrentSection())
    }

    // --- Game list management ---

    @Test
    fun `setSortedGames emits games through LiveData`() {
        val games = listOf(makeGame("G1", "God of War"), makeGame("G2", "Spider-Man"))
        viewModel.setSortedGames(games)
        assertEquals(games, viewModel.games.value)
    }

    @Test
    fun `clearGames empties the LiveData`() {
        viewModel.setSortedGames(listOf(makeGame("G1", "Test Game")))
        viewModel.clearGames()
        assertTrue(viewModel.games.value?.isEmpty() == true)
    }

    @Test
    fun `getAllCachedGames returns the current internal list`() {
        val games = listOf(makeGame("G1", "Horizon"))
        viewModel.setSortedGames(games)
        assertEquals(games, viewModel.getAllCachedGames())
    }

    // --- Search filtering ---

    @Test
    fun `setSearchQuery filters games by name case-insensitively`() {
        viewModel.setSortedGames(listOf(makeGame("G1", "God of War"), makeGame("G2", "Spider-Man")))
        viewModel.setSearchQuery("god")
        val result = viewModel.games.value
        assertEquals(1, result?.size)
        assertEquals("God of War", result?.first()?.name)
    }

    @Test
    fun `setSearchQuery filters games by productId`() {
        viewModel.setSortedGames(listOf(makeGame("CUSA12345", "Game A"), makeGame("PPSA99999", "Game B")))
        viewModel.setSearchQuery("CUSA")
        assertEquals(1, viewModel.games.value?.size)
        assertEquals("CUSA12345", viewModel.games.value?.first()?.productId)
    }

    @Test
    fun `setSearchQuery with empty string shows all games`() {
        val games = listOf(makeGame("G1", "God of War"), makeGame("G2", "Spider-Man"))
        viewModel.setSortedGames(games)
        viewModel.setSearchQuery("god")
        viewModel.setSearchQuery("")
        assertEquals(2, viewModel.games.value?.size)
    }

    @Test
    fun `setSearchQuery with no matches returns empty list`() {
        viewModel.setSortedGames(listOf(makeGame("G1", "God of War"), makeGame("G2", "Spider-Man")))
        viewModel.setSearchQuery("zzznomatch")
        assertTrue(viewModel.games.value?.isEmpty() == true)
    }

    // --- reapplyCurrentGames ---

    @Test
    fun `reapplyCurrentGames re-emits the current game list`() {
        val games = listOf(makeGame("G1", "Horizon"), makeGame("G2", "Ghost of Tsushima"))
        viewModel.setSortedGames(games)
        // Simulate a section switch that would normally re-emit via reapplyCurrentGames
        viewModel.reapplyCurrentGames()
        assertEquals(games, viewModel.games.value)
    }

    @Test
    fun `reapplyCurrentGames respects active search filter`() {
        viewModel.setSortedGames(listOf(makeGame("G1", "Horizon"), makeGame("G2", "Ghost of Tsushima")))
        viewModel.setSearchQuery("horizon")
        viewModel.reapplyCurrentGames()
        assertEquals(1, viewModel.games.value?.size)
        assertEquals("Horizon", viewModel.games.value?.first()?.name)
    }

    // --- Error handling ---

    @Test
    fun `clearError sets error LiveData to null`() {
        viewModel.clearError()
        assertNull(viewModel.error.value)
    }

    // --- Helpers ---

    private fun makeGame(productId: String, name: String, platform: String = "ps4") = CloudGame(
        productId = productId,
        name = name,
        imageUrl = "https://example.com/$productId.jpg",
        platform = platform,
        serviceType = "psnow"
    )
}
