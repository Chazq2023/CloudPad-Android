// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.trophy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.fixFocusOnFastScroll
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
	private val adapter = TrophyAdapter(onTrophyClick = { trophy -> showTrophyDetailDialog(this, trophy) })

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
		supportActionBar?.setDisplayHomeAsUpEnabled(true)

		repository = TrophyRepository(prefs)

		val gameName = intent.getStringExtra(EXTRA_GAME_NAME) ?: ""
		val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: ""
		val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL) ?: ""

		binding.trophyHeaderGameName.text = gameName
		if (imageUrl.isNotEmpty())
		{
			binding.trophyHeaderArt.load(imageUrl) { crossfade(true) }
		}
		else
		{
			binding.trophyHeaderArt.setImageResource(android.R.drawable.ic_menu_gallery)
		}

		binding.trophyRecyclerView.layoutManager = LinearLayoutManager(this)
		binding.trophyRecyclerView.adapter = adapter
		binding.trophyRecyclerView.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
		binding.trophyRecyclerView.fixFocusOnFastScroll("TrophiesActivity")

		loadTrophies(gameName, platform)
	}

	private fun loadTrophies(gameName: String, platform: String)
	{
		binding.trophyProgressBar.visibility = View.VISIBLE
		binding.trophyEmptyStateText.visibility = View.GONE
		binding.trophyRecyclerView.visibility = View.GONE

		lifecycleScope.launch {
			when (val result = repository.fetchTrophiesForGame(gameName, platform))
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

		val summary = detail.summary
		binding.trophyHeaderProgress.text = "${summary.progressPercent}% Complete"
		binding.trophyHeaderPlatinumCount.text = summary.earnedTrophies.platinum.toString()
		binding.trophyHeaderGoldCount.text = summary.earnedTrophies.gold.toString()
		binding.trophyHeaderSilverCount.text = summary.earnedTrophies.silver.toString()
		binding.trophyHeaderBronzeCount.text = summary.earnedTrophies.bronze.toString()

		val items = buildTrophyListItems(detail)

		if (items.isEmpty())
		{
			showEmptyState(getString(R.string.quick_settings_trophies_empty, gameName))
			return
		}

		adapter.items = items
		binding.trophyRecyclerView.visibility = View.VISIBLE
		focusFirstTrophy()
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
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId)
	{
		android.R.id.home -> { finish(); true }
		else -> super.onOptionsItemSelected(item)
	}
}
