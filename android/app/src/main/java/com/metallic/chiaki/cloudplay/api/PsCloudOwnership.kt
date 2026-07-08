// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.api

import android.util.Log
import com.metallic.chiaki.cloudplay.model.CloudGame
import org.json.JSONObject

object PsCloudOwnership
{
	private const val TAG = "PsCloudOwnership"
	const val PAGE_SIZE = 300
	const val PAGE_COOLDOWN_MS = 100L

	data class Entitlement(
		val id: String,
		val productId: String,
		val activeFlag: Boolean,
		val packageType: String,
		val name: String,
		val conceptId: String,
		val featureType: Int   // PSN feature_type: 3=full game, 1=trial/free, 0=add-on/DLC
	)

	private data class CatalogIndex(
		val byProductId: MutableMap<String, Int>,
		val byConceptId: MutableMap<String, Int>
	)

	fun filterOwnedPs5Games(entitlements: List<Entitlement>): List<Entitlement>
	{
		return entitlements.filter { ent ->
			// featureType 0 = DLC/add-ons/themes/avatars — never streamable games
			// featureType 1 (PS Plus subscription access) is intentionally kept: the catalog
			// cross-reference naturally filters out anything not in the streaming catalog,
			// and subscription entitlements carry the Gaikai streaming key in their id field.
			// Trials and demos are excluded by name or packageType (PSGT = PS Plus Game Trial).
			val keep = ent.activeFlag &&
				ent.featureType != 0 &&
				!ent.packageType.endsWith("GT", ignoreCase = true) &&
				!ent.name.contains(Regex("\\bdemo\\b", RegexOption.IGNORE_CASE)) &&
				!ent.name.contains(Regex("\\btrial\\b", RegexOption.IGNORE_CASE))
			if (keep) Log.d(TAG, "filter: kept '${ent.name}' productId=${ent.productId} packageType=${ent.packageType} featureType=${ent.featureType}")
			else Log.i(TAG, "filter: excluded '${ent.name}' id=${ent.id} productId=${ent.productId} featureType=${ent.featureType} packageType=${ent.packageType} active=${ent.activeFlag}")
			keep
		}
	}

	private fun conceptIdString(value: Any?): String = when (value)
	{
		is Number -> value.toLong().let { if (it > 0) it.toString() else "" }
		is String -> value
		else -> ""
	}

	fun parseEntitlement(obj: JSONObject): Entitlement?
	{
		val id = obj.optString("id", "")
		if (id.isEmpty()) return null
		val gameMeta = obj.optJSONObject("game_meta") ?: JSONObject()
		val name = gameMeta.optString("name", id)
		val conceptId = conceptIdString(gameMeta.opt("conceptId"))
			.ifEmpty { conceptIdString(gameMeta.opt("concept_id")) }
			.ifEmpty { conceptIdString(obj.opt("conceptId")) }
		return Entitlement(
			id = id,
			productId = obj.optString("product_id", ""),
			activeFlag = obj.optBoolean("active_flag", false),
			packageType = gameMeta.optString("package_type", ""),
			name = name,
			conceptId = conceptId,
			featureType = obj.optInt("feature_type", 0)
		)
	}

