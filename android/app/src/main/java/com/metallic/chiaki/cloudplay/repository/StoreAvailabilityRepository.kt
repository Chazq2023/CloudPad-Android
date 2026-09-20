// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.repository

import android.content.Context
import android.util.Log
import com.metallic.chiaki.cloudplay.api.StoreAvailabilityService
import com.metallic.chiaki.cloudplay.api.StoreVerdict
import com.metallic.chiaki.cloudplay.model.CloudGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saved answers plus when live checks were made (for the hourly cap). */
internal data class AvailabilityState(
	val verdicts: Map<String, Pair<StoreVerdict, Long>>,
	val checkTimesMs: List<Long>
)
{
	fun encode(): String = JSONObject()
		.put("verdicts", JSONObject().also { obj ->
			verdicts.forEach { (id, v) -> obj.put(id, JSONObject().put("v", v.first.name).put("at", v.second)) }
		})
		.put("checks", JSONArray(checkTimesMs))
		.toString()

	companion object
	{
		val EMPTY = AvailabilityState(emptyMap(), emptyList())

		fun decode(text: String): AvailabilityState = try
		{
			val root = JSONObject(text)
			val v = root.optJSONObject("verdicts") ?: JSONObject()
			val map = HashMap<String, Pair<StoreVerdict, Long>>()
			v.keys().forEach { id ->
				val e = v.getJSONObject(id)
				val verdict = StoreVerdict.valueOf(e.getString("v"))
				if (verdict != StoreVerdict.UNKNOWN) map[id] = verdict to e.getLong("at")
			}
			val checks = root.optJSONArray("checks")
			AvailabilityState(map, (0 until (checks?.length() ?: 0)).map { checks!!.getLong(it) })
		}
		catch (e: Exception) { EMPTY }
	}
}

/** Result of re-checking a hidden game from the Unavailable filter. */
enum class RecheckOutcome
{
	AVAILABLE, UNAVAILABLE,
	/** The page couldn't be read this time; the game stays as it was. */
	UNKNOWN,
	/** The hourly limit on live checks is used up; nothing was requested. */
	LIMIT_REACHED
}

/**
 * Answers "can this game actually be bought or added on the PlayStation Store?" for the Add Game
 * page, one game at a time, only when the user taps it (see [StoreAvailabilityService]).
 *
 * Load on the store is kept small on purpose: answers are remembered (available 7 days,
 * unavailable 30 days), a game is never re-checked while its answer is fresh, and at most
 * [MAX_CHECKS_PER_HOUR] live page requests are made per hour — past that it simply doesn't check
 * (the caller opens the store page as normal). Games known to be unavailable are seeded so they
 * are hidden straight away.
 */
