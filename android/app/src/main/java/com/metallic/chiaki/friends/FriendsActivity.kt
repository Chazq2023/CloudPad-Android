// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.InstantScrollLinearLayoutManager
import com.metallic.chiaki.common.ext.addMarginEnd
import com.metallic.chiaki.common.ext.redirectDpadDownTo
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityFriendsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Account-level PSN friends list — unlike [com.metallic.chiaki.trophy.TrophiesActivity], not
 *  launched from a specific game card, so it's reachable straight from MainActivity's toolbar. */
class FriendsActivity : AppCompatActivity()
{
	companion object
	{
		fun start(context: Context)
		{
			context.startActivity(Intent(context, FriendsActivity::class.java))
		}
	}

	private lateinit var binding: ActivityFriendsBinding
	private lateinit var repository: FriendsRepository
	private val adapter = FriendAdapter(
		onFriendClick = { friend -> FriendChatActivity.start(this, friend) },
		onCompareTrophiesClick = { friend -> TrophyCompareActivity.start(this, friend) },
		onTopBoundary = {
			binding.backButton.isFocusableInTouchMode = true
			binding.backButton.requestFocus()
		}
	)

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		binding = ActivityFriendsBinding.inflate(layoutInflater)
		setContentView(binding.root)

		// Same rationale as TrophiesActivity: every D-pad focus move onto a new friend row would
		// otherwise trigger an IME restart, a known cost for lists of focusable non-editable rows.
		window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

		setSupportActionBar(binding.toolbar)
		binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
		binding.backButton.redirectDpadDownTo { firstFriendRow() }

		repository = FriendsRepository(prefs)

		binding.friendsRecyclerView.layoutManager = InstantScrollLinearLayoutManager(this)
		binding.friendsRecyclerView.adapter = adapter
		binding.friendsRecyclerView.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
		binding.friendsRecyclerView.setItemViewCacheSize(20)

		loadFriends()
	}

	override fun onResume()
	{
		super.onResume()
		// Presence can go stale quickly while this screen is backgrounded (e.g. returning from a
		// chat) — cheap to just re-fetch (short TTL cache absorbs a same-instant re-entry).
		loadFriends()
	}

	private var loadFriendsJob: Job? = null

	/** onCreate and onResume both call this (onResume always fires right after onCreate on a
	 *  cold launch, and deliberately calls this again on every later resume too — see its own
	 *  comment), so a cold open with no cache yet would otherwise kick off two full concurrent
	 *  fetches. [FriendsRepository]'s cache check only sees whichever request already wrote a
	 *  result back, not one still in flight — so skip starting a second fetch while one is
	 *  already running, rather than relying on that cache to dedupe it. */
	private fun loadFriends(forceRefresh: Boolean = false)
	{
		if (loadFriendsJob?.isActive == true) return

		binding.friendsProgressBar.visibility = View.VISIBLE
		binding.friendsEmptyStateText.visibility = View.GONE
		binding.friendsRecyclerView.visibility = View.GONE

		loadFriendsJob = lifecycleScope.launch {
			when (val result = repository.fetchFriends(forceRefresh))
			{
				is FriendsResult.Success -> showFriends(result.friends)
				is FriendsResult.Error -> showEmptyState(result.message)
			}
		}
	}

	private fun showFriends(friends: List<Friend>)
	{
		binding.friendsProgressBar.visibility = View.GONE

		if (friends.isEmpty())
		{
			showEmptyState(getString(R.string.friends_empty_state))
			return
		}

		adapter.items = friends
		binding.friendsRecyclerView.visibility = View.VISIBLE
	}

	private fun showEmptyState(message: String)
	{
		binding.friendsProgressBar.visibility = View.GONE
		binding.friendsRecyclerView.visibility = View.GONE
		binding.friendsEmptyStateText.text = message
		binding.friendsEmptyStateText.visibility = View.VISIBLE

		// No list means nothing else on screen to carry D-pad focus to backButton/refresh via
		// onTopBoundary/redirectDpadDownTo — land it there directly, same fix as onTopBoundary.
		binding.backButton.isFocusableInTouchMode = true
		binding.backButton.requestFocus()
	}

	/** First row of [binding.friendsRecyclerView], the shared D-pad-down target for both
	 *  backButton and the toolbar's refresh action — see redirectDpadDownTo's doc comment for why
	 *  this needs to be explicit rather than left to the platform's own focus search. */
	private fun firstFriendRow() =
		(binding.friendsRecyclerView.layoutManager as? LinearLayoutManager)?.findViewByPosition(0)

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.menu_friends, menu)
		// The toolbar's default menu-icon tint doesn't pick up ic_refresh's own hardcoded fill
		// colour, rendering it dark against this dark toolbar — force white explicitly.
		menu.findItem(R.id.action_refresh_friends)?.icon?.mutate()?.setTint(android.graphics.Color.WHITE)
		// Same D-pad-down gap as backButton — this view isn't created until the toolbar lays out
		// the inflated menu, so grabbing it has to wait a frame past inflate() returning.
		binding.toolbar.post {
			val refreshButton = binding.toolbar.findViewById<View>(R.id.action_refresh_friends)
			// Same focus ring as backButton — an auto-generated ActionMenuItemView gets none of
			// the app's own focus-highlight styling by default.
			refreshButton?.foreground = ContextCompat.getDrawable(this, R.drawable.bg_focus_highlight)
			// Brings it in line with the trophy-icon column in the row below — confirmed on-device.
			// Nudges the ActionMenuView container itself, not the button — ActionMenuView's own
			// onLayout() ignores margins on its individual item children entirely (confirmed
			// on-device: setting marginEnd directly on the button changed its LayoutParams but its
			// laid-out bounds never moved), but Toolbar's own layout pass does respect margins on
			// its direct children, of which ActionMenuView (the refresh icon's actual parent) is one.
			(refreshButton?.parent as? View)?.addMarginEnd(9f)
			refreshButton?.redirectDpadDownTo { firstFriendRow() }
		}
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId)
	{
		R.id.action_refresh_friends -> { loadFriends(forceRefresh = true); true }
		else -> super.onOptionsItemSelected(item)
	}

	/** Triangle/Y as a controller shortcut for the refresh button — same convention MainActivity
	 *  already uses for CloudPlayFragment's own refresh. Circle/B mirrors the back button, the
	 *  same equivalence QuickSettingsPanel already treats KEYCODE_BACK/KEYCODE_BUTTON_B as. */
	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if (event.action == KeyEvent.ACTION_DOWN)
		{
			when (event.keyCode)
			{
				KeyEvent.KEYCODE_BUTTON_Y -> { loadFriends(forceRefresh = true); return true }
				KeyEvent.KEYCODE_BUTTON_B -> { onBackPressedDispatcher.onBackPressed(); return true }
			}
		}
		return super.dispatchKeyEvent(event)
	}
}
