// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.InstantScrollLinearLayoutManager
import com.metallic.chiaki.common.ext.fixFocusOnFastScroll
import com.metallic.chiaki.common.ext.redirectDpadDownTo
import com.metallic.chiaki.trophy.model.TrophyTitleDetail
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityTrophiesBinding
import kotlinx.coroutines.launch

class TrophiesActivity : AppCompatActivity()
{
	companion object
	{
		private const val EXTRA_GAME_NAME = "extra_game_name"
		private const val EXTRA_PLATFORM = "extra_platform"
		private const val EXTRA_IMAGE_URL = "extra_image_url"

		fun start(context: Context, game: CloudGame)
		{
			val intent = Intent(context, TrophiesActivity::class.java)
			intent.putExtra(EXTRA_GAME_NAME, game.name)
			intent.putExtra(EXTRA_PLATFORM, game.platform)
			intent.putExtra(EXTRA_IMAGE_URL, game.imageUrl)
			context.startActivity(intent)
		}
	}

	private lateinit var binding: ActivityTrophiesBinding
	private lateinit var repository: TrophyRepository
	private var currentDetail: TrophyTitleDetail? = null
	private var sortMode = TrophySortMode.DEFAULT
	private var filterMode = TrophyFilterMode.DEFAULT
	private var currentGameName = ""
	private var currentPlatform = ""
	private val adapter = TrophyAdapter(
		onTrophyClick = { trophy -> showTrophyDetailDialog(this, trophy) },
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

		binding = ActivityTrophiesBinding.inflate(layoutInflater)
		setContentView(binding.root)

		// This screen has no text fields, but every D-pad focus move onto a new trophy row still
		// triggers a synchronous IME restart (confirmed via logcat: GoogleInputMethodService
		// .onStartInput firing once per row during a fast scroll) — a well-known Android cost
		// for lists of focusable non-editable rows, and a plausible cause of the scroll stutter
		// reported when fast-scrolling downward into newly bound rows. Telling the window the IME
		// should never show removes it from having to track focus for input-method purposes here.
		window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

		setSupportActionBar(binding.toolbar)
		binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
		binding.trophyRefreshButton.setOnClickListener { loadTrophies(currentGameName, currentPlatform, forceRefresh = true) }
		binding.trophySortButton.setOnClickListener { showSortMenu(it) }
		binding.trophyFilterButton.setOnClickListener { showFilterMenu(it) }
		binding.backButton.redirectDpadDownTo {
			val firstTrophyPosition = adapter.items.indexOfFirst { it is TrophyListItem.TrophyRow }
			if (firstTrophyPosition < 0) return@redirectDpadDownTo null
			(binding.trophyRecyclerView.layoutManager as? LinearLayoutManager)?.findViewByPosition(firstTrophyPosition)
		}

		repository = TrophyRepository(prefs)

		currentGameName = intent.getStringExtra(EXTRA_GAME_NAME) ?: ""
		currentPlatform = intent.getStringExtra(EXTRA_PLATFORM) ?: ""
		val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL) ?: ""

		binding.trophyHeaderGameName.text = currentGameName
		if (imageUrl.isNotEmpty())
		{
			binding.trophyHeaderArt.load(imageUrl) { crossfade(true) }
		}
		else
		{
			binding.trophyHeaderArt.setImageResource(android.R.drawable.ic_menu_gallery)
		}

		binding.trophyRecyclerView.layoutManager = InstantScrollLinearLayoutManager(this)
		binding.trophyRecyclerView.adapter = adapter
		binding.trophyRecyclerView.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
		binding.trophyRecyclerView.fixFocusOnFastScroll("TrophiesActivity")