class StoreAvailabilityRepository internal constructor(
	context: Context,
	private val fetchVerdict: suspend (conceptUrl: String, productId: String) -> StoreVerdict,
	private val now: () -> Long
)
{
	constructor(context: Context) : this(context, StoreAvailabilityService::check, System::currentTimeMillis)

	companion object
	{
		private const val TAG = "StoreAvailabilityRepo"
		private const val CACHE_DIR = "store_availability"
		private const val CACHE_FILE = "verdicts.json"
		internal const val AVAILABLE_TTL_MS = 7L * 24 * 60 * 60 * 1000
		internal const val UNAVAILABLE_TTL_MS = 30L * 24 * 60 * 60 * 1000
		internal const val HOUR_MS = 60L * 60 * 1000
		internal const val MAX_CHECKS_PER_HOUR = 20

		/** Known to have no PS5 buy/add option; hidden without any request. Matched by product id or exact name (other regions use other ids). */
		internal val KNOWN_UNAVAILABLE_PRODUCT_IDS = setOf("EP4008-PPSA02019_00-TWT2SIEE00000000")
		internal val KNOWN_UNAVAILABLE_NAMES = setOf("tennisworldtour2")

		private fun normalized(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
	}

	private val file = File(File(context.cacheDir, CACHE_DIR).apply { mkdirs() }, CACHE_FILE)
	private val lock = Mutex()

	private fun load(): AvailabilityState = try { AvailabilityState.decode(file.readText()) } catch (e: Exception) { AvailabilityState.EMPTY }
	private fun save(state: AvailabilityState)
	{
		try { file.writeText(state.encode()) } catch (e: Exception) { Log.w(TAG, "Could not save availability answers", e) }
	}

	private fun isSeededUnavailable(game: CloudGame) =
		game.productId in KNOWN_UNAVAILABLE_PRODUCT_IDS || normalized(game.name) in KNOWN_UNAVAILABLE_NAMES

	/**
	 * What is known about [game] without asking the store. A saved answer always wins over the
	 * built-in list — even an expired one, which just means "unknown, check again" — so a live
	 * check that finds a seeded game available can't be undone by the seed later.
	 */
	private fun freshVerdict(state: AvailabilityState, game: CloudGame): StoreVerdict
	{
		val saved = state.verdicts[game.productId]
			?: return if (isSeededUnavailable(game)) StoreVerdict.UNAVAILABLE else StoreVerdict.UNKNOWN
		val (verdict, at) = saved
		val ttl = if (verdict == StoreVerdict.UNAVAILABLE) UNAVAILABLE_TTL_MS else AVAILABLE_TTL_MS
		return if (now() - at in 0 until ttl) verdict else StoreVerdict.UNKNOWN
	}

	/** [games] split into (listed, hidden) by what is already known about each. No network. */
	suspend fun splitByAvailability(games: List<CloudGame>): Pair<List<CloudGame>, List<CloudGame>> = withContext(Dispatchers.IO)
	{
		val state = lock.withLock { load() }
		games.partition { freshVerdict(state, it) != StoreVerdict.UNAVAILABLE }
	}

	/**
	 * Checks a hidden game against the store now, ignoring what's saved (still capped at
	 * [MAX_CHECKS_PER_HOUR] an hour). A definite answer replaces the saved one, so a game that is
	 * back on sale stops being hidden and one that still isn't stays hidden for another 30 days.
	 */
	suspend fun recheck(game: CloudGame): RecheckOutcome = withContext(Dispatchers.IO)
	{
		lock.withLock {
			if (game.conceptUrl.isEmpty()) return@withContext RecheckOutcome.UNKNOWN
			val state = load()
			val recent = state.checkTimesMs.filter { now() - it in 0 until HOUR_MS }
			if (recent.size >= MAX_CHECKS_PER_HOUR) return@withContext RecheckOutcome.LIMIT_REACHED

			val verdict = fetchVerdict(game.conceptUrl, game.productId)
			val verdicts = if (verdict == StoreVerdict.UNKNOWN) state.verdicts else state.verdicts + (game.productId to (verdict to now()))
			save(AvailabilityState(verdicts, recent + now()))
			when (verdict)
			{
				StoreVerdict.AVAILABLE -> RecheckOutcome.AVAILABLE
				StoreVerdict.UNAVAILABLE -> RecheckOutcome.UNAVAILABLE
				StoreVerdict.UNKNOWN -> RecheckOutcome.UNKNOWN
			}
		}
	}

	/**
	 * The verdict for [game]: from the saved answers if fresh, else one live page check (unless the
	 * hourly cap is used up, in which case [StoreVerdict.UNKNOWN]). Only definite answers are saved.
	 */
	suspend fun check(game: CloudGame): StoreVerdict = withContext(Dispatchers.IO)
	{
		lock.withLock {
			val state = load()
			val known = freshVerdict(state, game)
			if (known != StoreVerdict.UNKNOWN) return@withContext known
			if (game.conceptUrl.isEmpty()) return@withContext StoreVerdict.UNKNOWN

			val recent = state.checkTimesMs.filter { now() - it in 0 until HOUR_MS }
			if (recent.size >= MAX_CHECKS_PER_HOUR)
			{
				Log.i(TAG, "Hourly check limit reached; not checking ${game.name}")
				return@withContext StoreVerdict.UNKNOWN
			}

			val verdict = fetchVerdict(game.conceptUrl, game.productId)
			val verdicts = if (verdict == StoreVerdict.UNKNOWN) state.verdicts else state.verdicts + (game.productId to (verdict to now()))
			save(AvailabilityState(verdicts, recent + now()))
			verdict
		}
	}
}
