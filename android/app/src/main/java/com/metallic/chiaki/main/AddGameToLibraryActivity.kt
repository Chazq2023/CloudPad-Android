// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.PopupMenu
import com.metallic.chiaki.cloudplay.model.AddGameFilter
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.cloudplay.model.matchingFilter
import com.metallic.chiaki.cloudplay.model.taggedWithPsPlus
import com.metallic.chiaki.cloudplay.repository.PsPlusResult
import com.metallic.chiaki.cloudplay.model.PsnResult
import com.metallic.chiaki.cloudplay.model.matchingQuery
import com.metallic.chiaki.cloudplay.repository.CloudGameRepository
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.redirectDpadDownTo
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityAddGameBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Lists every known streamable PS5 game that isn't in the user's library yet. Tapping one opens
 * its Sony page, where it can be added; after adding, refreshing the PS5 Library brings it in
 * (and drops it from this list — see CloudGameRepository.fetchOwnedPs5Games).
 */
class AddGameToLibraryActivity : AppCompatActivity()
{
	companion object
	{
		private const val TAG = "AddGameToLibrary"
		private const val STATE_FILTER = "filter"

		fun start(context: Context) =
			context.startActivity(Intent(context, AddGameToLibraryActivity::class.java))
	}

	private lateinit var binding: ActivityAddGameBinding
	private lateinit var preferences: Preferences
	private lateinit var repository: CloudGameRepository
	private val adapter = AddGameAdapter(
		onGameClick = ::openGamePage,
		onTopBoundary = { focusView(searchInput()) }
	)

	private var allGames: List<CloudGame> = emptyList()
	private var loadJob: Job? = null
	private var filter = AddGameFilter.ALL

	/** Where the PS Plus lookup (which games are included with PS Plus) stands. It loads after
	 *  the game list, separately, since it can be a big first-time download. */
	private enum class PlusState { LOADING, READY, FAILED }
	private var plusState = PlusState.LOADING
	private var plusKeys: Set<String>? = null
	private var plusJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		preferences = Preferences(this)
		if (preferences.getThemeColour() != "pink") setTheme(preferences.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		binding = ActivityAddGameBinding.inflate(layoutInflater)
		setContentView(binding.root)
		setSupportActionBar(binding.toolbar)

		savedInstanceState?.getString(STATE_FILTER)?.let { saved ->
			filter = AddGameFilter.values().firstOrNull { it.name == saved } ?: AddGameFilter.ALL
		}

		repository = CloudGameRepository(applicationContext, preferences)

		binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
		binding.refreshButton.setOnClickListener { loadGames(forceRefresh = true) }
		binding.filterButton.setOnClickListener { showFilterMenu(it) }
		updateFilterButton()

		binding.gamesRecyclerView.layoutManager = InstantScrollGridLayoutManager(this, calculateSpanCount())
		binding.gamesRecyclerView.adapter = adapter

		binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener
		{
			override fun onQueryTextSubmit(query: String?): Boolean
			{
				binding.searchView.clearFocus()
				return true
			}

			override fun onQueryTextChange(newText: String?): Boolean
			{
				showGames()
				return true
			}
		})

		// AppCompat's default clear (X) button empties the text but then requests focus and
		// force-shows the keyboard. Clearing the search shouldn't open the keyboard — that should
		// only happen when the user taps into the field themselves (same fix as the library search).
		binding.searchView.findViewById<View>(androidx.appcompat.R.id.search_close_btn)
			?.setOnClickListener {
				binding.searchView.setQuery("", false)
				binding.searchView.clearFocus()
				val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
				imm.hideSoftInputFromWindow(binding.searchView.windowToken, 0)
			}

		// AppCompat's default clear (X) button empties the text but then requests focus and
		// force-shows the keyboard. Clearing the search shouldn't open the keyboard — that should
		// only happen when the user taps into the field themselves (same fix as the library search).
		binding.searchView.findViewById<View>(androidx.appcompat.R.id.search_close_btn)
			?.setOnClickListener {
				binding.searchView.setQuery("", false)
				binding.searchView.clearFocus()
				val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
				imm.hideSoftInputFromWindow(binding.searchView.windowToken, 0)
			}