	fun crossReferenceOwnedGames(
		filteredEntitlements: List<Entitlement>,
		publicCatalog: List<CloudGame>,
		plusLibrarySupplement: List<CloudGame> = emptyList(),
		productIdAliases: Map<String, String> = emptyMap(),
		componentIdsByProductId: Map<String, List<String>> = emptyMap(),
	): List<CloudGame>
	{
		val catalogMap = catalogMapFirstWins(publicCatalog)
		for ((alias, canonical) in productIdAliases)
		{
			if (alias in catalogMap) continue
			catalogMap[canonical]?.let { catalogMap[alias] = it }
		}
		val supplementMap = catalogMapFirstWins(plusLibrarySupplement)
		val browseStableKey = buildStableKeyIndex(publicCatalog)
		val supplementStableKey = buildStableKeyIndex(plusLibrarySupplement)
		val browseByConcept = buildConceptIdIndex(publicCatalog)
		val supplementByConcept = buildConceptIdIndex(plusLibrarySupplement)
		val byKey = linkedMapOf<String, CloudGame>()
		val byKeyRank = mutableMapOf<String, Int>()

		fun emit(meta: CloudGame, ent: Entitlement)
		{
			val game = meta.copy(
				name = meta.name.ifEmpty { ent.name },
				isOwned = true,
				entitlementId = ent.id,
				storeProductId = ent.productId,
				featureType = ent.featureType
			)
			val key = ownedDedupeKey(meta, ent)
			val candidateRank = ownedStreamRank(ent)
			if (byKey[key] == null)
			{
				byKey[key] = game
				byKeyRank[key] = candidateRank
			}
			else if (candidateRank > (byKeyRank[key] ?: -1))
			{
				byKey[key] = game
				byKeyRank[key] = candidateRank
			}
		}

		for (ent in filteredEntitlements)
		{
			val stable = productIdStableKey(ent.productId)
			val entStable = productIdStableKey(ent.id)
			val skipStableDemo = ent.name.contains("demo", ignoreCase = true)
			val meta = when
			{
				ent.productId.isNotEmpty() && catalogMap.containsKey(ent.productId) ->
					catalogMap[ent.productId]
				ent.id.isNotEmpty() && catalogMap.containsKey(ent.id) ->
					catalogMap[ent.id]
				// Stable key (PPSA/CUSA number) before conceptId: bridges format gaps between the
				// bare "PPSA12345_00" entitlement format and the full "EP...-PPSA12345_00-..." catalog
				// format, and ensures distinct games that share a conceptId (e.g. TimeSplitters 1/2/FP)
				// each match their own catalog entry rather than all collapsing to the first one.
				stable != null && !skipStableDemo && browseStableKey.containsKey(stable) ->
					browseStableKey[stable]
				entStable != null && !skipStableDemo && browseStableKey.containsKey(entStable) ->
					browseStableKey[entStable]
				stable != null && !skipStableDemo && supplementStableKey.containsKey(stable) ->
					supplementStableKey[stable]
				entStable != null && !skipStableDemo && supplementStableKey.containsKey(entStable) ->
					supplementStableKey[entStable]
				// conceptId as fallback: handles entitlements with empty/bare productIds that
				// can't match via stable key (e.g. GoT PS4 purchase → GoT PS5 catalog entry)
				ent.conceptId.isNotEmpty() && browseByConcept.containsKey(ent.conceptId) ->
					browseByConcept[ent.conceptId]
				ent.conceptId.isNotEmpty() && supplementByConcept.containsKey(ent.conceptId) ->
					supplementByConcept[ent.conceptId]
				ent.productId.isNotEmpty() && ent.id == ent.productId
					&& supplementMap.containsKey(ent.productId) ->
					supplementMap[ent.productId]
				else -> null
			}

			if (meta != null)
			{
				emit(meta, ent)
				continue
			}

			val seenPids = mutableSetOf<String>()
			val preEmitSize = byKey.size
			for (siblingId in componentIdsByProductId[ent.productId] ?: emptyList())
			{
				val siblingMeta = when
				{
					catalogMap.containsKey(siblingId) -> catalogMap[siblingId]
					supplementMap.containsKey(siblingId) -> supplementMap[siblingId]
					else ->
					{
						val s2 = productIdStableKey(siblingId)
						if (s2 != null && !skipStableDemo) browseStableKey[s2] ?: supplementStableKey[s2] else null
					}
				} ?: continue
				if (siblingMeta.productId.isEmpty() || seenPids.contains(siblingMeta.productId)) continue
				seenPids.add(siblingMeta.productId)
				emit(siblingMeta, ent)
			}
			if (byKey.size == preEmitSize)
				Log.i(TAG, "crossRef miss: '${ent.name}' productId=${ent.productId} conceptId=${ent.conceptId} id=${ent.id}")
		}

		// Disc-upgrade rescue: feature_type 5 = PS4-disc -> PS5 disc upgrade that Gaikai won't stream.
		// Substitute the owned full-game (feature_type 3) product id so the card streams correctly.
		for (key in byKey.keys.toList())
		{
			val game = byKey[key] ?: continue
			if (game.featureType != 5) continue
			val discPid = game.storeProductId
			val discPlatform = platformToken(discPid)
			val discEnt = filteredEntitlements.firstOrNull {
				it.productId == discPid && it.featureType == 5
			} ?: continue
			val discName = normalizeTitle(discEnt.name)
			if (discName.isEmpty()) continue
			val canonical = mutableListOf<String>()
			val other = mutableListOf<String>()
			for (cand in filteredEntitlements)
			{
				if (cand.featureType != 3) continue
				if (normalizeTitle(cand.name) != discName) continue
				val candPid = cand.productId
				if (candPid.isEmpty() || candPid == discPid) continue
				if (platformToken(candPid) != discPlatform) continue
				if (candPid == cand.id)
				{
					if (candPid !in canonical) canonical.add(candPid)
				}
				else if (candPid !in other)
				{
					other.add(candPid)
				}
			}
			val replacement = when
			{
				canonical.size == 1 -> canonical[0]
				canonical.isEmpty() && other.size == 1 -> other[0]
				else -> null
			}
			if (replacement == null)
			{
				if (canonical.isNotEmpty() || other.isNotEmpty())
					Log.w(TAG, "disc-upgrade rescue: ambiguous candidates for $discName -- leaving disc SKU")
				continue
			}
			byKey[key] = game.copy(storeProductId = replacement)
			Log.i(TAG, "disc-upgrade rescue: $discName $discPid -> $replacement")
		}

		return byKey.values.toList()
	}

