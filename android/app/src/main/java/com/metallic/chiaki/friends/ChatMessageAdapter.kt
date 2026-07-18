// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.pylux.stream.R
import com.pylux.stream.databinding.ItemChatMessageBinding

/** Single item layout, branching on [ChatMessage.isMine] at bind time to flip bubble side/colour
 *  — mirrors how [com.metallic.chiaki.trophy.TrophyAdapter] branches trophy-type styling in
 *  bind() rather than using a separate view type per case. */
class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder>()
{
	var items: List<ChatMessage> = emptyList()
		set(value) { field = value; notifyDataSetChanged() }

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder =
		MessageViewHolder(ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun onBindViewHolder(holder: MessageViewHolder, position: Int) = holder.bind(items[position])

	override fun getItemCount(): Int = items.size

	class MessageViewHolder(private val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)
	{
		fun bind(message: ChatMessage)
		{
			binding.chatMessageBubble.text = message.body
			(binding.chatMessageRoot as LinearLayout).gravity = if (message.isMine) Gravity.START else Gravity.END
			binding.chatMessageBubble.setBackgroundResource(
				if (message.isMine) R.drawable.bg_chat_bubble_mine else R.drawable.bg_chat_bubble_theirs
			)
		}
	}
}
