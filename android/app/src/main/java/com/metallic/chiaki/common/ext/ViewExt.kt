// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.content.Context
import android.graphics.drawable.GradientDrawable
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
 * The app-wide "this is the selectable thing you're currently on" highlight: a low-alpha
 * theme-accent fill plus a stronger-alpha accent stroke, restoring whatever the view had before
 * (its [View.getBackground] or [View.getForeground], per [useForeground]) once focus is lost —
 * rather than leaving it blank (e.g. an EditText's underline, a row's own ripple/selector).
 *
 * [useForeground] draws the highlight as an overlay instead of replacing the background — needed
 * for MaterialButtons and similar widgets whose background/stroke is already internally managed,
 * where overwriting it directly would fight the widget's own corner radius and outline. Plain
 * widgets (switches, spinners, seek bars, edit texts, plain rows) use the background instead.
 * [shape] defaults to a rectangle; pass [GradientDrawable.OVAL] for circular icon buttons (e.g.
 * the Friends list's compare-trophies button).
 *
 * The single shared implementation behind what used to be five near-identical copies
 * (QuickSettingsPanel, TrophyAdapter, ControllerRemapActivity's RemapAdapter, FriendAdapter,
 * TrophyCompareAdapter) that had quietly drifted apart: every one but QuickSettingsPanel's own
 * hardcoded a raw, non-dp-scaled 2px stroke instead of scaling it by density like this one does,
 * rendering visibly thinner than every other highlighted control on anything denser than mdpi.
 *
 * Applies the correct drawable for the view's CURRENT focus state immediately, not just on future
 * changes — confirmed on-device that a dynamically-added row can already be focused by the time
 * this runs (e.g. the first row of a newly-built tab, auto-focused synchronously during attach,
 * before this call ever gets a chance to attach [onFocusChangeListener]). Without this, that row
 * shows its plain unfocused [original] look indefinitely: nothing ever fires the listener for a
 * focus change that already happened before the listener existed.
 *
 * Also clears any XML-declared background/foreground tint list while focused, restoring it
 * afterward — confirmed on-device that a view with e.g. `android:backgroundTint="@android:color/
 * white"` (item_quick_settings_dropdown.xml's Spinner needs this to keep its own default arrow
 * chrome visible against this dark panel when unfocused) tints THIS drawable too, since
 * `setBackground()`/`setForeground()` don't clear a previously-set tint list — without clearing
 * it here, the pink highlight rendered as a washed-out grey/white instead.
 */
fun View.applyFocusHighlight(color: Int, useForeground: Boolean = false, shape: Int = GradientDrawable.RECTANGLE)
{
	disableDefaultFocusHighlight()
	val fillColor = (0x30 shl 24) or (color and 0x00FFFFFF)
	val strokeColor = (0x99 shl 24) or (color and 0x00FFFFFF)
	val strokeWidthPx = (2f * resources.displayMetrics.density).toInt()
	val original = if(useForeground) foreground else background
	val originalTintList = if(useForeground) foregroundTintList else backgroundTintList
	fun apply(hasFocus: Boolean)
	{
		if(hasFocus)
		{
			val drawable = GradientDrawable().apply {
				this.shape = shape
				setColor(fillColor)
				setStroke(strokeWidthPx, strokeColor)
			}
			if(useForeground) { foreground = drawable; foregroundTintList = null }
			else { background = drawable; backgroundTintList = null }
		}
		else
		{
			if(useForeground) { foreground = original; foregroundTintList = originalTintList }
			else { background = original; backgroundTintList = originalTintList }
		}
	}
	onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> apply(hasFocus) }
	apply(isFocused)
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