		loadTrophies(currentGameName, currentPlatform)
	}

	private fun loadTrophies(gameName: String, platform: String, forceRefresh: Boolean = false)
	{
		binding.trophyProgressBar.visibility = View.VISIBLE
		binding.trophyEmptyStateText.visibility = View.GONE
		binding.trophyRecyclerView.visibility = View.GONE

		lifecycleScope.launch {
			when (val result = repository.fetchTrophiesForGame(gameName, platform, forceRefresh))
			{
				is TrophyResult.Success -> showTrophies(result.detail, gameName)
				is TrophyResult.NoMatchFound -> showEmptyState(getString(R.string.quick_settings_trophies_empty, gameName))
				is TrophyResult.Error -> showEmptyState(result.message)
			}
		}
	}

	private fun showTrophies(detail: TrophyTitleDetail, gameName: String)
	{
		binding.trophyProgressBar.visibility = View.GONE
		currentDetail = detail

		val summary = detail.summary
		binding.trophyHeaderProgress.text = getString(R.string.trophy_progress_percent_complete, summary.progressPercent)
		binding.trophyHeaderPlatinumCount.text = summary.earnedTrophies.platinum.toString()
		binding.trophyHeaderGoldCount.text = summary.earnedTrophies.gold.toString()
		binding.trophyHeaderSilverCount.text = summary.earnedTrophies.silver.toString()
		binding.trophyHeaderBronzeCount.text = summary.earnedTrophies.bronze.toString()
		binding.trophyHeaderAchievedCount.text = getString(
			R.string.trophy_achieved_count_format,
			summary.earnedTrophies.total,
			summary.definedTrophies.total
		)

		if (!renderTrophyList(gameName)) return

		binding.trophyRecyclerView.visibility = View.VISIBLE
		focusFirstTrophy()
	}

	/** Rebuilds the adapter's list from [currentDetail] under the currently selected sort/filter —
	 *  used both by the initial load and whenever the user changes sort/filter, without refetching.
	 *  Returns false (and shows the empty state) if the resulting list is empty. */
	private fun renderTrophyList(gameName: String): Boolean
	{
		val detail = currentDetail ?: return false
		val items = buildTrophyListItems(this, detail, sortMode, filterMode)

		if (items.isEmpty())
		{
			val message = if (filterMode != TrophyFilterMode.DEFAULT)
				getString(R.string.trophy_filter_no_matches)
			else
				getString(R.string.quick_settings_trophies_empty, gameName)
			showEmptyState(message)
			return false
		}

		adapter.items = items
		binding.trophyRecyclerView.visibility = View.VISIBLE
		binding.trophyEmptyStateText.visibility = View.GONE
		return true
	}

	private fun showSortMenu(anchor: View)
	{
		val popup = PopupMenu(this, anchor)
		popup.menu.add(0, 0, 0, R.string.trophy_sort_default)
		popup.menu.add(0, 1, 1, R.string.trophy_sort_earned_date)
		popup.menu.setGroupCheckable(0, true, true)
		popup.menu.findItem(if (sortMode == TrophySortMode.EARNED_DATE) 1 else 0)?.isChecked = true
		whitenMenuItemText(popup.menu)

		popup.setOnMenuItemClickListener { item ->
			sortMode = if (item.itemId == 1) TrophySortMode.EARNED_DATE else TrophySortMode.DEFAULT
			renderTrophyList(binding.trophyHeaderGameName.text.toString())
			true
		}
		popup.show()
	}

	private fun showFilterMenu(anchor: View)
	{
		val popup = PopupMenu(this, anchor)
		popup.menu.add(0, 0, 0, R.string.trophy_filter_default)
		popup.menu.add(0, 1, 1, R.string.trophy_filter_bronze)
		popup.menu.add(0, 2, 2, R.string.trophy_filter_silver)
		popup.menu.add(0, 3, 3, R.string.trophy_filter_gold)
		popup.menu.add(0, 4, 4, R.string.trophy_filter_platinum)
		popup.menu.setGroupCheckable(0, true, true)
		popup.menu.findItem(filterModeToItemId(filterMode))?.isChecked = true
		whitenMenuItemText(popup.menu)

		popup.setOnMenuItemClickListener { item ->
			filterMode = itemIdToFilterMode(item.itemId)
			renderTrophyList(binding.trophyHeaderGameName.text.toString())
			true
		}
		popup.show()
	}

	/** Forces every item's title to white text, applied directly on the title CharSequence via a
	 *  span rather than through the popup's theme — the theme attributes AppPopupMenuStyle/
	 *  ThemeOverlay.App.PopupMenu set (styles.xml) kept resolving to black item text in practice
	 *  on-device despite matching the platform/AppCompat popup menu's documented attribute chain,
	 *  across two different theming approaches, so this bypasses that resolution entirely. */
	private fun whitenMenuItemText(menu: android.view.Menu)
	{
		for (i in 0 until menu.size())
		{
			val item = menu.getItem(i)
			item.title = SpannableString(item.title).apply {
				setSpan(ForegroundColorSpan(Color.WHITE), 0, length, 0)
			}
		}
	}

	/** Lands D-pad/keyboard focus on the first trophy row (skipping group headers, which aren't
	 *  focusable) so up/down navigation works immediately without requiring a touch first
	 *  (matches CloudPlayFragment.focusFirstGame). */
	private fun focusFirstTrophy()
	{
		val firstTrophyPosition = adapter.items.indexOfFirst { it is TrophyListItem.TrophyRow }
		if (firstTrophyPosition < 0) return

		binding.trophyRecyclerView.postDelayed({
			val layoutManager = binding.trophyRecyclerView.layoutManager as? LinearLayoutManager
			layoutManager?.scrollToPosition(firstTrophyPosition)
			binding.trophyRecyclerView.postDelayed({
				layoutManager?.findViewByPosition(firstTrophyPosition)?.requestFocus()
			}, 50)
		}, 100)
	}

	private fun showEmptyState(message: String)
	{
		binding.trophyProgressBar.visibility = View.GONE
		binding.trophyRecyclerView.visibility = View.GONE
		binding.trophyEmptyStateText.text = message
		binding.trophyEmptyStateText.visibility = View.VISIBLE

		// No list means nothing else on screen to carry D-pad focus to backButton/refresh via
		// onTopBoundary/redirectDpadDownTo — land it there directly, same fix as onTopBoundary.
		binding.backButton.isFocusableInTouchMode = true
		binding.backButton.requestFocus()
	}

	/** Circle/B as a controller shortcut for the back button — same equivalence QuickSettingsPanel
	 *  already treats KEYCODE_BACK/KEYCODE_BUTTON_B as. */
	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
		{
			onBackPressedDispatcher.onBackPressed()
			return true
		}
		return super.dispatchKeyEvent(event)
	}
}
