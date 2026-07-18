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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.metallic.chiaki.common.Preferences
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityFriendsBinding
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
		onCompareTrophiesClick = { friend -> TrophyCompareActivity.start(this, friend) }
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
		supportActionBar?.setDisplayHomeAsUpEnabled(true)

		repository = FriendsRepository(prefs)

		binding.friendsRecyclerView.layoutManager = LinearLayoutManager(this)
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

	private fun loadFriends(forceRefresh: Boolean = false)
	{
		binding.friendsProgressBar.visibility = View.VISIBLE
		binding.friendsEmptyStateText.visibility = View.GONE
		binding.friendsRecyclerView.visibility = View.GONE

		lifecycleScope.launch {
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
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.menu_friends, menu)
		// The toolbar's default menu-icon tint doesn't pick up ic_refresh's own hardcoded fill
		// colour, rendering it dark against this dark toolbar — force white explicitly.
		menu.findItem(R.id.action_refresh_friends)?.icon?.mutate()?.setTint(android.graphics.Color.WHITE)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId)
	{
		android.R.id.home -> { finish(); true }
		R.id.action_refresh_friends -> { loadFriends(forceRefresh = true); true }
		else -> super.onOptionsItemSelected(item)
	}

	/** Triangle/Y as a controller shortcut for the refresh button — same convention MainActivity
	 *  already uses for CloudPlayFragment's own refresh. */
	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_Y)
		{
			loadFriends(forceRefresh = true)
			return true
		}
		return super.dispatchKeyEvent(event)
	}
}
