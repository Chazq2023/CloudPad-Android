// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import java.util.concurrent.ConcurrentHashMap

/**
 * Small in-memory cache whose entries expire after [ttlMs]. Used to avoid re-downloading data that
 * doesn't change while the app is running. [now] is injectable for tests.
 */
class TtlCache<K : Any, V : Any>(private val ttlMs: Long, private val now: () -> Long = System::currentTimeMillis)
{
	private val entries = ConcurrentHashMap<K, Pair<Long, V>>()

	fun get(key: K): V?
	{
		val (at, value) = entries[key] ?: return null
		return if (now() - at in 0 until ttlMs) value else null.also { entries.remove(key) }
	}

	fun put(key: K, value: V) { entries[key] = now() to value }

	/** The cached value, or [load]s one and keeps it only if [worthKeeping] says it is a real result (never cache a failure or an empty answer). */
	suspend fun getOrLoad(key: K, worthKeeping: (V) -> Boolean = { true }, load: suspend () -> V): V
	{
		get(key)?.let { return it }
		return load().also { if (worthKeeping(it)) put(key, it) }
	}
}

/** Lets an action through at most once per [minIntervalMs]; used to stop automatic refreshes from repeating on every screen resume. */
class MinIntervalGate(private val minIntervalMs: Long, private val now: () -> Long = System::currentTimeMillis)
{
	private var lastMs: Long? = null

	@Synchronized
	fun tryAcquire(): Boolean
	{
		val t = now()
		val last = lastMs
		if (last != null && t - last in 0 until minIntervalMs) return false
		lastMs = t
		return true
	}
}
