// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.common.ext.enableFocusableInTouchModeForTv
import com.pylux.stream.databinding.ItemAddGameBinding

/** Plain cover-art tiles for the add-a-game-to-library page (no favourite/trophy/playtime icons). */
class AddGameAdapter(
	private val onGameClick: (CloudGame) -> Unit
) : ListAdapter<CloudGame, AddGameAdapter.ViewHolder>(DIFF)
{
	companion object
	{
		private val DIFF = object : DiffUtil.ItemCallback<CloudGame>()
		{
			override fun areItemsTheSame(old: CloudGame, new: CloudGame) =
				old.productId == new.productId && old.platform == new.platform
			override fun areContentsTheSame(old: CloudGame, new: CloudGame) = old == new
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder
	{
		val binding = ItemAddGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		binding.root.enableFocusableInTouchModeForTv(parent.context)
		return ViewHolder(binding)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

	override fun onViewRecycled(holder: ViewHolder)
	{
		super.onViewRecycled(holder)
		holder.binding.gameImageView.dispose()
	}

	inner class ViewHolder(val binding: ItemAddGameBinding) : RecyclerView.ViewHolder(binding.root)
	{
		fun bind(game: CloudGame)
		{
			binding.gameNameTextView.text = game.name
			binding.gamePlatformTextView.text = when (game.platform.lowercase())
			{
				"ps3" -> "PS3"
				"ps4" -> "PS4"
				"ps5" -> "PS5"
				else -> game.platform.takeLast(1)
			}

			if (game.imageUrl.isEmpty())
				binding.gameImageView.setImageResource(android.R.drawable.ic_menu_gallery)
			else
				binding.gameImageView.load(game.imageUrl) {
					crossfade(false)
					error(android.R.drawable.ic_menu_gallery)
				}

			binding.root.setOnClickListener { onGameClick(game) }
		}
	}
}
