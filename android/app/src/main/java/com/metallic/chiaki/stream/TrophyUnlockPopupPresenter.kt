// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import com.metallic.chiaki.trophy.model.Trophy
import com.metallic.chiaki.trophy.model.TrophyType
import com.pylux.stream.R

/**
 * Shows the in-stream trophy-unlock popup one trophy at a time, queueing any additional
 * trophies detected while one is already on screen. Each popup stays up for
 * [DISPLAY_DURATION_MS] then either advances to the next queued trophy or hides.
 */
class TrophyUnlockPopupPresenter(
	private val container: View,
	private val iconView: ImageView,
	private val textView: TextView,
	private val detailView: TextView,
	private val badgeView: TextView
)
{
	companion object
	{
		private const val DISPLAY_DURATION_MS = 10_000L
	}

	private val handler = Handler(Looper.getMainLooper())
	private val queue = ArrayDeque<Trophy>()
	private val hideRunnable = Runnable { showNextOrHide() }

	fun enqueue(trophies: List<Trophy>)
	{
		val wasIdle = queue.isEmpty() && container.visibility != View.VISIBLE
		queue.addAll(trophies)
		if (wasIdle) showNextOrHide()
	}

	private fun showNextOrHide()
	{
		handler.removeCallbacks(hideRunnable)
		val next = queue.removeFirstOrNull()
		if (next == null)
		{
			container.visibility = View.GONE
			return
		}

		textView.text = next.name
		detailView.text = next.detail
		if (next.iconUrl.isNotEmpty())
			iconView.load(next.iconUrl) { crossfade(true) }
		else
			iconView.setImageResource(android.R.drawable.ic_menu_gallery)

		badgeView.text = next.type.name
		badgeView.setBackgroundResource(
			when (next.type)
			{
				TrophyType.BRONZE -> R.drawable.bg_trophy_bronze
				TrophyType.SILVER -> R.drawable.bg_trophy_silver
				TrophyType.GOLD -> R.drawable.bg_trophy_gold
				TrophyType.PLATINUM -> R.drawable.bg_trophy_platinum
			}
		)

		container.visibility = View.VISIBLE
		handler.postDelayed(hideRunnable, DISPLAY_DURATION_MS)
	}

	/** Stops any pending auto-advance/hide and clears the queue — used when the stream
	 *  disconnects so a delayed callback doesn't fire against a torn-down session. */
	fun cancel()
	{
		handler.removeCallbacks(hideRunnable)
		queue.clear()
		container.visibility = View.GONE
	}
}
