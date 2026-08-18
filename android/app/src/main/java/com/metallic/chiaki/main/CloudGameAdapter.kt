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
	private val onProfileClick: (CloudGame) -> Unit,
	private val hasProfile: (CloudGame) -> Boolean,
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

    override fun onViewRecycled(holder: CloudGameViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelImage()
        holder.cancelPendingLongPress()
        if (iconNavHolder === holder) iconNavHolder = null
    }

    override fun getItemCount(): Int = games.size

    /** The tile whose favourite/trophies/playtime/shortcut/profile icons are currently being
     *  controller-navigated (entered via Select on that tile), or null if no tile is in that
     *  mode. Only one tile can be in icon-nav mode at a time. */
    private var iconNavHolder: CloudGameViewHolder? = null

    /** MainActivity checks this so its DPAD row/boundary logic (which reasons about tile
     *  positions, not icon positions) steps aside and lets the focused icon's own key listener
     *  handle D-pad movement instead. */
    val isIconNavActive: Boolean get() = iconNavHolder != null

    /** Backs out of icon-nav mode from outside the adapter (e.g. MainActivity's Back handling). */
    fun exitIconNav() {
        iconNavHolder?.exitIconNav()
    }

    inner class CloudGameViewHolder(
        val binding: ItemCloudGameBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingTrophiesRunnable: Runnable? = null
        private var trophiesLongPressTriggered = false

        /** Top-to-bottom order matches the tile layout, and is what Select-press icon-nav
         *  navigates through. */
        private val icons by lazy {
			listOf(binding.favoriteButton, binding.trophiesButton, binding.playtimeButton, binding.addToHomeButton, binding.profileButton)
        }

        fun cancelImage() {
            binding.gameImageView.dispose()
        }

        fun cancelPendingLongPress() {
            pendingTrophiesRunnable?.let { longPressHandler.removeCallbacks(it) }
            pendingTrophiesRunnable = null
        }

        /** Select (1st press): focuses the favourite icon and makes all five icons reachable by
         *  D-pad — they're non-focusable the rest of the time so normal grid navigation can't
         *  wander into them by accident. */
        fun enterIconNav() {
            iconNavHolder = this
            icons.forEach {
                it.isFocusable = true
                it.isFocusableInTouchMode = true
            }
            binding.favoriteButton.requestFocusFromTouch()
        }

        /** Select (2nd press) or Back returns focus to this
         *  tile and makes the icons non-focusable again. No-op if this holder isn't the one
         *  currently in icon-nav mode (e.g. a stray call after the icon action navigated away). */
        fun exitIconNav() {
            if (iconNavHolder !== this) return
            icons.forEach {
                it.isFocusable = false
                it.isFocusableInTouchMode = false
            }
            iconNavHolder = null
            binding.root.isFocusableInTouchMode = true
            binding.root.requestFocusFromTouch()
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
			binding.profileButton.setImageResource(
				if (hasProfile(game)) R.drawable.ic_settings else R.drawable.ic_settings_outline
			)

            // Rebinding (e.g. a recycled view scrolling back into place) always starts from
            // "not in icon-nav mode" — if this exact holder was the active one, clear it too, so
            // a rebind while icon-nav was active can't leave a dangling reference to a tile that
            // no longer shows that game.
            if (iconNavHolder === this) iconNavHolder = null
            icons.forEach { icon ->
                icon.isFocusable = false
                icon.isFocusableInTouchMode = false
                icon.setBackgroundResource(R.drawable.bg_tile_icon_circle)
                icon.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundResource(
                        if (hasFocus) R.drawable.bg_tile_icon_circle_focused else R.drawable.bg_tile_icon_circle
                    )
                }
            }

            binding.loadingSpinner?.visibility = android.view.View.GONE
            if (game.imageUrl.isEmpty()) {
                binding.gameImageView.setImageResource(android.R.drawable.ic_menu_gallery)
            } else {
                // No crossfade to prevent flash when recycled views rebind.
                binding.gameImageView.load(game.imageUrl) {
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    networkCachePolicy(CachePolicy.ENABLED)
                    crossfade(false)
                    error(android.R.drawable.ic_menu_gallery)
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

            // Picking any icon while controller-navigating it (icons list, above)
            // leaves the user right where they were — still on that icon, in icon-nav mode —
            // rather than kicking them back out to the tile. Only an explicit 2nd Select press
            // or Back exits icon-nav mode.
            binding.favoriteButton.setOnClickListener { toggleFavorite() }
            binding.trophiesButton.setOnClickListener { onTrophiesClick(game) }
            binding.playtimeButton.setOnClickListener { onPlaytimeClick(game) }
            binding.addToHomeButton.setOnClickListener { onAddToHomeClick(game) }
			binding.profileButton.setOnClickListener { onProfileClick(game) }

            icons.forEachIndexed { index, icon ->
                icon.setOnKeyListener { _, keyCode, event ->
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (event.action == android.view.KeyEvent.ACTION_DOWN && index > 0) {
                                icons[index - 1].requestFocusFromTouch()
                            }
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (event.action == android.view.KeyEvent.ACTION_DOWN && index < icons.lastIndex) {
                                icons[index + 1].requestFocusFromTouch()
                            }
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (event.action == android.view.KeyEvent.ACTION_DOWN && icon !== binding.profileButton) {
                                binding.profileButton.requestFocusFromTouch()
                            }
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (event.action == android.view.KeyEvent.ACTION_DOWN && icon === binding.profileButton) {
                                binding.addToHomeButton.requestFocusFromTouch()
                            }
                            true
                        }
                        android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> {
                            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                exitIconNav()
                            }
                            true
                        }
                        else -> false
                    }
                }
            }

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
                    // Start — a plain press triggers the same confirmation dialog as tapping
                    // addToHomeButton directly (same immediate-on-ACTION_DOWN shape as MENU/
                    // favorite above, since there's no hold/short-press distinction to make here).
                    android.view.KeyEvent.KEYCODE_BUTTON_START -> {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                            // repeatCount guard so holding it doesn't re-open the dialog on every
                            // auto-repeated DOWN event.
                            if (event.repeatCount == 0) onAddToHomeClick(game)
                            true
                        } else false
                    }
                    // Select (1st press, tile focused): jumps into icon-nav mode on the favourite
                    // icon. The 2nd press that exits is handled per-icon above, not here — once
                    // icon-nav starts, focus (and so key dispatch) has moved off binding.root.
                    android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            enterIconNav()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }
}
