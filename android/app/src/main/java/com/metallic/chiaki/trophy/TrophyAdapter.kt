// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.metallic.chiaki.common.ext.applyFocusHighlight
import com.metallic.chiaki.common.ext.redirectDpadUpAtListBoundary
import com.metallic.chiaki.trophy.model.Trophy
import com.metallic.chiaki.trophy.model.TrophyTitleDetail
import com.metallic.chiaki.trophy.model.TrophyType
import com.pylux.stream.R
import com.pylux.stream.databinding.ItemTrophyBinding
import com.pylux.stream.databinding.ItemTrophyGroupHeaderBinding
import java.text.DateFormat
import java.util.Date

sealed class TrophyListItem
{
	data class GroupHeader(val name: String) : TrophyListItem()
	data class TrophyRow(val trophy: Trophy) : TrophyListItem()
}

/** "Default" restores the game's own group ordering; "Earned Date" flattens everything (group
 *  headers stop making sense once trophies are reordered across groups) and pulls every unlocked
 *  trophy to the top, most recent first, with locked trophies left after in their original order. */
enum class TrophySortMode { DEFAULT, EARNED_DATE }

/** "Default" shows every trophy; the rest restrict the list to just that rarity. */
enum class TrophyFilterMode { DEFAULT, BRONZE, SILVER, GOLD, PLATINUM }

/** Shared PopupMenu item-id <-> [TrophyFilterMode] mapping so [TrophiesActivity] and
 *  [com.metallic.chiaki.stream.QuickSettingsPanel]'s filter menus stay in sync. */
fun filterModeToItemId(mode: TrophyFilterMode): Int = when (mode)
{
	TrophyFilterMode.DEFAULT -> 0
	TrophyFilterMode.BRONZE -> 1
	TrophyFilterMode.SILVER -> 2
	TrophyFilterMode.GOLD -> 3
	TrophyFilterMode.PLATINUM -> 4
}

fun itemIdToFilterMode(itemId: Int): TrophyFilterMode = when (itemId)
{
	1 -> TrophyFilterMode.BRONZE
	2 -> TrophyFilterMode.SILVER
	3 -> TrophyFilterMode.GOLD
	4 -> TrophyFilterMode.PLATINUM
	else -> TrophyFilterMode.DEFAULT
}

/** [TrophyType.name] is the raw Kotlin enum constant (always English, e.g. "BRONZE") — used for
 *  the rarity badge's background lookup, but never for display text. This is the translated
 *  equivalent for the badge label. */
fun trophyTypeLabelRes(type: TrophyType): Int = when (type)
{
	TrophyType.BRONZE -> R.string.trophy_filter_bronze
	TrophyType.SILVER -> R.string.trophy_filter_silver
	TrophyType.GOLD -> R.string.trophy_filter_gold
	TrophyType.PLATINUM -> R.string.trophy_filter_platinum
}

/** Shared by [TrophiesActivity] and [QuickSettingsPanel]'s in-stream Trophies tab so both
 *  present identical group-header + trophy-row structure from the same fetched detail, under
 *  whichever sort/filter the user currently has selected. */
