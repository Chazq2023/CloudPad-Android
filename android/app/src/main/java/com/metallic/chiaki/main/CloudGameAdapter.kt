// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.request.CachePolicy
import com.pylux.stream.R
import com.metallic.chiaki.cloudplay.model.CloudGame
import com.metallic.chiaki.cloudplay.model.StreamableStatus
import com.metallic.chiaki.common.ext.enableFocusableInTouchModeForTv
import com.pylux.stream.databinding.ItemCloudGameBinding

/** Shared by every Modern-Grid-Deck-style card's focus highlight (grid tiles, Remote Play host
 *  cards) so they all track the user's selected theme accent instead of a hardcoded color. */
internal fun resolveThemeColor(context: android.content.Context, attr: Int): Int {
    val tv = android.util.TypedValue()
    context.theme.resolveAttribute(attr, tv, true)
    return tv.data
}

class CloudGameAdapter(
    private val onGameClick: (CloudGame) -> Unit,
    private val onFavoriteClick: (CloudGame, Boolean) -> Unit,
    private val onPlaytimeClick: (CloudGame) -> Unit,
    private val onTrophiesClick: (CloudGame) -> Unit,
    private val onAddToHomeClick: (CloudGame) -> Unit,
    private val isFavorite: (String) -> Boolean
) : RecyclerView.Adapter<CloudGameAdapter.CloudGameViewHolder>() {
    init {
        // Lets Coil's memory cache carry an already-loaded tile's image straight through a
        // refresh instead of visibly reloading it — without a stable ID, notifyDataSetChanged()
        // gives every visible ViewHolder no identity to match against the previous list, so Coil
        // treats each one as a brand new load target. productId alone isn't safe as the raw key
        // (the same game can legitimately appear twice — once per platform/service variant — and
        // duplicate stable IDs crash RecyclerView), so it's combined with platform/serviceType,
        // with a defensive dedupe below closing off any remaining collision case.
        setHasStableIds(true)
    }

    var games: List<CloudGame> = emptyList()
        set(value) {
            field = value.distinctBy { stableKey(it) }
            notifyDataSetChanged()
        }

    var showStreamabilityBadge: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var isScrollingFast = false

    private fun stableKey(game: CloudGame) = "${game.productId}|${game.platform}|${game.serviceType}"

    override fun getItemId(position: Int): Long = stableKey(games[position]).hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CloudGameViewHolder {
        val binding = ItemCloudGameBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        binding.root.enableFocusableInTouchModeForTv(parent.context)
        return CloudGameViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CloudGameViewHolder, position: Int) {
        holder.bind(games[position])
    }

    override fun onBindViewHolder(
        holder: CloudGameViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(FastScrollerHelper.PAYLOAD_RELOAD_IMAGE)) {
            // Only reload the image — don't rebind the whole card (avoids flash)
            holder.reloadImage(games[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: CloudGameViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelImage()
        holder.cancelPendingLongPress()
    }

    override fun getItemCount(): Int = games.size

    inner class CloudGameViewHolder(
        val binding: ItemCloudGameBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingTrophiesRunnable: Runnable? = null
        private var trophiesLongPressTriggered = false

        fun cancelImage() {
            binding.gameImageView.dispose()
        }

        fun cancelPendingLongPress() {
            pendingTrophiesRunnable?.let { longPressHandler.removeCallbacks(it) }
            pendingTrophiesRunnable = null
        }

        fun reloadImage(game: CloudGame) {
            if (game.imageUrl.isNotEmpty()) {
                binding.gameImageView.load(game.imageUrl) {
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    networkCachePolicy(CachePolicy.ENABLED)
                    crossfade(false)
                }
            }
        }

        fun bind(game: CloudGame) {
            binding.gameNameTextView.text = game.name
            binding.gamePlatformTextView.text = when (game.platform.lowercase()) {
                "ps3" -> "PS3"
                "ps4" -> "PS4"
                "ps5" -> "PS5"
                else -> game.platform.takeLast(1)
            }

            if (showStreamabilityBadge && game.serviceType == "pscloud") {
                binding.streamabilityBadge.visibility = android.view.View.VISIBLE
                binding.streamabilityIcon.setImageResource(
                    when (game.streamableStatus) {
                        StreamableStatus.STREAMABLE -> R.drawable.ic_check_white
                        StreamableStatus.NOT_STREAMABLE -> R.drawable.ic_close_white
                        StreamableStatus.UNKNOWN -> R.drawable.ic_question_white
                    }
                )
            } else {
                binding.streamabilityBadge.visibility = android.view.View.GONE
            }

            val isFav = isFavorite(game.productId)
            binding.favoriteButton.setImageResource(
                if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            )

            binding.loadingSpinner?.visibility = android.view.View.GONE
            if (game.imageUrl.isEmpty()) {
                binding.gameImageView.setImageResource(android.R.drawable.ic_menu_gallery)
            } else {
                // During fast scroll: memory + disk cache stay on (local, cheap — and with the
                // whole catalog prefetched to disk up front by CloudPlayFragment.prefetchArtwork,
                // most tiles are already there), only network is skipped. That avoids the slow
                // network-fetch-mid-fling jank the memory-only version of this policy was
                // originally guarding against, without reintroducing it for already-cached tiles.
                // No crossfade to prevent flash when recycled views rebind.
                binding.gameImageView.load(game.imageUrl) {
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    networkCachePolicy(if (isScrollingFast) CachePolicy.DISABLED else CachePolicy.ENABLED)
                    crossfade(false)
                }
            }

            binding.root.setOnClickListener {
                onGameClick(game)
            }

            val toggleFavorite = {
                val newFavoriteState = !isFavorite(game.productId)
                onFavoriteClick(game, newFavoriteState)
                binding.favoriteButton.setImageResource(
                    if (newFavoriteState) R.drawable.ic_star_filled else R.drawable.ic_star_outline
                )
            }

            binding.favoriteButton.setOnClickListener { toggleFavorite() }
            binding.trophiesButton.setOnClickListener { onTrophiesClick(game) }
            binding.playtimeButton.setOnClickListener { onPlaytimeClick(game) }
            binding.addToHomeButton.setOnClickListener { onAddToHomeClick(game) }

            binding.root.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, hasFocus ->
                val card = v as com.google.android.material.card.MaterialCardView
                if (hasFocus) {
                    card.strokeColor = resolveThemeColor(v.context, R.attr.pyluxAccent)
                    card.strokeWidth = (2 * v.resources.displayMetrics.density).toInt()
                } else {
                    card.strokeColor = resolveThemeColor(v.context, R.attr.pyluxAccentA20)
                    card.strokeWidth = (1 * v.resources.displayMetrics.density).toInt()
                }
            }

            // Favorites/Add to Home Screen are now direct icons on the tile (favoriteButton/
            // addToHomeButton above), so the long-press menu that used to surface them is gone —
            // holding a tile intentionally does nothing now.
            binding.root.setOnLongClickListener(null)
            binding.root.setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_MENU -> {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                            toggleFavorite()
                            true
                        } else false
                    }
                    // Square on PlayStation controllers reports as the generic gamepad "X" keycode
                    // on Android (X/Y/A/B are positional, not brand-specific). Short press opens
                    // Playtime; holding it opens Trophies instead.
                    android.view.KeyEvent.KEYCODE_BUTTON_X -> {
                        when (event.action) {
                            android.view.KeyEvent.ACTION_DOWN -> {
                                if (event.repeatCount == 0) {
                                    cancelPendingLongPress()
                                    trophiesLongPressTriggered = false
                                    val runnable = Runnable {
                                        trophiesLongPressTriggered = true
                                        onTrophiesClick(game)
                                    }
                                    pendingTrophiesRunnable = runnable
                                    longPressHandler.postDelayed(
                                        runnable,
                                        android.view.ViewConfiguration.getLongPressTimeout().toLong()
                                    )
                                }
                                true
                            }

                            android.view.KeyEvent.ACTION_UP -> {
                                val wasLongPress = trophiesLongPressTriggered
                                cancelPendingLongPress()
                                if (!wasLongPress) onPlaytimeClick(game)
                                true
                            }

                            else -> false
                        }
                    }
                    else -> false
                }
            }
        }
    }
}