		setupDpadNavigation()
		loadGames(forceRefresh = false)
	}

	override fun onSaveInstanceState(outState: Bundle)
	{
		super.onSaveInstanceState(outState)
		outState.putString(STATE_FILTER, filter.name)
	}

	private fun filterLabel(option: AddGameFilter) = getString(
		when(option)
		{
			AddGameFilter.ALL -> R.string.add_game_filter_all
			AddGameFilter.PURCHASABLE -> R.string.add_game_filter_purchasable
			AddGameFilter.PS_CATALOG -> R.string.add_game_filter_ps_catalog
			AddGameFilter.PS_PLUS -> R.string.add_game_filter_ps_plus
			AddGameFilter.FREE_TO_PLAY -> R.string.add_game_filter_free_to_play
		}
	)

	private fun showFilterMenu(anchor: View)
	{
		val popup = PopupMenu(this, anchor)
		AddGameFilter.values().forEach { option ->
			popup.menu.add(0, option.ordinal, option.ordinal, filterLabel(option))
		}
		popup.menu.setGroupCheckable(0, true, true)
		popup.menu.findItem(filter.ordinal)?.isChecked = true
		popup.setOnMenuItemClickListener { item ->
			filter = AddGameFilter.values()[item.itemId]
			updateFilterButton()
			showGames()
			true
		}
		popup.show()
	}

	/** Tints the icon with the theme accent while a filter other than All is active, so it's
	 *  visible at a glance that the list is being narrowed. */
	private fun updateFilterButton()
	{
		val tint = TypedValue()
		theme.resolveAttribute(
			if(filter == AddGameFilter.ALL) com.google.android.material.R.attr.colorOnPrimary else R.attr.pyluxAccentLight,
			tint, true
		)
		binding.filterButton.setColorFilter(tint.data)
	}

	private fun searchInput(): View =
		binding.searchView.findViewById(androidx.appcompat.R.id.search_src_text) ?: binding.searchView

	/** Touch mode is still active until the first D-pad press, and requestFocus() silently
	 *  fails on a target that isn't focusable in touch mode (see redirectDpadDownTo's doc). */
	private fun focusView(view: View?)
	{
		view ?: return
		view.isFocusableInTouchMode = true
		view.requestFocus()
	}

	/** The platform's focus search doesn't cross between the toolbar, the search field and the
	 *  grid on this screen, so each hop is wired explicitly: toolbar buttons DOWN -> search,
	 *  search UP -> back button, search DOWN -> first tile, first-row tile UP -> search. */
	private fun setupDpadNavigation()
	{
		binding.backButton.redirectDpadDownTo { searchInput().also { it.isFocusableInTouchMode = true } }
		binding.filterButton.redirectDpadDownTo { searchInput().also { it.isFocusableInTouchMode = true } }
		binding.refreshButton.redirectDpadDownTo { searchInput().also { it.isFocusableInTouchMode = true } }

		searchInput().setOnKeyListener { _, keyCode, event ->
			if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
			when (keyCode)
			{
				KeyEvent.KEYCODE_DPAD_UP ->
				{
					focusView(binding.backButton)
					true
				}
				KeyEvent.KEYCODE_DPAD_DOWN ->
				{
					val firstTile = binding.gamesRecyclerView.findViewHolderForAdapterPosition(0)?.itemView
					if (firstTile == null) false else { focusView(firstTile); true }
				}
				else -> false
			}
		}
	}

	private fun calculateSpanCount(): Int
	{
		val metrics = resources.displayMetrics
		return ((metrics.widthPixels / metrics.density) / 180).toInt().coerceIn(2, 4)
	}

	private fun loadGames(forceRefresh: Boolean)
	{
		if (loadJob?.isActive == true) return

		val npsso = preferences.getNpssoToken()
		if (npsso.isEmpty())
		{
			showMessage(getString(R.string.add_game_login_required))
			return
		}

		binding.progressBar.visibility = View.VISIBLE
		binding.emptyStateText.visibility = View.GONE
		binding.refreshButton.isEnabled = false

		loadJob = lifecycleScope.launch {
			when (val result = repository.fetchPs5GamesNotInLibrary(npsso, forceRefresh))
			{
				is PsnResult.Success ->
				{
					allGames = result.data.let { games -> plusKeys?.let { games.taggedWithPsPlus(it) } ?: games }
					showGames()
					loadPsPlus(forceRefresh)
				}
				is PsnResult.Error ->
				{
					Log.e(TAG, "Failed to load games: ${result.message}", result.exception)
					allGames = emptyList()
					showMessage(getString(R.string.add_game_load_failed, result.message))
				}
			}
			binding.progressBar.visibility = View.GONE
			binding.refreshButton.isEnabled = true
		}
	}

	/** Loads which games are included with PS Plus and tags them. Normally served from a week-long
	 *  cache; the page's refresh button passes [forceRefresh] to fetch a new one. */
	private fun loadPsPlus(forceRefresh: Boolean = false)
	{
		if (plusJob?.isActive == true) return
		if (plusState != PlusState.READY) plusState = PlusState.LOADING
		plusJob = lifecycleScope.launch {
			when (val result = repository.loadPsPlusKeys(forceRefresh))
			{
				is PsPlusResult.Ready ->
				{
					plusKeys = result.keys
					allGames = allGames.taggedWithPsPlus(result.keys)
					plusState = PlusState.READY
					if (forceRefresh && !result.isFresh)
						Toast.makeText(this@AddGameToLibraryActivity, R.string.add_game_plus_not_refreshed, Toast.LENGTH_LONG).show()
				}
				is PsPlusResult.Failed ->
				{
					Log.w(TAG, "PS Plus lookup failed: ${result.message}")
					plusState = PlusState.FAILED
				}
			}
			showGames()
		}
	}

	private fun plusStatusMessage(): String = getString(
		when (plusState)
		{
			PlusState.LOADING -> R.string.add_game_plus_loading
			else -> R.string.add_game_plus_failed
		}
	)

	private fun showGames()
	{
		// The PS Plus filter has nothing to show until the lookup is in — say why instead of "no matches".
		if (filter == AddGameFilter.PS_PLUS && plusState != PlusState.READY && allGames.isNotEmpty())
		{
			showMessage(plusStatusMessage())
			return
		}

		val visible = allGames
			.matchingFilter(filter)
			.matchingQuery(binding.searchView.query?.toString() ?: "")
		adapter.submitList(visible)
		updateGameCount(shown = visible.size, total = allGames.size)
		if (filter == AddGameFilter.PURCHASABLE && plusState != PlusState.READY && allGames.isNotEmpty())
		{
			// Purchasable leaves out PS Plus titles, which isn't known yet — don't pass a partial list off as complete.
			binding.gameCountText.text = "${binding.gameCountText.text} · " + getString(
				if (plusState == PlusState.LOADING) R.string.add_game_plus_note_loading else R.string.add_game_plus_note_unavailable
			)
		}
		if (visible.isEmpty())
		{
			binding.emptyStateText.text = getString(
				if (allGames.isEmpty()) R.string.add_game_empty else R.string.add_game_no_matches
			)
			binding.emptyStateText.visibility = View.VISIBLE
		}
		else
			binding.emptyStateText.visibility = View.GONE
	}

	/** "x out of y games displayed": x is what the current filter (and search) leaves, y is always
	 *  the full not-in-library list. Hidden while there's no list (not loaded yet, or an error). */
	private fun updateGameCount(shown: Int, total: Int)
	{
		if(total == 0)
		{
			binding.gameCountText.visibility = View.GONE
			return
		}
		val format = NumberFormat.getIntegerInstance()
		binding.gameCountText.text = resources.getQuantityString(
			R.plurals.add_game_count, total, format.format(shown), format.format(total)
		)
		binding.gameCountText.visibility = View.VISIBLE
	}

	private fun showMessage(message: String)
	{
		binding.gameCountText.visibility = View.GONE
		adapter.submitList(emptyList())
		binding.emptyStateText.text = message
		binding.emptyStateText.visibility = View.VISIBLE
	}

	/** Sony sign-in happens in Chrome (see PsnLoginActivity), so a Custom Tab reuses that session. */
	private fun openGamePage(game: CloudGame)
	{
		if (game.conceptUrl.isEmpty())
		{
			Toast.makeText(this, R.string.cloud_add_to_library_no_url_message, Toast.LENGTH_LONG).show()
			return
		}

		val uri = Uri.parse(game.conceptUrl)
		try
		{
			CustomTabsIntent.Builder().build().launchUrl(this, uri)
		}
		catch (e: Exception)
		{
			Log.w(TAG, "Custom Tab unavailable, falling back to browser intent", e)
			try
			{
				startActivity(Intent(Intent.ACTION_VIEW, uri))
			}
			catch (e2: Exception)
			{
				Log.e(TAG, "Failed to open ${game.conceptUrl}", e2)
				Toast.makeText(this, R.string.cloud_failed_to_open_browser_toast, Toast.LENGTH_SHORT).show()
			}
		}
	}

	/** Same fix as CloudPlayFragment's grid: skip the smooth-scroll animation on focus moves so
	 *  D-pad navigation doesn't overshoot and leave the focused tile off-screen. */
	private class InstantScrollGridLayoutManager(context: Context, spanCount: Int) :
		GridLayoutManager(context, spanCount)
	{
		override fun requestChildRectangleOnScreen(
			parent: RecyclerView,
			child: View,
			rect: android.graphics.Rect,
			immediate: Boolean,
			focusedChildVisible: Boolean
		): Boolean = super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible)
	}
}