fun buildTrophyListItems(
	context: Context,
	detail: TrophyTitleDetail,
	sortMode: TrophySortMode = TrophySortMode.DEFAULT,
	filterMode: TrophyFilterMode = TrophyFilterMode.DEFAULT
): List<TrophyListItem>
{
	val trophies = when (filterMode)
	{
		TrophyFilterMode.DEFAULT -> detail.trophies
		TrophyFilterMode.BRONZE -> detail.trophies.filter { it.type == TrophyType.BRONZE }
		TrophyFilterMode.SILVER -> detail.trophies.filter { it.type == TrophyType.SILVER }
		TrophyFilterMode.GOLD -> detail.trophies.filter { it.type == TrophyType.GOLD }
		TrophyFilterMode.PLATINUM -> detail.trophies.filter { it.type == TrophyType.PLATINUM }
	}

	if (sortMode == TrophySortMode.EARNED_DATE)
	{
		val (earned, locked) = trophies.partition { it.earned }
		val orderedEarned = earned.sortedByDescending { it.earnedDateTimeMs ?: Long.MIN_VALUE }
		return (orderedEarned + locked).map { TrophyListItem.TrophyRow(it) }
	}

	val items = mutableListOf<TrophyListItem>()
	if (detail.groups.isEmpty())
	{
		trophies.forEach { items.add(TrophyListItem.TrophyRow(it)) }
	}
	else
	{
		// Sony's API frequently returns an empty trophyGroupName for more than one group (not
		// just the base game group), all of which fall back to the generic "Trophies" label
		// below — only the first such fallback gets its own header so it doesn't repeat further
		// down the list; later empty-named groups' trophies fold under that same header.
		// A group filtered down to zero trophies is skipped entirely rather than left as a
		// header with nothing under it — but only once a filter is active, so the unfiltered
		// "Default" view's group structure is untouched from before filtering existed.
		var fallbackHeaderShown = false
		detail.groups.forEach { group ->
			val groupTrophies = trophies.filter { it.groupId == group.groupId }
			if (filterMode != TrophyFilterMode.DEFAULT && groupTrophies.isEmpty()) return@forEach

			if (group.groupName.isNotEmpty())
			{
				items.add(TrophyListItem.GroupHeader(group.groupName))
			}
			else if (!fallbackHeaderShown)
			{
				items.add(TrophyListItem.GroupHeader(context.getString(R.string.trophy_group_fallback_header)))
				fallbackHeaderShown = true
			}
			groupTrophies.forEach { items.add(TrophyListItem.TrophyRow(it)) }
		}
	}
	return items
}

/** Shown when a trophy row is tapped in either [TrophiesActivity] or [QuickSettingsPanel]'s
 *  Trophies tab — the row itself truncates long names/descriptions to fit, this shows them in
 *  full, styled the same as the app's other popups (e.g. CloudPlayFragment's playtime dialog). */
fun showTrophyDetailDialog(context: Context, trophy: Trophy)
{
	val isHiddenLocked = trophy.hidden && !trophy.earned
	val view = LayoutInflater.from(context).inflate(R.layout.dialog_trophy_detail, null)

	view.findViewById<TextView>(R.id.trophyDetailName).text =
		if (isHiddenLocked) context.getString(R.string.trophy_hidden_name) else trophy.name

	view.findViewById<TextView>(R.id.trophyDetailDescription).text = if (isHiddenLocked)
		context.getString(R.string.trophy_hidden_description)
	else
		trophy.detail

	val typeBadge = view.findViewById<TextView>(R.id.trophyDetailTypeBadge)
	typeBadge.text = context.getString(trophyTypeLabelRes(trophy.type))
	typeBadge.setBackgroundResource(
		when (trophy.type)
		{
			TrophyType.BRONZE -> R.drawable.bg_trophy_bronze
			TrophyType.SILVER -> R.drawable.bg_trophy_silver
			TrophyType.GOLD -> R.drawable.bg_trophy_gold
			TrophyType.PLATINUM -> R.drawable.bg_trophy_platinum
		}
	)

	view.findViewById<View>(R.id.trophyDetailLockBadge).visibility =
		if (trophy.earned) View.GONE else View.VISIBLE

	val earnedDateText = view.findViewById<TextView>(R.id.trophyDetailEarnedDate)
	if (trophy.earned && trophy.earnedDateTimeMs != null)
	{
		earnedDateText.visibility = View.VISIBLE
		val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
		earnedDateText.text = context.getString(R.string.trophy_earned_date_format, format.format(Date(trophy.earnedDateTimeMs)))
	}
	else
	{
		earnedDateText.visibility = View.GONE
	}

	val iconUrl = if (isHiddenLocked) "" else trophy.iconUrl
	val iconView = view.findViewById<ImageView>(R.id.trophyDetailIcon)
	if (iconUrl.isNotEmpty())
	{
		iconView.load(iconUrl) { crossfade(true) }
	}
	else
	{
		iconView.setImageResource(android.R.drawable.ic_menu_gallery)
	}

	context.alertDialogBuilder()
		.setView(view)
		.setPositiveButton(R.string.quick_settings_close, null)
		.show()
}

