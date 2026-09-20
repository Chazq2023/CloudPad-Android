package com.metallic.chiaki.cloudplay.repository

import android.content.Context
import com.metallic.chiaki.cloudplay.api.StoreVerdict
import com.metallic.chiaki.cloudplay.model.CloudGame
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoreAvailabilityRepositoryTest {

    private lateinit var tempDir: File
    private val context: Context = mockk(relaxed = true)
    private var clock = 1_000_000_000L
    private var calls = 0
    private var next = StoreVerdict.AVAILABLE

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("availability_test").toFile()
        every { context.cacheDir } returns tempDir
    }

    @After
    fun tearDown() { tempDir.deleteRecursively() }

    private fun repo() = StoreAvailabilityRepository(context, { _, _ -> calls++; next }, { clock })

    private fun game(id: String, name: String = "Game $id", url: String = "https://store.example/$id") =
        CloudGame(id, name, "", platform = "ps5", serviceType = "pscloud", conceptUrl = url)

    @Test
    fun `a definite answer is saved and not fetched again`() = runTest {
        val r = repo()

        assertEquals(StoreVerdict.AVAILABLE, r.check(game("EP1-PPSA00001_00-A")))
        assertEquals(StoreVerdict.AVAILABLE, r.check(game("EP1-PPSA00001_00-A")))
        assertEquals(StoreVerdict.AVAILABLE, repo().check(game("EP1-PPSA00001_00-A"))) // survives a new instance
        assertEquals(1, calls)
    }

    @Test
    fun `an unknown answer is not saved so the game is checked again later`() = runTest {
        next = StoreVerdict.UNKNOWN

        repo().check(game("EP1-PPSA00002_00-B"))
        repo().check(game("EP1-PPSA00002_00-B"))

        assertEquals(2, calls)
    }

    @Test
    fun `an unavailable game is hidden from the list afterwards`() = runTest {
        next = StoreVerdict.UNAVAILABLE
        val bad = game("EP1-PPSA00003_00-C"); val fine = game("EP1-PPSA00004_00-D")
        val r = repo()

        assertEquals(listOf(bad, fine), r.withoutUnavailable(listOf(bad, fine)))
        r.check(bad)

        assertEquals(listOf(fine), repo().withoutUnavailable(listOf(bad, fine)))
    }

    @Test
    fun `Tennis World Tour 2 is hidden with no request, by id or by name`() = runTest {
        val byId = game("EP4008-PPSA02019_00-TWT2SIEE00000000", name = "Tennis World Tour 2 - Complete Edition")
        val byName = game("UP4008-PPSA99999_00-OTHERREGION", name = "Tennis World Tour 2")
        val other = game("EP1-PPSA00005_00-E", name = "Tennis World Tour")

        assertEquals(listOf(other), repo().withoutUnavailable(listOf(byId, byName, other)))
        assertEquals(StoreVerdict.UNAVAILABLE, repo().check(byId))
        assertEquals(0, calls)
    }

    @Test
    fun `available answers expire after a week and unavailable ones after a month`() = runTest {
        val a = game("EP1-PPSA00006_00-F"); val u = game("EP1-PPSA00007_00-G")
        next = StoreVerdict.AVAILABLE; repo().check(a)
        next = StoreVerdict.UNAVAILABLE; repo().check(u)
        calls = 0

        clock += StoreAvailabilityRepository.AVAILABLE_TTL_MS - 1
        repo().check(a); assertEquals(0, calls)
        clock += 2
        next = StoreVerdict.AVAILABLE; repo().check(a); assertEquals(1, calls)

        assertEquals(emptyList<CloudGame>(), repo().withoutUnavailable(listOf(u))) // still within 30 days
        clock += StoreAvailabilityRepository.UNAVAILABLE_TTL_MS
        assertEquals(listOf(u), repo().withoutUnavailable(listOf(u)))
    }

    @Test
    fun `no more than 20 live checks an hour, then it stops checking`() = runTest {
        repeat(StoreAvailabilityRepository.MAX_CHECKS_PER_HOUR) { repo().check(game("EP1-PPSA1%04d_00-X".format(it))) }
        assertEquals(StoreAvailabilityRepository.MAX_CHECKS_PER_HOUR, calls)

        assertEquals(StoreVerdict.UNKNOWN, repo().check(game("EP1-PPSA20000_00-OVER")))
        assertEquals(StoreAvailabilityRepository.MAX_CHECKS_PER_HOUR, calls)

        clock += StoreAvailabilityRepository.HOUR_MS + 1
        assertEquals(StoreVerdict.AVAILABLE, repo().check(game("EP1-PPSA20000_00-OVER")))
        assertEquals(StoreAvailabilityRepository.MAX_CHECKS_PER_HOUR + 1, calls)
    }

    @Test
    fun `a saved answer is served even when the hourly limit is used up`() = runTest {
        val known = game("EP1-PPSA00008_00-H")
        repo().check(known)
        repeat(StoreAvailabilityRepository.MAX_CHECKS_PER_HOUR) { repo().check(game("EP1-PPSA3%04d_00-Y".format(it))) }
        calls = 0

        assertEquals(StoreVerdict.AVAILABLE, repo().check(known))
        assertEquals(0, calls)
    }

    @Test
    fun `a game with no store link is never fetched`() = runTest {
        assertEquals(StoreVerdict.UNKNOWN, repo().check(game("EP1-PPSA00009_00-I", url = "")))
        assertEquals(0, calls)
    }

    @Test
    fun `a corrupt saved file is treated as empty`() = runTest {
        File(File(tempDir, "store_availability").apply { mkdirs() }, "verdicts.json").writeText("not json")

        assertEquals(StoreVerdict.AVAILABLE, repo().check(game("EP1-PPSA00010_00-J")))
        assertTrue(calls == 1)
    }
}
