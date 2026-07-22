// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

/**
 * Recursively sets [View.isFocusableInTouchMode] = true on all focusable descendants.
 * **TV / Leanback only:** on phones/tablets this is a no-op. Enabling touch-mode focus on
 * handhelds makes the first tap only *focus* the control (highlight) and the second tap
 * activate it — bad for normal touch UI.
 */
fun View.enableFocusableInTouchModeForTv(context: Context)
{
	if (!context.isTv()) return
	if (this is ViewGroup) {
		for (i in 0 until childCount) {
			getChildAt(i).enableFocusableInTouchModeForTv(context)
		}
	}
	if (isFocusable) {
		isFocusableInTouchMode = true
	}
}

/**
 * Suppresses the platform's own automatic focus highlight (a system-drawn glow/scale effect
 * added in API 26, layered on top of whatever a view already draws for focus). Custom row/button
 * focus highlights in this app draw their own — without this they show doubled: the intended
 * theme-coloured one plus a separate translucent system one behind/around it.
 */
fun View.disableDefaultFocusHighlight()
{
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		defaultFocusHighlightEnabled = false
	}
}

/**
 * Explicitly redirects D-pad UP to [onBoundary] once this focused row has nothing further to
 * focus above it within its own scrollable list, instead of relying on the platform's own
 * cross-container focus search to escape to a sibling control outside the list (e.g. a toolbar
 * back button). Confirmed on-device (see [redirectDpadDownTo]'s doc comment for the fuller
 * picture): that default escape doesn't happen at all in this app's toolbar-over-content screens
 * — [View.focusSearch] simply returns null once it runs out of candidates inside the RecyclerView,
 * rather than continuing the search into the toolbar above it, in either direction.
 *
 * [onBoundary] is expected to move focus onto the target itself (typically a
 * `isFocusableInTouchMode = true; requestFocus()` pair, same as QuickSettingsPanel's own back
 * buttons) — confirmed on-device that requestFocus() alone silently fails here even though the
 * target is focusable and shown, because the device is still in touch mode at this point and the
 * target isn't focusableInTouchMode. Every other key, and ordinary row-to-row movement (where the
 * platform's own search already finds a real candidate), is left untouched.
 */
fun View.redirectDpadUpAtListBoundary(onBoundary: () -> Unit)
{
	setOnKeyListener { v, keyCode, event ->
		if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_UP)
			return@setOnKeyListener false
		val next = v.focusSearch(View.FOCUS_UP)
		if (next == null || next == v) { onBoundary(); true } else false
	}
}

/**
 * Explicitly redirects D-pad DOWN on this view (typically a toolbar back button) to whatever
 * [target] returns, instead of relying on the platform's own focus search to reach content below
 * it. Confirmed on-device: from a plain toolbar button that isn't inside any RecyclerView,
 * `view.focusSearch(View.FOCUS_DOWN)` returns null outright rather than finding the list below —
 * this app's toolbar-over-content screens don't cross that boundary by default in either
 * direction (see [redirectDpadUpAtListBoundary], the mirror-image fix for the opposite direction
 * and the RecyclerView-side half of the same underlying gap).
 *
 * [target] is called fresh on every DOWN press (not resolved once) since the intended row may not
 * exist yet the first time (data still loading) — return null in that case to no-op. The returned
 * view is expected to already be focusable in touch mode (every row adapter in this app already
 * sets that unconditionally at bind time) — if it isn't, requestFocus() here will silently fail
 * the same way it did for [redirectDpadUpAtListBoundary]'s target before that got the same flag.
 */
fun View.redirectDpadDownTo(target: () -> View?)
{
	setOnKeyListener { _, keyCode, event ->
		if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN)
			return@setOnKeyListener false
		target()?.requestFocus()
		true
	}
}

/**
 * Nudges this view [extraDp] further in from its current end edge. Used to bring a toolbar's
 * auto-generated refresh action into the same horizontal position as the trophy-icon column in
 * the list below it — confirmed on-device that `app:contentInsetEndWithActions` on the Toolbar
 * has no effect on where AppCompat places `ActionMenuView`'s items in this app's toolbars (the
 * measured bounds were pixel-identical with and without it), so the fix has to reach past that
 * and adjust the action view's own margin directly instead.
 */
fun View.addMarginEnd(extraDp: Float)
{
	val extraPx = (extraDp * resources.displayMetrics.density).toInt()
	(layoutParams as? ViewGroup.MarginLayoutParams)?.let {
		it.marginEnd += extraPx
		layoutParams = it
	}
}
