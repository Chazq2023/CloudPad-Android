// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.KeyEvent
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
	private val onCompareTrophiesClick: (Friend) -> Unit,
	/** Only supplied by [FriendsActivity] — its toolbar back button is otherwise unreachable by
	 *  D-pad from the first row, since RecyclerView.focusSearch() contains arrow-key search to its
	 *  own subtree rather than escaping to a sibling control outside the list (see
	 *  [com.metallic.chiaki.common.ext.redirectDpadUpAtListBoundary] for the general case this is
	 *  a hand-rolled variant of — moveFocusVertically below already has its own column-locked
	 *  redirect logic, so the boundary check is folded in here instead of reusing that helper).
	 *  Left null for [com.metallic.chiaki.stream.QuickSettingsPanel]'s Friends tab, which has no
	 *  such button to escape to. */
	private val onTopBoundary: (() -> Unit)? = null
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

		// Each row has two side-by-side focusable targets (the tile and the compare-trophies
		// button). Left to the platform's default geometric focus search, D-pad up/down can drift
		// from one column to the other while scrolling through recycled rows — the button's much
		// smaller bounds compared to the tile make it an unreliable target for the "nearest in this
		// direction" heuristic once rows are being bound/recycled mid-scroll. Intercepting up/down
		// explicitly and re-requesting focus on the *same* column of the adjacent row keeps
		// navigation column-locked regardless of recycling.
		val recyclerView = parent as RecyclerView
		fun moveFocusVertically(keyCode: Int, event: KeyEvent, sameColumn: (FriendViewHolder) -> View): Boolean
		{
			if (event.action != KeyEvent.ACTION_DOWN) return false
			val direction = when (keyCode)
			{
				KeyEvent.KEYCODE_DPAD_UP -> -1
				KeyEvent.KEYCODE_DPAD_DOWN -> 1
				else -> return false
			}
			val pos = holder.bindingAdapterPosition
			if (pos == RecyclerView.NO_POSITION) return false
			val targetPos = pos + direction
			if (targetPos < 0)
			{
				// Top row, pressed up — nothing left within the list to move to. Redirect to the
				// screen's back button if one was supplied (expected to flip
				// isFocusableInTouchMode = true before requestFocus() — see
				// redirectDpadUpAtListBoundary's doc comment for why requestFocus() alone silently
				// fails here), otherwise fall through to default handling (unchanged from before
				// this existed).
				val boundary = onTopBoundary ?: return false
				boundary()
				return true
			}
			if (targetPos >= items.size) return false

			val existing = recyclerView.findViewHolderForAdapterPosition(targetPos) as? FriendViewHolder
			if (existing != null)
			{
				sameColumn(existing).requestFocus()
			}
			else
			{
				// Target row has scrolled out of the view cache and isn't bound yet — bring it
				// into view first, then focus once it's attached.
				recyclerView.scrollToPosition(targetPos)
				recyclerView.post {
					(recyclerView.findViewHolderForAdapterPosition(targetPos) as? FriendViewHolder)
						?.let { sameColumn(it).requestFocus() }
				}
			}
			return true
		}
		binding.friendItemContent.setOnKeyListener { _, keyCode, event ->
			moveFocusVertically(keyCode, event) { it.contentView }
		}
		binding.friendItemCompareTrophiesButton.setOnKeyListener { _, keyCode, event ->
			moveFocusVertically(keyCode, event) { it.trophyButtonView }
		}

		return holder
	}

	override fun onBindViewHolder(holder: FriendViewHolder, position: Int) = holder.bind(items[position])

	override fun getItemCount(): Int = items.size

	inner class FriendViewHolder(private val binding: ItemFriendBinding) : RecyclerView.ViewHolder(binding.root)
	{
		private var friend: Friend? = null
		fun items(): Friend? = friend

		val contentView: View get() = binding.friendItemContent
		val trophyButtonView: View get() = binding.friendItemCompareTrophiesButton

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
