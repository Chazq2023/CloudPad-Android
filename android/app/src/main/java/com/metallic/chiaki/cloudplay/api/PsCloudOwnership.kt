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
			ent.activeFlag &&
				!ent.productId.startsWith("IP") &&
				!ent.productId.startsWith("SUB") &&
				// feature_type==0 is DLC/add-ons/themes/avatars — never a base game, safe to drop
				ent.featureType != 0
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
				ent.conceptId.isNotEmpty() && browseByConcept.containsKey(ent.conceptId) ->
					browseByConcept[ent.conceptId]
				ent.conceptId.isNotEmpty() && supplementByConcept.containsKey(ent.conceptId) ->
					supplementByConcept[ent.conceptId]
				ent.productId.isNotEmpty() && ent.id == ent.productId
					&& supplementMap.containsKey(ent.productId) ->
					supplementMap[ent.productId]
				stable != null && !skipStableDemo && browseStableKey.containsKey(stable) ->
					browseStableKey[stable]
				stable != null && !skipStableDemo && supplementStableKey.containsKey(stable) ->
					supplementStableKey[stable]
				entStable != null && !skipStableDemo && browseStableKey.containsKey(entStable) ->
					browseStableKey[entStable]
				entStable != null && !skipStableDemo && supplementStableKey.containsKey(entStable) ->
					supplementStableKey[entStable]
				else -> null
			}

			if (meta != null)
			{
				emit(meta, ent)
				continue
			}

			val seenPids = mutableSetOf<String>()
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

	// Edition identity: conceptId + PLATFORM so cross-gen titles appear as separate entries
	private fun ownedDedupeKey(meta: CloudGame, ent: Entitlement): String
	{
		if (meta.conceptId.isNotEmpty()) return "c:${meta.conceptId}:${platformToken(ent.productId)}"
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
			// Trials (feature_type 1) stay as their own card so the full version still shows
			// separately as a not-owned "Add Game" card
			val catalogMatch = if (owned.featureType == 1) -1 else findCatalogIndexForOwned(owned, catalogIndex)
			if (catalogMatch >= 0)
			{
				val existing = games[catalogMatch]
				games[catalogMatch] = existing.copy(
					isOwned = true,
					entitlementId = owned.entitlementId.ifEmpty { existing.entitlementId },
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

	// For pscloud: stream the owned storeProductId (the actual product_id from entitlements),
	// falling back to entitlementId, then productId. Cross-gen upgrades carry stale entitlement
	// ids that Gaikai has no game for; the storeProductId is the real streamable edition.
	fun streamingIdentifier(game: CloudGame): String
	{
		if (game.serviceType.equals("pscloud", ignoreCase = true))
		{
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
		return if (streamServiceType(game) == "psnow") game.productId.ifEmpty { streamingIdentifier(game) }
		else streamingIdentifier(game)
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