	// Dedup key: conceptId + catalog productId so that (a) two entitlements that both resolve
	// to the same catalog entry compete in one slot (GoT PS4 purchase and GoT PS5 subscription
	// both land on the GoT PS5 catalog entry → same key → PPSA subscription wins), while
	// (b) distinct games sharing a conceptId (TimeSplitters 1/2/FP) each get their own slot
	// because they resolve to different catalog entries with different productIds.
	private fun ownedDedupeKey(meta: CloudGame, ent: Entitlement): String
	{
		if (meta.conceptId.isNotEmpty()) return "c:${meta.conceptId}:${meta.productId}"
		if (meta.productId.isNotEmpty()) return "p:${meta.productId}"
		if (ent.id.isNotEmpty()) return "e:${ent.id}"
		return "u:${meta.productId}:${ent.id}"
	}

	fun platformToken(productId: String): String = when
	{
		productId.contains("PPSA") -> "ps5"
		productId.contains("CUSA") -> "ps4"
		else -> ""
	}

	private fun normalizeTitle(raw: String): String =
		raw.lowercase()
			.replace("™", "").replace("®", "").replace("℠", "")
			.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

	private fun isFullGameEntitlement(ent: Entitlement): Boolean =
		ent.featureType == 3 || ent.packageType.endsWith("GD")

	private fun ownedStreamRank(ent: Entitlement): Int
	{
		var rank = 0
		if (ent.productId.isNotEmpty() && ent.productId == ent.id) rank += 4
		if (isFullGameEntitlement(ent)) rank += 2
		if (ent.id.isNotEmpty()) rank += 1
		// Prefer PS5 (PPSA) entitlement ids: they carry the Gaikai streaming key for
		// PS Plus subscription games (e.g. GoT streaming ent vs PS4 purchase ent)
		if (ent.id.contains("PPSA")) rank += 3
		return rank
	}

	private fun conceptPlatformKey(game: CloudGame): String
	{
		if (game.conceptId.isEmpty()) return ""
		val pid = if (game.storeProductId.isNotEmpty()) game.storeProductId else game.productId
		return "${game.conceptId}|${platformToken(pid)}"
	}

	private fun catalogMapFirstWins(games: List<CloudGame>): MutableMap<String, CloudGame>
	{
		val map = linkedMapOf<String, CloudGame>()
		for (game in games)
		{
			if (game.productId.isNotEmpty() && game.productId !in map)
				map[game.productId] = game
		}
		return map
	}

	private fun productIdStableKey(productId: String): String?
	{
		if (productId.isEmpty()) return null
		// PPSA/CUSA number is stable across PSN product ID formats:
		// entitlement API returns "PPSA01147_00", imagic catalog has "EP9000-PPSA01147_00-SUFFIX".
		// Both extract to "PPSA01147", enabling cross-reference matches for games like Demon's Souls.
		val titleId = Regex("(?:PPSA|CUSA)\\d+").find(productId)?.value
		if (titleId != null) return titleId
		val tokens = mutableListOf<String>()
		for (dashPart in productId.split('-'))
			for (token in dashPart.split('_'))
				if (token.isNotEmpty()) tokens.add(token)
		if (tokens.size < 2) return null
		return tokens.dropLast(1).joinToString("|")
	}

	private fun buildStableKeyIndex(games: List<CloudGame>): Map<String, CloudGame>
	{
		val index = linkedMapOf<String, CloudGame>()
		for (game in games)
		{
			val key = productIdStableKey(game.productId) ?: continue
			if (key !in index) index[key] = game
		}
		return index
	}

	private fun buildConceptIdIndex(games: List<CloudGame>): Map<String, CloudGame>
	{
		val index = linkedMapOf<String, CloudGame>()
		for (game in games)
		{
			if (game.conceptId.isNotEmpty() && game.conceptId !in index)
				index[game.conceptId] = game
		}
		return index
	}

