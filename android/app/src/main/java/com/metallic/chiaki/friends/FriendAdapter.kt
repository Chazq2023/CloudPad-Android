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
import com.pylux.stream.R
import com.pylux.stream.databinding.ItemFriendBinding

/** Shared by [FriendsActivity] and [com.metallic.chiaki.stream.QuickSettingsPanel]'s in-stream
 *  Friends tab so both present an identical friends list from the same fetched data. */
class FriendAdapter(private val onFriendClick: (Friend) -> Unit) : RecyclerView.Adapter<FriendAdapter.FriendViewHolder>()
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
		binding.root.isFocusable = true
		binding.root.isFocusableInTouchMode = true

		val tv = TypedValue()
		binding.root.context.theme.resolveAttribute(R.attr.pyluxAccent, tv, true)
		val accent = tv.data
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
		binding.root.setOnClickListener {
			holder.items()?.let { onFriendClick(it) }
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
