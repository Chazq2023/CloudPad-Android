// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.pylux.stream.R
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.PsnDisplayHost
import com.metallic.chiaki.common.ext.inflate
import com.metallic.chiaki.common.ext.enableFocusableInTouchModeForTv
import com.pylux.stream.databinding.ItemDisplayHostBinding
import com.metallic.chiaki.discovery.ConsoleSleepIntent
import com.metallic.chiaki.lib.DiscoveryHost

class DisplayHostDiffCallback(val old: List<DisplayHost>, val new: List<DisplayHost>): DiffUtil.Callback()
{
	override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = (old[oldItemPosition] == new[newItemPosition])
	override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = (old[oldItemPosition] == new[newItemPosition])
	override fun getOldListSize() = old.size
	override fun getNewListSize() = new.size
}

class DisplayHostRecyclerViewAdapter(
	val clickCallback: (DisplayHost) -> Unit,
	val wakeupCallback: (DisplayHost) -> Unit,
	val editCallback: (DisplayHost) -> Unit,
	val deleteCallback: (DisplayHost) -> Unit
): RecyclerView.Adapter<DisplayHostRecyclerViewAdapter.ViewHolder>()
{
	var hosts: List<DisplayHost> = listOf()
		set(value)
		{
			val diff = DiffUtil.calculateDiff(DisplayHostDiffCallback(field, value))
			field = value
			diff.dispatchUpdatesTo(this)
		}

	/** Host IDs currently mid-wake-transition — see MainViewModel.hostTransitions' doc comment.
	 *  Set directly by the fragment (not part of [hosts]/DiffUtil, since it doesn't change which
	 *  hosts are shown, only how one already-shown tile reads) alongside an explicit
	 *  notifyDataSetChanged() to force the affected row(s) to actually rebind. */
	var hostTransitions: Map<String, HostTransitionState> = emptyMap()

	class ViewHolder(val binding: ItemDisplayHostBinding): RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
		= ViewHolder(ItemDisplayHostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun getItemCount() = hosts.count()

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val context = holder.itemView.context
		val host = hosts[position]
		holder.binding.also {
			// Set both visible header name and hidden binding name
			it.nameTextView.text = host.name
			it.headerNameTextView.text = host.name
			
			// Platform badge (4 or 5)
			val platformLabel = if(host.isPS5) "PS5" else "PS4"
			it.platformBadge.text = platformLabel
			it.platformTextView.text = platformLabel

			// Takes priority over every other status below — see MainViewModel.hostTransitions'
			// doc comment for why discovery alone reporting READY (or even a successful raw TCP
			// check) isn't good enough to call a just-woken console "Ready" yet.
			val transition = host.id?.let { hostTransitions[it] }
			// Only meaningful for a host with a real address (PsnDisplayHost.host is always
			// blank) — see ConsoleSleepIntent's doc comment. Cleared once discovery confirms
			// STANDBY, so this is false again well before "Asleep" below would otherwise apply.
			val isPendingSleep = ConsoleSleepIntent.isPendingSleep(host.host)

			// For PSN hosts: just show "Remote Console" and a "Getting Console Status" status.
			// Deliberately not "Ready" — PSN presence only means this console is associated with
			// the account, not that it's actually powered on right now (e.g. right after a
			// wakeup packet, it can take many seconds to boot before local discovery confirms
			// it's really up, and this tile is what's shown in that gap — see
			// MainViewModel.combine()'s discovered/psnDisplayHosts split). "Ready" is reserved
			// for the locally-confirmed green state below so the two can never be confused for
			// the same thing. Colour is hardcoded here rather than reusing R.color.psn_blue,
			// which despite its name is actually the app's pink theme accent (#FF149D) — this
			// status needs to visually read as "blue", distinct from ready/asleep/offline.
			if(host is PsnDisplayHost)
			{
				it.hostTextView.text = context.getString(R.string.display_host_remote_console)
				it.hostTextView.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
				it.hostTextView.textSize = 16f
				it.idTextView.visibility = View.GONE
				it.statusLayout.visibility = View.VISIBLE
				when
				{
					transition == HostTransitionState.WAKING ->
					{
						it.statusTextView.text = context.getString(R.string.display_host_waking_console)
						it.statusIcon.setColorFilter(android.graphics.Color.parseColor("#F97316")) // Orange-500
					}
					transition == HostTransitionState.CONFIRMED_ON_WAITING ->
					{
						it.statusTextView.text = context.getString(R.string.display_host_console_ready_wait)
						it.statusIcon.setColorFilter(android.graphics.Color.parseColor("#22C55E")) // Green-500
					}
					// This tile only shows once local discovery has stopped seeing the console (a
					// still-discovered one renders as a DiscoveredDisplayHost instead — see below).
					// For a short window after that it's still plausibly just mid-boot or
					// mid-lookup; past it, genuinely offline. See PsnDisplayHost.confirmedOffline's
					// doc comment — this is a fixed real-time grace, not session history, so a
					// console that was already off before the app was even opened still gets a
					// definitive verdict instead of sitting in "Getting Console Status" forever.
					host.confirmedOffline ->
					{
						it.statusTextView.text = context.getString(R.string.display_host_console_offline)
						it.statusIcon.setColorFilter(android.graphics.Color.parseColor("#EF4444")) // Red-500
					}
					else ->
					{
						it.statusTextView.text = context.getString(R.string.display_host_getting_status)
						it.statusIcon.setColorFilter(android.graphics.Color.parseColor("#3B82F6")) // Blue-500
					}
				}
			}
			else
			{
				// For local/manual hosts: show address and MAC on left, state on right
				it.hostTextView.text = context.getString(R.string.display_host_host, host.host)
				it.hostTextView.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
				it.hostTextView.textSize = 15f

				// Device ID (MAC address)
				val id = host.id
				if(id != null)
				{
					// Format MAC address nicely (add colons if needed)
					val formatted = if(id.length == 12 && !id.contains(":"))
						id.chunked(2).joinToString(":")
					else
						id
					it.idTextView.text = context.getString(R.string.display_host_mac_format, formatted)
					it.idTextView.visibility = View.VISIBLE
				}
				else
				{
					it.idTextView.visibility = View.GONE
				}

				// State/Status with colored dot on the right. A ManualDisplayHost never carries a
				// live discovery state at all (manual hosts and discovered hosts are always
				// separate, undeduplicated rows — see MainViewModel.combine()), so the console
				// it points at not currently showing up here means exactly one thing either way:
				// it's not reachable right now, i.e. Offline.
				val (stateText, statusIconTint) = when
				{
					transition == HostTransitionState.WAKING ->
						context.getString(R.string.display_host_waking_console) to android.graphics.Color.parseColor("#F97316") // Orange-500
					transition == HostTransitionState.CONFIRMED_ON_WAITING ->
						context.getString(R.string.display_host_console_ready_wait) to android.graphics.Color.parseColor("#22C55E") // Green-500
					isPendingSleep && !(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY) ->
						context.getString(R.string.display_host_console_sleeping) to android.graphics.Color.parseColor("#EAB308") // Yellow-500
					host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.READY ->
						context.getString(R.string.display_host_ready) to android.graphics.Color.parseColor("#22C55E") // Green-500
					host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY ->
						context.getString(R.string.display_host_asleep) to android.graphics.Color.parseColor("#EAB308") // Yellow-500
					else ->
						context.getString(R.string.display_host_offline) to android.graphics.Color.parseColor("#EF4444") // Red-500
				}
				it.statusTextView.text = stateText
				it.statusLayout.visibility = View.VISIBLE
				it.statusIcon.setColorFilter(statusIconTint)
			}
			// Bottom info (app/game running)
			val bottomInfo = (host as? DiscoveredDisplayHost)?.discoveredHost?.let { discoveredHost ->
				if(discoveredHost.runningAppName != null || discoveredHost.runningAppTitleid != null)
					context.getString(R.string.display_host_app_title_id, discoveredHost.runningAppName ?: "", discoveredHost.runningAppTitleid ?: "")
				else
					null
			}
			if(bottomInfo != null)
			{
				it.bottomInfoTextView.text = bottomInfo
				it.bottomInfoTextView.visibility = View.VISIBLE
			}
			else
			{
				it.bottomInfoTextView.visibility = View.GONE
			}
			
			it.stateIndicatorImageView.setImageResource(
				when
				{
					host is PsnDisplayHost -> if(host.isPS5) R.drawable.ic_console_ps5 else R.drawable.ic_console
					host is DiscoveredDisplayHost -> when(host.discoveredHost.state)
					{
						DiscoveryHost.State.STANDBY -> if(host.isPS5) R.drawable.ic_console_ps5_standby else R.drawable.ic_console_standby
						DiscoveryHost.State.READY -> if(host.isPS5) R.drawable.ic_console_ps5_ready else R.drawable.ic_console_ready
						else -> if(host.isPS5) R.drawable.ic_console_ps5 else R.drawable.ic_console
					}
					host.isPS5 -> R.drawable.ic_console_ps5
					else -> R.drawable.ic_console
				}
			)
			val canWakeup = host.registeredHost != null
			val canEditDelete = host is ManualDisplayHost

			val showHostOverflowMenu = {
				val menu = PopupMenu(context, it.menuButton)
				menu.menuInflater.inflate(R.menu.display_host, menu.menu)
				menu.menu.findItem(R.id.action_wakeup).isVisible = canWakeup
				menu.menu.findItem(R.id.action_edit).isVisible = canEditDelete
				menu.menu.findItem(R.id.action_delete).isVisible = canEditDelete
				menu.setOnMenuItemClickListener { menuItem ->
					when (menuItem.itemId)
					{
						R.id.action_wakeup -> wakeupCallback(host)
						R.id.action_edit -> editCallback(host)
						R.id.action_delete -> deleteCallback(host)
						else -> return@setOnMenuItemClickListener false
					}
					true
				}
				menu.show()
			}

			it.root.setOnClickListener { clickCallback(host) }

			if (canWakeup || canEditDelete)
			{
				it.menuButton.isVisible = true
				it.menuButton.setOnClickListener { showHostOverflowMenu() }
			}
			else
			{
				it.menuButton.isGone = true
				it.menuButton.setOnClickListener(null)
			}

			it.root.enableFocusableInTouchModeForTv(context)
			// One focus target per row; overflow via ⋮ tap or long-press on the card.
			it.menuButton.isFocusable = false
			it.menuButton.isFocusableInTouchMode = false
			it.menuButton.isClickable = canWakeup || canEditDelete
			it.root.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
				val density = v.resources.displayMetrics.density
				if (hasFocus)
				{
					it.root.strokeWidth = (2 * density).toInt()
					it.root.strokeColor = resolveThemeColor(v.context, R.attr.pyluxAccent)
				}
				else
				{
					it.root.strokeWidth = (1 * density).toInt()
					it.root.strokeColor = resolveThemeColor(v.context, R.attr.pyluxAccentA20)
				}
			}
			if (canWakeup || canEditDelete)
			{
				it.root.setOnLongClickListener {
					showHostOverflowMenu()
					true
				}
			}
			else
				it.root.setOnLongClickListener(null)
		}
	}
}