// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.common.Preferences
import com.pylux.stream.R

private const val TV_TITLE_SP = 28f
private const val TV_BODY_SP = 24f
private const val TV_BUTTON_SP = 20f
private const val TV_FOCUS_COLOR = 0x44FFD700.toInt()

fun Context.isTv(): Boolean
{
	val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
	return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * App-wide dialog builder — every dialog in the app should be created via
 * `context.alertDialogBuilder()` (never a bare `MaterialAlertDialogBuilder`) so this styling and
 * the TV enhancements below apply automatically.
 *
 * - Rounded grey box with a theme-accent-coloured border, on the window itself so it wraps the
 *   button row too — originally a one-off applied to just the disclaimer dialog, now the standard
 *   look for every dialog in the app from this single shared entry point. Overriding [create]
 *   rather than [show] covers callers that build via `.create()` then show the result later, not
 *   just ones that call `.show()` directly — [show]'s own default implementation calls [create]
 *   internally, so both paths still only need this one override.
 * - On TV: scales title, message and button text for couch-distance readability, adds a visible
 *   blue overlay on focused buttons for D-pad navigation, and pre-highlights the positive button
 *   so it activates with a single press even when the system is in touch mode (common on TV
 *   emulators).
 */
class AppAlertDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context)
{
	override fun create(): AlertDialog
	{
		val dialog = super.create()
		dialog.window?.setBackgroundDrawableResource(R.drawable.bg_disclaimer_box)
		return dialog
	}

	override fun show(): AlertDialog
	{
		val dialog = super.show()
		if (!context.isTv()) return dialog

		dialog.findViewById<TextView>(android.R.id.message)
			?.setTextSize(TypedValue.COMPLEX_UNIT_SP, TV_BODY_SP)
		dialog.window?.decorView?.findViewById<TextView>(
			androidx.appcompat.R.id.alertTitle
		)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, TV_TITLE_SP)

		val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

		for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
			dialog.getButton(which)?.let { btn ->
				btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, TV_BUTTON_SP)
				btn.isFocusable = true
				btn.isFocusableInTouchMode = true
				btn.setOnFocusChangeListener { v, hasFocus ->
					v.foreground = if (hasFocus) ColorDrawable(TV_FOCUS_COLOR) else null
					if (hasFocus && v != positiveBtn) positiveBtn?.foreground = null
				}
			}
		}

		positiveBtn?.foreground = ColorDrawable(TV_FOCUS_COLOR)
		positiveBtn?.requestFocusFromTouch()

		// dialog.setOnKeyListener { _, keyCode, event ->
		// 	if (event.action == KeyEvent.ACTION_UP &&
		// 		(keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
		// 		positiveBtn != null && !positiveBtn.isFocused)
		// 	{
		// 		positiveBtn.performClick()
		// 		true
		// 	} else false
		// }

		return dialog
	}
}

/**
 * Convenience entry-point: every dialog in the app should be created via
 * `context.alertDialogBuilder()` so TV enhancements are applied automatically.
 */
fun Context.alertDialogBuilder(): AppAlertDialogBuilder = AppAlertDialogBuilder(this)

/** Single shared "are you sure you want to log out" flow — originally only reachable from
 *  Settings, now also triggered from the main screen's account icon dropdown. Both callers get
 *  identical copy and identical state clearing this way, rather than two copies drifting apart. */
fun Context.showPsnLogoutConfirmation(preferences: Preferences, onLoggedOut: () -> Unit = {})
{
	alertDialogBuilder()
		.setTitle(R.string.preferences_psn_logout_title)
		.setMessage(R.string.preferences_psn_logout_message)
		.setPositiveButton(R.string.preferences_psn_logout_confirm) { _, _ ->
			preferences.clearNpssoToken()
			preferences.psnAuthToken = ""
			preferences.psnRefreshToken = ""
			preferences.psnAuthTokenExpiry = 0L
			preferences.psnAccountId = ""
			preferences.psnAvatarUrl = ""
			Toast.makeText(this, R.string.preferences_psn_logout_success, Toast.LENGTH_SHORT).show()
			onLoggedOut()
		}
		.setNegativeButton(R.string.action_cancel, null)
		.show()
}
