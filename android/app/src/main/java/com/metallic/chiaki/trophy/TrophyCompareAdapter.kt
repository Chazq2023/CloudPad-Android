// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.metallic.chiaki.common.ext.disableDefaultFocusHighlight
import com.metallic.chiaki.common.ext.redirectDpadUpAtListBoundary
import com.pylux.stream.R
import com.pylux.stream.databinding.ItemTrophyCompareGameBinding

/** Shared by [com.metallic.chiaki.friends.TrophyCompareActivity] and
 *  [com.metallic.chiaki.stream.QuickSettingsPanel]'s inline Trophy Compare sub-view. */
class TrophyCompareAdapter(
	/** Only supplied by TrophyCompareActivity — its toolbar back button is otherwise
	 *  unreachable by D-pad from the first row (see
	 *  [com.metallic.chiaki.common.ext.redirectDpadUpAtListBoundary]). Left null for
	 *  QuickSettingsPanel's inline copy, which has no such button to escape to. */
	private val onTopBoundary: (() -> Unit)? = null
) : RecyclerView.Adapter<TrophyCompareAdapter.GameViewHolder>()
{
	var items: List<SharedGameComparison> = emptyList()
		set(value) { field = value; notifyDataSetChanged() }

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder
	{
		val binding = ItemTrophyCompareGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)

		// Focusable unconditionally (not gated to TV mode) so D-pad/keyboard navigation through
		// the list works on phone/tablet too, matching TrophyAdapter/FriendAdapter's row handling
		// — this was missing entirely, which is why the list could only be scrolled by touch.
		binding.root.isFocusable = true
		binding.root.isFocusableInTouchMode = true
		binding.root.disableDefaultFocusHighlight()
		onTopBoundary?.let { boundary -> binding.root.redirectDpadUpAtListBoundary(boundary) }

		val accent = resolvePyluxAccent(binding.root.context)
		binding.root.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
			v.background = if (hasFocus)
				GradientDrawable().apply {
					shape = GradientDrawable.RECTANGLE
					setColor((0x30 shl 24) or (accent and 0x00FFFFFF))
					setStroke(2, (0x99 shl 24) or (accent and 0x00FFFFFF))
				}
			else
				null
		}

		return GameViewHolder(binding)
	}

	override fun onBindViewHolder(holder: GameViewHolder, position: Int) = holder.bind(items[position])

	override fun getItemCount(): Int = items.size

	class GameViewHolder(private val binding: ItemTrophyCompareGameBinding) : RecyclerView.ViewHolder(binding.root)
	{
		fun bind(game: SharedGameComparison)
		{
			val context = binding.root.context

			binding.trophyCompareGameName.text = game.gameName
			binding.trophyCompareGamePlatform.text = game.platform

			if (game.gameIconUrl.isNotEmpty()) binding.trophyCompareGameIcon.load(game.gameIconUrl) { crossfade(true) }
			else binding.trophyCompareGameIcon.setImageResource(android.R.drawable.ic_menu_gallery)

			binding.trophyCompareGameMyBar.progress = game.myProgressPercent
			binding.trophyCompareGameTheirBar.progress = game.theirProgressPercent
			binding.trophyCompareGameMyLabel.text = context.getString(R.string.trophy_compare_my_progress, game.myProgressPercent)
			binding.trophyCompareGameTheirLabel.text = context.getString(R.string.trophy_compare_their_progress, game.theirProgressPercent)

			val accent = resolvePyluxAccent(context)
			when
			{
				game.myProgressPercent > game.theirProgressPercent -> bindLeadTag(
					R.string.trophy_compare_you_ahead, accent, (0x30 shl 24) or (accent and 0x00FFFFFF)
				)
				game.theirProgressPercent > game.myProgressPercent -> bindLeadTag(
					R.string.trophy_compare_them_ahead, 0xFFB3B3B3.toInt(), 0x14FFFFFF
				)
				else -> bindLeadTag(R.string.trophy_compare_tied, 0xFFB3B3B3.toInt(), 0x14FFFFFF)
			}
		}

		private fun bindLeadTag(textRes: Int, textColor: Int, backgroundColor: Int)
		{
			binding.trophyCompareGameLeadTag.text = binding.root.context.getString(textRes)
			binding.trophyCompareGameLeadTag.setTextColor(textColor)
			binding.trophyCompareGameLeadTag.background = GradientDrawable().apply {
				shape = GradientDrawable.RECTANGLE
				cornerRadius = 999f
				setColor(backgroundColor)
			}
		}
	}
}