class TrophyAdapter(
	private val onTrophyClick: (Trophy) -> Unit = {},
	/** Only supplied by [TrophiesActivity] — its toolbar back button is otherwise unreachable by
	 *  D-pad past the group header sitting above the first row (see
	 *  [com.metallic.chiaki.common.ext.redirectDpadUpAtListBoundary]). Left null for
	 *  [QuickSettingsPanel]'s Trophies tab, which has no such button to escape to. */
	private val onTopBoundary: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>()
{
	companion object
	{
		private const val VIEW_TYPE_HEADER = 0
		private const val VIEW_TYPE_TROPHY = 1
	}

	var items: List<TrophyListItem> = emptyList()
		set(value) { field = value; notifyDataSetChanged() }

	override fun getItemViewType(position: Int): Int = when (items[position])
	{
		is TrophyListItem.GroupHeader -> VIEW_TYPE_HEADER
		is TrophyListItem.TrophyRow -> VIEW_TYPE_TROPHY
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType)
	{
		VIEW_TYPE_HEADER -> HeaderViewHolder(
			ItemTrophyGroupHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		)
		else -> TrophyViewHolder(
			ItemTrophyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		).apply {
			// Focusable unconditionally (not gated to TV mode) so D-pad/keyboard navigation
			// through the trophy list works on phone/tablet too, not just Android TV.
			itemView.isFocusable = true
			itemView.isFocusableInTouchMode = true
			itemView.setOnClickListener { items()?.let { onTrophyClick(it) } }
			onTopBoundary?.let { boundary -> itemView.redirectDpadUpAtListBoundary(boundary) }

			val tv = TypedValue()
			itemView.context.theme.resolveAttribute(R.attr.pyluxAccent, tv, true)
			itemView.applyFocusHighlight(tv.data)
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int)
	{
		when (val item = items[position])
		{
			is TrophyListItem.GroupHeader -> (holder as HeaderViewHolder).bind(item)
			is TrophyListItem.TrophyRow -> (holder as TrophyViewHolder).bind(item.trophy)
		}
	}

	override fun getItemCount(): Int = items.size

	class HeaderViewHolder(private val binding: ItemTrophyGroupHeaderBinding) : RecyclerView.ViewHolder(binding.root)
	{
		fun bind(header: TrophyListItem.GroupHeader)
		{
			binding.trophyGroupHeaderText.text = header.name
		}
	}

	class TrophyViewHolder(private val binding: ItemTrophyBinding) : RecyclerView.ViewHolder(binding.root)
	{
		private var trophy: Trophy? = null
		fun items(): Trophy? = trophy

		fun bind(trophy: Trophy)
		{
			this.trophy = trophy

			val isHiddenLocked = trophy.hidden && !trophy.earned

			binding.trophyItemName.text = if (isHiddenLocked) binding.root.context.getString(R.string.trophy_hidden_name) else trophy.name
			binding.trophyItemDetail.text = if (isHiddenLocked)
				binding.root.context.getString(R.string.trophy_hidden_description)
			else
				trophy.detail

			binding.trophyItemTypeBadge.text = binding.root.context.getString(trophyTypeLabelRes(trophy.type))
			binding.trophyItemTypeBadge.setBackgroundResource(
				when (trophy.type)
				{
					TrophyType.BRONZE -> R.drawable.bg_trophy_bronze
					TrophyType.SILVER -> R.drawable.bg_trophy_silver
					TrophyType.GOLD -> R.drawable.bg_trophy_gold
					TrophyType.PLATINUM -> R.drawable.bg_trophy_platinum
				}
			)

			binding.trophyItemLockBadge.visibility = if (trophy.earned) View.GONE else View.VISIBLE
			binding.root.alpha = if (trophy.earned) 1.0f else 0.55f

			if (trophy.earned && trophy.earnedDateTimeMs != null)
			{
				binding.trophyItemEarnedDate.visibility = View.VISIBLE
				val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
				binding.trophyItemEarnedDate.text = binding.root.context.getString(R.string.trophy_earned_date_format, format.format(Date(trophy.earnedDateTimeMs)))
			}
			else
			{
				binding.trophyItemEarnedDate.visibility = View.GONE
			}

			val iconUrl = if (isHiddenLocked) "" else trophy.iconUrl
			if (iconUrl.isNotEmpty())
			{
				binding.trophyItemIcon.load(iconUrl) { crossfade(true) }
			}
			else
			{
				binding.trophyItemIcon.setImageResource(android.R.drawable.ic_menu_gallery)
			}
		}
	}
}
