// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.metallic.chiaki.common.ext.disableDefaultFocusHighlight
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

/** Shared by [TrophiesActivity] and [QuickSettingsPanel]'s in-stream Trophies tab so both
 *  present identical group-header + trophy-row structure from the same fetched detail. */
fun buildTrophyListItems(detail: TrophyTitleDetail): List<TrophyListItem>
{
	val items = mutableListOf<TrophyListItem>()
	if (detail.groups.isEmpty())
	{
		detail.trophies.forEach { items.add(TrophyListItem.TrophyRow(it)) }
	}
	else
	{
		// Sony's API frequently returns an empty trophyGroupName for more than one group (not
		// just the base game group), all of which fall back to the generic "Trophies" label
		// below — only the first such fallback gets its own header so it doesn't repeat further
		// down the list; later empty-named groups' trophies fold under that same header.
		var fallbackHeaderShown = false
		detail.groups.forEach { group ->
			if (group.groupName.isNotEmpty())
			{
				items.add(TrophyListItem.GroupHeader(group.groupName))
			}
			else if (!fallbackHeaderShown)
			{
				items.add(TrophyListItem.GroupHeader("Trophies"))
				fallbackHeaderShown = true
			}
			detail.trophies.filter { it.groupId == group.groupId }
				.forEach { items.add(TrophyListItem.TrophyRow(it)) }
		}
	}
	return items
}

class TrophyAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>()
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
			itemView.disableDefaultFocusHighlight()

			val tv = TypedValue()
			itemView.context.theme.resolveAttribute(R.attr.pyluxAccent, tv, true)
			val accent = tv.data
			itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
				v.background = if (hasFocus)
					GradientDrawable().apply {
						shape = GradientDrawable.RECTANGLE
						setColor((0x30 shl 24) or (accent and 0x00FFFFFF))
						setStroke(2, (0x99 shl 24) or (accent and 0x00FFFFFF))
					}
				else
					null
			}
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
		fun bind(trophy: Trophy)
		{
			val isHiddenLocked = trophy.hidden && !trophy.earned

			binding.trophyItemName.text = if (isHiddenLocked) "Hidden Trophy" else trophy.name
			binding.trophyItemDetail.text = if (isHiddenLocked)
				"Complete this trophy to reveal its details"
			else
				trophy.detail

			binding.trophyItemTypeBadge.text = trophy.type.name
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
				binding.trophyItemEarnedDate.text = "Earned ${format.format(Date(trophy.earnedDateTimeMs))}"
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
