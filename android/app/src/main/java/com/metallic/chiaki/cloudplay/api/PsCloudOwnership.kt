// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.cloudplay.api

import android.util.Log
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.cloudplay.model.StreamableStatus
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
		val featureType: Int,   // PSN feature_type: 3=full game, 1=trial/free, 0=add-on/DLC
		val skuType: String = "",  // PSN sku_type: "GAME_TRIAL" for limited-play game trials
		val iconUrl: String = ""  // game_meta.icon_url — box art straight from the entitlement itself
	)

	private data class CatalogIndex(
		val byProductId: MutableMap<String, Int>,
		val byConceptId: MutableMap<String, Int>
	)

	fun filterOwnedPs5Games(entitlements: List<Entitlement>): List<Entitlement>
	{
		return entitlements.filter { ent ->
			// featureType 0 = DLC/add-ons/themes/avatars — never streamable games
			// featureType 1 (PS Plus subscription access and Game Trials) is intentionally kept:
			// Game Trials are valid streaming entries and the cross-reference naturally filters
			// out anything not in the streaming catalog.
			val keep = ent.activeFlag &&
				ent.featureType != 0 &&
				!ent.packageType.endsWith("GT", ignoreCase = true) &&
				!ent.skuType.contains("trial", ignoreCase = true) &&
				!ent.name.contains(Regex("\\bdemo\\b", RegexOption.IGNORE_CASE)) &&
				!ent.name.contains(Regex("\\btrial\\b", RegexOption.IGNORE_CASE))
			if (keep) Log.d(TAG, "filter: kept '${ent.name}' id=${ent.id} productId=${ent.productId} skuType=${ent.skuType} packageType=${ent.packageType} featureType=${ent.featureType}")
			else Log.i(TAG, "filter: excluded '${ent.name}' id=${ent.id} productId=${ent.productId} featureType=${ent.featureType} packageType=${ent.packageType} skuType=${ent.skuType} active=${ent.activeFlag}")
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
		val skuType = obj.optString("sku_type", "")
			.ifEmpty { gameMeta.optString("sku_type", "") }
		return Entitlement(
			id = id,
			productId = obj.optString("product_id", ""),
			activeFlag = obj.optBoolean("active_flag", false),
			packageType = gameMeta.optString("package_type", ""),
			name = name,
			conceptId = conceptId,
			featureType = obj.optInt("feature_type", 0),
			skuType = skuType,
			iconUrl = gameMeta.optString("icon_url", "")
		)
	}

	/**
	 * Builds the owned-games (Library) list directly from the user's entitlements — no public
	 * catalog cross-reference and nothing to hardcode per title. Sony's entitlement data already
	 * carries a usable icon and the identifiers Gaikai validates against; a title Sony hasn't
	 * enabled for cloud streaming yet will simply fail to start with whatever error Gaikai
	 * returns, same as it would in Sony's own apps — the user just needs to be aware, rather than
	 * the app silently omitting or misidentifying it.
	 *
	 * Restricted to PS5-native (PPSA) entitlements: this Library is the PS Cloud tab, not PSNow,
	 * so PS3/PS4-only titles don't belong here. This also happens to filter out legacy PS4 media
	 * apps (e.g. BT Sport) that share the same entitlement shape as a real game and were never
	 * issued a PS5-native upgrade entitlement the way real cross-gen games were.
	 *
	 * PS5-native media apps (Netflix, YouTube, Disney+, Prime Video, Twitch, Apple TV, etc.) are
	 * excluded via package_type == "PSMEDIA" — Sony does classify these separately from games
	 * (PSGD/PS4GD), just not under a field we were previously reading.
	 */
	fun buildOwnedGamesFromEntitlements(filteredEntitlements: List<Entitlement>): List<CloudGame>
	{
		// PSTRACK entitlements are Sony-issued analytics/tracking placeholders that ride along with
		// a real purchase under the same product_id — not a separately streamable product. They
		// carry featureType=3 like a real game, so they'd otherwise show up as a duplicate row for
		// whatever real game they're attached to (confirmed against Mortal Kombat 11, DIRT 5,
		// Immortals: Fenyx Rising).
		val gameEntitlements = filteredEntitlements.filterNot {
			it.packageType.equals("PSMEDIA", ignoreCase = true) ||
				it.packageType.equals("PSTRACK", ignoreCase = true) ||
				isDigitalExtra(it.name)
		}

		// Resolve each entitlement's own streaming identifier first, then group by THAT — not by
		// the raw entitlement product_id. Grouping by product_id is unsafe: Sony sometimes assigns
		// the same product_id to a bundle of otherwise-unrelated games (e.g. a "Resident Evil"
		// cross-buy promo covering RE2 Remake, RE3 Remake, and Resistance all under one shared
		// product_id), which would wrongly merge distinct games down into a single row. A PS4
		// disc entitlement and its PS5-native counterpart for the *same* game don't have this
		// problem: the disc entitlement's own id is never PS5-native, so it's simply filtered out
		// below rather than needing to be merged with its PS5 counterpart.
		val resolved = gameEntitlements
			.map { it to bestStreamIdentifier(it.id, it.productId) }
			.filter { (_, streamId) -> streamId.contains("PPSA") }

		return resolved.groupBy { (_, streamId) -> streamId }.map { (streamId, group) ->
			val best = group.map { it.first }.maxByOrNull { ownedStreamRank(it) } ?: group.first().first

			CloudGame(
				productId = streamId,
				name = best.name,
				imageUrl = best.iconUrl,
				landscapeImageUrl = best.iconUrl,
				thumbnailUrl = best.iconUrl,
				platform = "ps5",
				serviceType = "pscloud",
				conceptId = best.conceptId,
				isOwned = true,
				entitlementId = best.id,
				storeProductId = best.productId,
				plusCatalog = best.featureType == 1,
				featureType = best.featureType
			)
		}
	}

	// Digital extras (artbooks, soundtracks, bonus-content viewer apps) are sold as their own
	// entitlement — often bundled under the same product_id as the real game (Horizon Zero Dawn
	// Remastered Artbook, Persona 3 Reload Artbook/Soundtrack) or standalone (Square Enix's
	// "Digital Content Viewer") — but Sony gives them the exact same package_type/featureType
	// shape as a real game, so there's no structural field to key off. Name matching is the only
	// signal available, same rationale as the existing demo/trial check below.
	private val DIGITAL_EXTRA_NAME_PATTERNS = listOf(
		Regex("art\\s*book", RegexOption.IGNORE_CASE),
		Regex("^the\\s+art\\s+of\\s+", RegexOption.IGNORE_CASE), // "The Art of Starfield"-style artbook naming
		Regex("soundtrack", RegexOption.IGNORE_CASE),
		Regex("content\\s*viewer", RegexOption.IGNORE_CASE),
	)

	private fun isDigitalExtra(name: String): Boolean = DIGITAL_EXTRA_NAME_PATTERNS.any { it.containsMatchIn(name) }

	/**
	 * Picks the identifier Gaikai actually validates against when an entitlement's own `id` and
	 * `product_id` disagree. `id` wins whenever both are present: confirmed across every observed
	 * mismatch shape — a PS4-purchase entitlement whose product_id is a legacy CUSA SKU but whose
	 * id carries the PS5-native PPSA id for a free upgrade (Nioh 2-style), and also cases where
	 * both look PS5-native but product_id is actually a cross-edition/region bundle SKU that
	 * neither the catalog nor Gaikai recognize, while id is the specific entitlement they do
	 * (RE7 Gold Edition, RE2 Remake — id matches the public catalog's productId exactly; the
	 * bundle product_id does not).
	 */
	private fun bestStreamIdentifier(id: String, productId: String): String
	{
		if (id.isEmpty()) return productId
		return id
	}

	/**
	 * Best-effort art upgrade: entitlements only carry a 512x512 square icon, while the public
	 * catalog has proper cover/landscape box art for most titles. Swap it in when a catalog match
	 * exists, but never require one — a game whose real SKU doesn't line up with the catalog's
	 * (the Witcher 3/Nioh 2/RE7 Gold class of mismatch) simply keeps its entitlement icon rather
	 * than being hidden or needing a hardcoded correction. Also checks the PS Plus supplement
	 * list — some owned titles (e.g. classic Resident Evil 2) only ever appear there, not in the
	 * main browse catalog.
	 */
	fun enrichWithCatalogArt(
		ownedGames: List<CloudGame>,
		catalog: List<CloudGame>,
		supplement: List<CloudGame> = emptyList()
	): List<CloudGame>
	{
		val combined = catalog + supplement
		if (combined.isEmpty()) return ownedGames

		val byProductId = catalogMapFirstWins(combined)
		val byStableKey = buildStableKeyIndex(combined)
		val byConceptId = buildConceptIdIndex(combined)

		return ownedGames.map { game ->
			val stable = productIdStableKey(game.productId)
			val match = byProductId[game.productId]
				?: byProductId[game.entitlementId]
				?: byProductId[game.storeProductId]
				?: stable?.let { byStableKey[it] }
				?: game.conceptId.takeIf { it.isNotEmpty() }?.let { byConceptId[it] }
				?: return@map game

			game.copy(
				imageUrl = match.imageUrl.ifEmpty { game.imageUrl },
				landscapeImageUrl = match.landscapeImageUrl.ifEmpty { game.landscapeImageUrl },
				thumbnailUrl = match.thumbnailUrl.ifEmpty { game.thumbnailUrl }
			)
		}
	}

	/**
	 * Library tile badge state. A confirmed override (from an actual launch attempt — success or
	 * a Gaikai-rejected failure) always wins, since it reflects reality rather than a guess and
	 * should persist until another real attempt changes it. Absent that, a match in Sony's main
	 * browse catalog (streamingSupported=true, by construction — see mergeImagicCategoryIntoMap)
	 * is a confident STREAMABLE signal. Everything else — no match, or a match only in the PS
	 * Plus supplement list (streamingSupported=false, which is sometimes wrong) — is UNKNOWN
	 * rather than an assumed cross; only a real attempt should ever produce NOT_STREAMABLE.
	 */
	fun applyStreamabilityHints(
		games: List<CloudGame>,
		catalog: List<CloudGame>,
		confirmedOverrides: Map<String, Boolean> = emptyMap()
	): List<CloudGame>
	{
		val byProductId = catalogMapFirstWins(catalog)
		val byStableKey = buildStableKeyIndex(catalog)
		val byConceptId = buildConceptIdIndex(catalog)

		return games.map { game ->
			val status = when (confirmedOverrides[game.productId])
			{
				true -> StreamableStatus.STREAMABLE
				false -> StreamableStatus.NOT_STREAMABLE
				null ->
				{
					val stable = productIdStableKey(game.productId)
					val catalogMatch = byProductId[game.productId]
						?: byProductId[game.entitlementId]
						?: byProductId[game.storeProductId]
						?: stable?.let { byStableKey[it] }
						?: game.conceptId.takeIf { it.isNotEmpty() }?.let { byConceptId[it] }
					if (catalogMatch != null) StreamableStatus.STREAMABLE else StreamableStatus.UNKNOWN
				}
			}
			game.copy(streamableStatus = status)
		}
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
			val src = if (meta.plusCatalog) "supplement" else "browse"
			Log.i(TAG, "crossRef match [$src]: '${meta.name}' featureType=${ent.featureType} skuType=${ent.skuType} packageType=${ent.packageType} entPid=${ent.productId} entId=${ent.id} catalogPid=${meta.productId}")
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
				// Supplement branches are restricted to featureType=3 (full game owned).
				// featureType=1 subscription access (EA Play, Ubisoft+ Classics, etc.) must not
				// match supplement entries — those games are not streamable via PS Cloud for
				// subscribers, only for outright owners.
				stable != null && !skipStableDemo && ent.featureType == 3 && supplementStableKey.containsKey(stable) ->
					supplementStableKey[stable]
				entStable != null && !skipStableDemo && ent.featureType == 3 && supplementStableKey.containsKey(entStable) ->
					supplementStableKey[entStable]
				// conceptId as fallback: handles entitlements with empty/bare productIds that
				// can't match via stable key (e.g. GoT PS4 purchase → GoT PS5 catalog entry)
				ent.conceptId.isNotEmpty() && browseByConcept.containsKey(ent.conceptId) ->
					browseByConcept[ent.conceptId]
				ent.conceptId.isNotEmpty() && ent.featureType == 3 && supplementByConcept.containsKey(ent.conceptId) ->
					supplementByConcept[ent.conceptId]
				ent.productId.isNotEmpty() && ent.id == ent.productId && ent.featureType == 3
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
				Log.i(TAG, "crossRef miss: '${ent.name}' productId=${ent.productId} conceptId=${ent.conceptId} id=${ent.id} featureType=${ent.featureType}")
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
	// Exception 1: disc-upgrade rescue (featureType=5) uses storeProductId — the catalog holds
	// the disc-upgrade SKU which Gaikai won't stream; the owned full-game edition is correct.
	// Exception 2: regional prefix variant — when storeProductId is identical to productId except
	// for the two-char regional publisher prefix (EP/UP/JP), use storeProductId. Hardcoded catalog
	// entries use EU (EP) productIds; a US user's storeProductId carries UP prefix for the same
	// suffix and that's what Gaikai validates against their PSN entitlement. When PPSA numbers
	// differ (GoT subscription PPSA05031 vs retail PPSA03208), drop(2) won't match so the catalog
	// productId still wins — that is a deliberate SKU preference, not a regional variant.
	// Exception 3: some hardcoded entries (e.g. RE7 Gold Edition) only cross-reference via the
	// entitlement's own id — product_id is a cross-platform bundle SKU with an unrelated PPSA
	// number, so the Exception 2 check on storeProductId never fires. Apply the same regional-
	// prefix-variant check to entitlementId so non-EU owners still get their own regional id
	// instead of the hardcoded EU one.
	fun streamingIdentifier(game: CloudGame): String
	{
		if (game.serviceType.equals("pscloud", ignoreCase = true))
		{
			if (game.featureType == 5 &&
				game.storeProductId.isNotEmpty() &&
				game.storeProductId != game.productId)
				return game.storeProductId
			if (game.productId.isNotEmpty())
			{
				if (game.storeProductId.isNotEmpty() &&
					game.storeProductId != game.productId &&
					game.storeProductId.drop(2) == game.productId.drop(2))
					return game.storeProductId
				// PS4 purchase (CUSA) that entitles a free PS5 upgrade (PPSA): entitlementId carries
				// the user's actual regional PPSA, not the hardcoded EU catalog productId.
				if (game.storeProductId.contains("CUSA") && game.entitlementId.contains("PPSA"))
					return game.entitlementId
				if (game.entitlementId.isNotEmpty() &&
					game.entitlementId != game.productId &&
					game.entitlementId.drop(2) == game.productId.drop(2))
					return game.entitlementId
				return game.productId
			}
			if (game.storeProductId.isNotEmpty()) return game.storeProductId
			if (game.entitlementId.isNotEmpty()) return game.entitlementId
		}
		return game.productId
	}

	fun streamPlatform(game: CloudGame): String
	{
		// If the catalog productId and the user's entitlement id are both PPSA (PS5), treat as
		// ps5 even when storeProductId is CUSA. This handles PS4 purchases (CUSA) that entitle a
		// PS5 Remastered upgrade (PPSA) — e.g. Nioh 2 — where storeProductId would otherwise
		// poison the platform detection and send the session down the psnow path instead.
		if (game.productId.contains("PPSA") && game.entitlementId.contains("PPSA")) return "ps5"
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
