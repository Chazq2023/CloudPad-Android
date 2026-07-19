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

			// For PSN hosts: just show "Remote Console" and a "Finding console" status.
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
				it.hostTextView.text = "Remote Console"
				it.hostTextView.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
				it.hostTextView.textSize = 16f
				it.idTextView.visibility = View.GONE
				it.statusLayout.visibility = View.VISIBLE
				it.statusTextView.text = "Finding console"
				it.statusIcon.setColorFilter(android.graphics.Color.parseColor("#3B82F6")) // Blue-500
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
					it.idTextView.text = "MAC: $formatted"
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
					host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.READY ->
						"Ready" to android.graphics.Color.parseColor("#22C55E") // Green-500
					host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY ->
						"Asleep" to android.graphics.Color.parseColor("#EAB308") // Yellow-500
					else ->
						"Offline" to android.graphics.Color.parseColor("#EF4444") // Red-500
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
			it.root.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
				if (hasFocus)
				{
					it.root.strokeWidth = 4
					it.root.strokeColor = android.graphics.Color.parseColor("#FFFFD700")
					it.root.foreground = android.graphics.drawable.GradientDrawable().apply {
						shape = android.graphics.drawable.GradientDrawable.RECTANGLE
						cornerRadius = 40f
						setColor(0x22FFD700.toInt())
					}
				}
				else
				{
					it.root.strokeWidth = 0
					it.root.foreground = null
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