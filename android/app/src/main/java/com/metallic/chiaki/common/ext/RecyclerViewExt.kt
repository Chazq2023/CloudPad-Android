// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

private const val TAG = "FastScrollFocusFix"

/**
 * LinearLayoutManager that forces instant (non-smooth) scroll when D-pad focus moves to an
 * off-screen item — the same fix as CloudPlayFragment's private InstantScrollGridLayoutManager,
 * generalised here so the Trophies list and Quick Settings Trophies tab (both plain, single-column
 * LinearLayoutManager lists) get the same treatment: the default smooth-scroll behaviour lets
 * multiple fast D-pad presses queue up, causing the list to overshoot and leave the focused row
 * off-screen.
 */
class InstantScrollLinearLayoutManager(context: Context) : LinearLayoutManager(context)
{
	override fun requestChildRectangleOnScreen(
		parent: RecyclerView,
		child: View,
		rect: Rect,
		immediate: Boolean,
		focusedChildVisible: Boolean
	): Boolean = super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible)
}

/**
 * Fixes D-pad down/up focus reliably getting "stuck" on a fast-scrolling list of focusable rows —
 * originally worked out for the Quick Settings Trophies tab, generalised here since
 * [com.metallic.chiaki.trophy.TrophiesActivity]'s identical full-screen list and the newer Trophy
 * Compare lists needed the exact same treatment.
 *
 * Holding (or repeatedly pressing) D-pad down recycles rows faster than the default view cache
 * retains them; when the currently-focused row gets recycled mid-scroll, the platform's own
 * (synchronous) focus-restoration searches the *whole window* rather than just this list, and can
 * land on something outside it — or simply fail to find a same-list replacement in time — which
 * is what makes navigation feel like it needs a few extra presses to "catch". Catching the detach
 * and immediately redirecting focus to a still-attached row keeps focus inside the list without
 * touching scroll speed at all.
 *
 * The redirect target is biased toward whichever direction the list was actually scrolling
 * (tracked via [RecyclerView.OnScrollListener]), preferring a candidate at or beyond the detached
 * row's position over the pure-closest one. Rows with more on-screen content (e.g. Trophy
 * Compare's) keep fewer views attached at once during a scroll than a plain single-line list, so
 * it's much likelier the only surviving candidate sits *behind* the detached row rather than
 * ahead of it — picking pure-closest in that case sends focus backward mid-downward-press, which
 * reads as "stuck, takes a few tries" from the user's side even though the redirect itself
 * "succeeded".
 *
 * The redirect only runs as a *fallback* — it checks first whether focus already landed
 * somewhere in the list on its own by the time the posted callback runs, and does nothing if so.
 * Acting unconditionally on every detach was actively fighting the platform's own successful
 * focus-restoration on the (common) case where that already worked: by the next frame, our
 * recompute-and-reassign could snap focus back to a stale position, cancelling out forward
 * progress the platform had already made — live-observed as "tries to move but doesn't" and, at
 * the edge of the visible/cached window where real scroll+bind work is also happening, as
 * navigation slowing down noticeably (both mechanisms doing full focus resolution for one event).
 *
 * [logTag] identifies which list a given log line came from — temporary, kept while confirming
 * this fix actually catches every "stuck" case reported in the field; filter logcat for
 * "FastScrollFocusFix" to see it fire (or not) live. [onFocusedChildDetached] fires on every
 * detach-while-focused event, before the (possibly skipped) redirect — e.g. so a caller can
 * temporarily suppress a nearby button's focusability for the duration of a scroll burst, the way
 * the Quick Settings Trophies tab's own refresh button needs to avoid flickering into focus
 * mid-scroll.
 */
fun RecyclerView.fixFocusOnFastScroll(logTag: String = "", onFocusedChildDetached: (() -> Unit)? = null)
{
	setItemViewCacheSize(20)

	var lastScrollDy = 0
	addOnScrollListener(object : RecyclerView.OnScrollListener()
	{
		override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int)
		{
			if (dy != 0) lastScrollDy = dy
		}
	})

	addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener
	{
		override fun onChildViewAttachedToWindow(view: View) {}
		override fun onChildViewDetachedFromWindow(view: View)
		{
			if (!view.hasFocus())
			{
				Log.d(TAG, "[$logTag] child detached without focus, ignoring")
				return
			}

			onFocusedChildDetached?.invoke()

			// Captured now, before the ViewHolder backing this view gets recycled.
			val lastPosition = getChildViewHolder(view)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
			val scrollDy = lastScrollDy
			Log.d(TAG, "[$logTag] focused child detached at position=$lastPosition (scrollDy=$scrollDy), scheduling fallback check")
			post {
				if (hasFocus())
				{
					Log.d(TAG, "[$logTag] focus already recovered on its own, skipping redirect")
					return@post
				}
				val focusables = ArrayList<View>()
				addFocusables(focusables, View.FOCUS_DOWN)
				val target = pickRedirectTarget(focusables, lastPosition, scrollDy)
				val targetPosition = target?.let { findContainingItemView(it) }?.let { getChildAdapterPosition(it) }
				val gotFocus = target?.requestFocus() ?: false
				Log.d(TAG, "[$logTag] redirect result: candidates=${focusables.size} target=$targetPosition " +
					"requestFocus()=$gotFocus target.isFocused=${target?.isFocused}")
			}
		}
	})
}

private fun RecyclerView.pickRedirectTarget(focusables: List<View>, lastPosition: Int, scrollDy: Int): View?
{
	if (lastPosition == RecyclerView.NO_POSITION) return focusables.firstOrNull()

	// addFocusables() returns every focusable *descendant*, not just direct children of the
	// RecyclerView — getChildAdapterPosition() requires an actual direct child (it reads the
	// view's own LayoutParams as RecyclerView.LayoutParams), so a candidate nested inside an
	// item's view hierarchy has to be resolved up to its containing item view first. Skipping
	// this crashed with a ClassCastException (FrameLayout.LayoutParams cannot be cast to
	// RecyclerView.LayoutParams) the one time a caller's item layout had a focusable descendant
	// deeper than the item root.
	fun positionOf(candidate: View): Int {
		val itemView = findContainingItemView(candidate) ?: return RecyclerView.NO_POSITION
		return getChildAdapterPosition(itemView)
	}

	// Prefer a candidate that continues the direction we were actually scrolling — falls back to
	// the full pool (closest by absolute distance, in either direction) only when nothing exists
	// in that direction, e.g. at the very end of the list.
	val directional = focusables.filter { candidate ->
		val pos = positionOf(candidate)
		when
		{
			pos == RecyclerView.NO_POSITION -> false
			scrollDy > 0 -> pos >= lastPosition
			scrollDy < 0 -> pos <= lastPosition
			else -> true
		}
	}
	val pool = directional.ifEmpty { focusables }
	return pool.minByOrNull { candidate ->
		val pos = positionOf(candidate)
		if (pos == RecyclerView.NO_POSITION) Int.MAX_VALUE else abs(pos - lastPosition)
	}
}