	fun mergeOwnedIntoBrowseCatalog(
		browseCatalog: List<CloudGame>,
		ownedCrossRef: List<CloudGame>,
		addUnmatched: Boolean = true
	): List<CloudGame>
	{
		val games = browseCatalog.toMutableList()
		val catalogIndex = buildCatalogIndex(games)

		for (owned in ownedCrossRef)
		{
			val catalogMatch = findCatalogIndexForOwned(owned, catalogIndex)
			if (catalogMatch >= 0)
			{
				val existing = games[catalogMatch]
				// Prefer PS5 (PPSA) entitlementIds: a streaming entitlement's Gaikai key wins
				// over a PS4 purchase's CUSA id regardless of merge order.
				val bestEntitlementId = when
				{
					owned.entitlementId.contains("PPSA") -> owned.entitlementId
					existing.entitlementId.contains("PPSA") -> existing.entitlementId
					owned.entitlementId.isNotEmpty() -> owned.entitlementId
					else -> existing.entitlementId
				}
				games[catalogMatch] = existing.copy(
					isOwned = true,
					entitlementId = bestEntitlementId,
					storeProductId = owned.storeProductId.ifEmpty { existing.storeProductId }
				)
				continue
			}

			if (!addUnmatched) continue
			val entry = owned.copy(isOwned = true)
			registerInCatalogIndex(entry, games.size, catalogIndex)
			games.add(entry)
		}

		return games.sortedWith(
			compareByDescending<CloudGame> { it.isOwned }
				.thenBy { it.name.lowercase() }
		)
	}

	// For pscloud: Gaikai identifies games by their catalog productId (from the imagic list).
	// Exception: disc-upgrade rescue (featureType=5) replaces storeProductId with the owned
	// full-game edition. The catalog holds the disc-upgrade SKU which Gaikai won't stream;
	// use storeProductId in that case. For all other feature types (including games with
	// two legitimately different PS5 SKUs like Ghost of Tsushima), the catalog productId wins.
	fun streamingIdentifier(game: CloudGame): String
	{
		if (game.serviceType.equals("pscloud", ignoreCase = true))
		{
			if (game.featureType == 5 &&
				game.storeProductId.isNotEmpty() &&
				game.storeProductId != game.productId)
				return game.storeProductId
			if (game.productId.isNotEmpty()) return game.productId
			if (game.storeProductId.isNotEmpty()) return game.storeProductId
			if (game.entitlementId.isNotEmpty()) return game.entitlementId
		}
		return game.productId
	}

	fun streamPlatform(game: CloudGame): String
	{
		val p = game.storeProductId.ifEmpty { game.productId.ifEmpty { game.entitlementId } }
		return when
		{
			p.contains("PPSA") -> "ps5"
			p.contains("CUSA") -> "ps4"
			else -> game.platform.ifEmpty { "ps5" }
		}
	}

	fun streamServiceType(game: CloudGame): String
	{
		if (game.serviceType.equals("psnow", ignoreCase = true)) return "psnow"
		return if (streamPlatform(game) == "ps4") "psnow" else "pscloud"
	}

	fun streamIdentifier(game: CloudGame): String
	{
		val svcType = streamServiceType(game)
		val id = if (svcType == "psnow") game.productId.ifEmpty { streamingIdentifier(game) }
		else streamingIdentifier(game)
		Log.i(TAG, "streamIdentifier '${game.name}': productId=${game.productId} storeProductId=${game.storeProductId} entitlementId=${game.entitlementId} -> $id (svc=$svcType)")
		return id
	}

	private fun buildCatalogIndex(games: List<CloudGame>): CatalogIndex
	{
		val byProductId = mutableMapOf<String, Int>()
		val byConceptId = mutableMapOf<String, Int>()
		for (i in games.indices)
			registerInCatalogIndex(games[i], i, CatalogIndex(byProductId, byConceptId))
		return CatalogIndex(byProductId, byConceptId)
	}

	private fun registerInCatalogIndex(game: CloudGame, index: Int, catalogIndex: CatalogIndex)
	{
		if (game.productId.isNotEmpty())
			catalogIndex.byProductId[game.productId] = index
		val conceptKey = conceptPlatformKey(game)
		if (conceptKey.isNotEmpty())
			catalogIndex.byConceptId[conceptKey] = index
		if (game.entitlementId.isNotEmpty() && game.entitlementId != game.productId)
			catalogIndex.byProductId[game.entitlementId] = index
	}

	private fun findCatalogIndexForOwned(owned: CloudGame, catalogIndex: CatalogIndex): Int
	{
		if (owned.productId.isNotEmpty() && catalogIndex.byProductId.containsKey(owned.productId))
			return catalogIndex.byProductId.getValue(owned.productId)
		if (owned.entitlementId.isNotEmpty() && catalogIndex.byProductId.containsKey(owned.entitlementId))
			return catalogIndex.byProductId.getValue(owned.entitlementId)
		if (owned.storeProductId.isNotEmpty() && catalogIndex.byProductId.containsKey(owned.storeProductId))
			return catalogIndex.byProductId.getValue(owned.storeProductId)
		val conceptKey = conceptPlatformKey(owned)
		if (conceptKey.isNotEmpty() && catalogIndex.byConceptId.containsKey(conceptKey))
			return catalogIndex.byConceptId.getValue(conceptKey)
		return -1
	}
}
