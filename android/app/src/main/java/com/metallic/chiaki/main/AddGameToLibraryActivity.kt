// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.cloudplay.model.PsnResult
import com.metallic.chiaki.cloudplay.model.matchingQuery
import com.metallic.chiaki.cloudplay.repository.CloudGameRepository
import com.metallic.chiaki.common.Preferences
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityAddGameBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

		fun start(context: Context) =
			context.startActivity(Intent(context, AddGameToLibraryActivity::class.java))
	}

	private lateinit var binding: ActivityAddGameBinding
	private lateinit var preferences: Preferences
	private lateinit var repository: CloudGameRepository
	private val adapter = AddGameAdapter(onGameClick = ::openGamePage)

	private var allGames: List<CloudGame> = emptyList()
	private var loadJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		preferences = Preferences(this)
		if (preferences.getThemeColour() != "pink") setTheme(preferences.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		binding = ActivityAddGameBinding.inflate(layoutInflater)
		setContentView(binding.root)
		setSupportActionBar(binding.toolbar)

		repository = CloudGameRepository(applicationContext, preferences)

		binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
		binding.refreshButton.setOnClickListener { loadGames(forceRefresh = true) }

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

		loadGames(forceRefresh = false)
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
					allGames = result.data
					showGames()
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

	private fun showGames()
	{
		val visible = allGames.matchingQuery(binding.searchView.query?.toString() ?: "")
		adapter.submitList(visible)
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

	private fun showMessage(message: String)
	{
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
