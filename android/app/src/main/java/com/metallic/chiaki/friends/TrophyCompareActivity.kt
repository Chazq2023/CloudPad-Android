// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import com.metallic.chiaki.common.ext.addMarginEnd
import com.metallic.chiaki.common.ext.fixFocusOnFastScroll
import com.metallic.chiaki.common.ext.redirectDpadDownTo
import com.metallic.chiaki.trophy.TrophyCompareAdapter
import com.metallic.chiaki.trophy.TrophyCompareRepository
import com.metallic.chiaki.trophy.TrophyComparisonResult
import com.metallic.chiaki.trophy.TrophyRepository
import com.metallic.chiaki.trophy.bindTrophyCompareHeader
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityTrophyCompareBinding
import kotlinx.coroutines.launch

/** Full-screen trophy comparison against one friend — account level, badge counts, and every
 *  shared game's completion side by side. Reachable from the trophy icon on a friend row in
 *  [FriendsActivity]; the Quick Settings copy of this same view lives inline in
 *  [com.metallic.chiaki.stream.QuickSettingsPanel] instead, for the same reason its chat is
 *  inline rather than a separate Activity. */
class TrophyCompareActivity : AppCompatActivity()
{
	companion object
	{
		private const val EXTRA_ACCOUNT_ID = "extra_account_id"
		private const val EXTRA_ONLINE_ID = "extra_online_id"
		private const val EXTRA_AVATAR_URL = "extra_avatar_url"

		fun start(context: Context, friend: Friend)
		{
			val intent = Intent(context, TrophyCompareActivity::class.java)
			intent.putExtra(EXTRA_ACCOUNT_ID, friend.accountId)
			intent.putExtra(EXTRA_ONLINE_ID, friend.onlineId)
			intent.putExtra(EXTRA_AVATAR_URL, friend.avatarUrl)
			context.startActivity(intent)
		}
	}

	private lateinit var binding: ActivityTrophyCompareBinding
	private lateinit var repository: TrophyCompareRepository
	private val adapter = TrophyCompareAdapter(onTopBoundary = {
		binding.backButton.isFocusableInTouchMode = true
		binding.backButton.requestFocus()
	})
	private var accountId: String = ""
	private var onlineId: String = ""
	private var avatarUrl: String = ""

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		binding = ActivityTrophyCompareBinding.inflate(layoutInflater)
		setContentView(binding.root)

		// Same rationale as TrophiesActivity/FriendsActivity: every D-pad focus move onto a new
		// row otherwise triggers a synchronous IME restart, a known cost for lists of focusable
		// non-editable rows and a contributor to scroll stutter — missed when this screen was
		// first built.
		window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

		setSupportActionBar(binding.toolbar)
		binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
		binding.backButton.redirectDpadDownTo { firstComparisonRow() }

		accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: ""
		onlineId = intent.getStringExtra(EXTRA_ONLINE_ID) ?: ""
		avatarUrl = intent.getStringExtra(EXTRA_AVATAR_URL) ?: ""
		binding.trophyCompareTitleTextView.text = getString(R.string.trophy_compare_title, onlineId)

		repository = TrophyCompareRepository(prefs, TrophyRepository(prefs))

		binding.trophyCompareRecyclerView.layoutManager = LinearLayoutManager(this)
		binding.trophyCompareRecyclerView.adapter = adapter
		binding.trophyCompareRecyclerView.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
		binding.trophyCompareRecyclerView.fixFocusOnFastScroll("TrophyCompareActivity")

		loadComparison()
	}

	private fun loadComparison()
	{
		binding.trophyCompareProgressBar.visibility = View.VISIBLE
		binding.trophyCompareEmptyStateText.visibility = View.GONE
		binding.trophyCompareContentGroup.visibility = View.GONE

		lifecycleScope.launch {
			when (val result = repository.fetchComparison(accountId))
			{
				is TrophyComparisonResult.Success -> showComparison(result)
				is TrophyComparisonResult.Error -> showEmptyState(result.message)
			}
		}
	}

	private fun showComparison(comparison: TrophyComparisonResult.Success)
	{
		binding.trophyCompareProgressBar.visibility = View.GONE

		binding.trophyCompareHeader.bindTrophyCompareHeader(comparison, comparison.myAvatarUrl, theirName = onlineId, theirAvatarUrl = avatarUrl)
		binding.trophyCompareSharedGamesLabel.text =
			getString(R.string.trophy_compare_shared_games, comparison.sharedGames.size)

		if (comparison.sharedGames.isEmpty())
		{
			showEmptyState(getString(R.string.trophy_compare_empty))
			return
		}

		adapter.items = comparison.sharedGames
		binding.trophyCompareEmptyStateText.visibility = View.GONE
		binding.trophyCompareContentGroup.visibility = View.VISIBLE
	}

	private fun showEmptyState(message: String)
	{
		binding.trophyCompareProgressBar.visibility = View.GONE
		binding.trophyCompareContentGroup.visibility = View.GONE
		binding.trophyCompareEmptyStateText.text = message
		binding.trophyCompareEmptyStateText.visibility = View.VISIBLE
	}

	/** First row of [binding.trophyCompareRecyclerView], the shared D-pad-down target for both
	 *  backButton and the toolbar's refresh action — see redirectDpadDownTo's doc comment for why
	 *  this needs to be explicit rather than left to the platform's own focus search. */
	private fun firstComparisonRow() =
		(binding.trophyCompareRecyclerView.layoutManager as? LinearLayoutManager)?.findViewByPosition(0)

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.menu_trophy_compare, menu)
		menu.findItem(R.id.action_refresh_trophy_compare)?.icon?.mutate()?.setTint(Color.WHITE)
		// Same D-pad-down gap as backButton — this view isn't created until the toolbar lays out
		// the inflated menu, so grabbing it has to wait a frame past inflate() returning.
		binding.toolbar.post {
			val refreshButton = binding.toolbar.findViewById<View>(R.id.action_refresh_trophy_compare)
			// Same focus ring as backButton — an auto-generated ActionMenuItemView gets none of
			// the app's own focus-highlight styling by default.
			refreshButton?.foreground = ContextCompat.getDrawable(this, R.drawable.bg_focus_highlight)
			// Same nudge as FriendsActivity's refresh button (see its own comment for why this
			// targets the ActionMenuView container, not the button itself), for a consistent
			// position across all three of these toolbars.
			(refreshButton?.parent as? View)?.addMarginEnd(9f)
			refreshButton?.redirectDpadDownTo { firstComparisonRow() }
		}
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId)
	{
		R.id.action_refresh_trophy_compare -> { loadComparison(); true }
		else -> super.onOptionsItemSelected(item)
	}

	/** Triangle/Y as a controller shortcut for the refresh button — same convention as the
	 *  Friends list and chat screens. Circle/B mirrors the back button, same equivalence
	 *  QuickSettingsPanel already treats KEYCODE_BACK/KEYCODE_BUTTON_B as. */
	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if (event.action == KeyEvent.ACTION_DOWN)
		{
			when (event.keyCode)
			{
				KeyEvent.KEYCODE_BUTTON_Y -> { loadComparison(); return true }
				KeyEvent.KEYCODE_BUTTON_B -> { onBackPressedDispatcher.onBackPressed(); return true }
			}
		}
		return super.dispatchKeyEvent(event)
	}
}
