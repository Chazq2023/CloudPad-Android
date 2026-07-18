// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.metallic.chiaki.common.ext.disableDefaultFocusHighlight
import com.pylux.stream.R
import com.pylux.stream.databinding.ItemFriendBinding

/** Shared by [FriendsActivity] and [com.metallic.chiaki.stream.QuickSettingsPanel]'s in-stream
 *  Friends tab so both present an identical friends list from the same fetched data. */
class FriendAdapter(
	private val onFriendClick: (Friend) -> Unit,
	private val onCompareTrophiesClick: (Friend) -> Unit
) : RecyclerView.Adapter<FriendAdapter.FriendViewHolder>()
{
	companion object
	{
		private const val ONLINE_COLOR = 0xFF4CAF50.toInt()
		private const val OFFLINE_COLOR = 0xFFB3B3B3.toInt()
	}

	var items: List<Friend> = emptyList()
		set(value) { field = value; notifyDataSetChanged() }

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder
	{
		val binding = ItemFriendBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		val holder = FriendViewHolder(binding)

		// Focusable unconditionally (not gated to TV mode) so D-pad/keyboard navigation through
		// the friends list works on phone/tablet too, matching TrophyAdapter's row focus handling.
		// Targets friendItemContent, not binding.root — the row's own focus/click target needs to
		// be sized to just its own content, not the trophy button's area too. A focusable parent
		// whose bounds fully contain a focusable child confuses D-pad directional focus search
		// (the button was technically reachable but arrow keys couldn't reliably land on it,
		// since it wasn't beside the row's focus rectangle but entirely inside it).
		binding.friendItemContent.isFocusable = true
		binding.friendItemContent.isFocusableInTouchMode = true
		binding.friendItemContent.disableDefaultFocusHighlight()
		binding.friendItemCompareTrophiesButton.isFocusable = true
		binding.friendItemCompareTrophiesButton.isFocusableInTouchMode = true
		binding.friendItemCompareTrophiesButton.disableDefaultFocusHighlight()

		val tv = TypedValue()
		binding.root.context.theme.resolveAttribute(R.attr.pyluxAccent, tv, true)
		val accent = tv.data
		val fillColor = (0x30 shl 24) or (accent and 0x00FFFFFF)
		val strokeColor = (0x99 shl 24) or (accent and 0x00FFFFFF)
		binding.friendItemContent.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
			v.background = if (hasFocus)
				GradientDrawable().apply {
					shape = GradientDrawable.RECTANGLE
					setColor(fillColor)
					setStroke(2, strokeColor)
				}
			else
				null
		}
		binding.friendItemCompareTrophiesButton.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
			v.foreground = if (hasFocus)
				GradientDrawable().apply {
					shape = GradientDrawable.OVAL
					setColor(fillColor)
					setStroke(2, strokeColor)
				}
			else
				null
		}
		binding.friendItemContent.setOnClickListener {
			holder.items()?.let { onFriendClick(it) }
		}
		binding.friendItemCompareTrophiesButton.setOnClickListener {
			holder.items()?.let { onCompareTrophiesClick(it) }
		}

		return holder
	}

	override fun onBindViewHolder(holder: FriendViewHolder, position: Int) = holder.bind(items[position])

	override fun getItemCount(): Int = items.size

	inner class FriendViewHolder(private val binding: ItemFriendBinding) : RecyclerView.ViewHolder(binding.root)
	{
		private var friend: Friend? = null
		fun items(): Friend? = friend

		fun bind(friend: Friend)
		{
			this.friend = friend

			binding.friendItemOnlineId.text = friend.onlineId
			binding.friendItemStatus.text = when
			{
				friend.currentGame.isNotEmpty() -> "Playing ${friend.currentGame}"
				friend.isOnline -> "Online"
				friend.lastOnlineDateMs != null -> "Last online " + DateUtils.getRelativeTimeSpanString(
					friend.lastOnlineDateMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
				)
				else -> "Offline"
			}
			binding.friendItemStatus.setTextColor(if (friend.isOnline) ONLINE_COLOR else OFFLINE_COLOR)
			binding.friendItemStatusDot.setBackgroundResource(
				if (friend.isOnline) R.drawable.bg_friend_online_dot else R.drawable.bg_friend_offline_dot
			)

			if (friend.avatarUrl.isNotEmpty())
			{
				binding.friendItemAvatar.load(friend.avatarUrl) { crossfade(true) }
			}
			else
			{
				binding.friendItemAvatar.setImageResource(android.R.drawable.ic_menu_gallery)
			}
		}
	}
}
