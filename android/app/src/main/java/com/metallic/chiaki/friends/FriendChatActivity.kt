// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.friends

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.metallic.chiaki.common.Preferences
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityFriendChatBinding
import kotlinx.coroutines.launch

class FriendChatActivity : AppCompatActivity()
{
	companion object
	{
		private const val EXTRA_ACCOUNT_ID = "extra_account_id"
		private const val EXTRA_ONLINE_ID = "extra_online_id"

		fun start(context: Context, friend: Friend)
		{
			val intent = Intent(context, FriendChatActivity::class.java)
			intent.putExtra(EXTRA_ACCOUNT_ID, friend.accountId)
			intent.putExtra(EXTRA_ONLINE_ID, friend.onlineId)
			context.startActivity(intent)
		}
	}

	private lateinit var binding: ActivityFriendChatBinding
	private lateinit var repository: FriendsRepository
	private val adapter = ChatMessageAdapter()
	private var groupId: String? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		binding = ActivityFriendChatBinding.inflate(layoutInflater)
		setContentView(binding.root)

		setSupportActionBar(binding.toolbar)
		supportActionBar?.setDisplayHomeAsUpEnabled(true)

		val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: ""
		val onlineId = intent.getStringExtra(EXTRA_ONLINE_ID) ?: ""
		binding.chatTitleTextView.text = onlineId

		repository = FriendsRepository(prefs)

		// stackFromEnd so the list starts pinned to the newest message, like any chat UI.
		val layoutManager = LinearLayoutManager(this)
		layoutManager.stackFromEnd = true
		binding.chatRecyclerView.layoutManager = layoutManager
		binding.chatRecyclerView.adapter = adapter

		binding.chatSendButton.setOnClickListener { sendCurrentMessage() }

		openConversation(accountId)
	}

	private fun openConversation(accountId: String)
	{
		binding.chatProgressBar.visibility = View.VISIBLE
		binding.chatEmptyStateText.visibility = View.GONE
		binding.chatRecyclerView.visibility = View.GONE

		lifecycleScope.launch {
			when (val result = repository.openConversation(accountId))
			{
				is ConversationResult.Success -> {
					groupId = result.groupId
					showMessages(result.messages)
				}
				is ConversationResult.Error -> {
					// Still capture the group id if the DM group itself was created fine and only
					// the history fetch failed — lets the user send even though history didn't load.
					groupId = result.groupId
					showEmptyState(result.message)
				}
			}
		}
	}

	private fun sendCurrentMessage()
	{
		val text = binding.chatMessageInput.text?.toString()?.trim() ?: ""
		val activeGroupId = groupId
		if (text.isEmpty() || activeGroupId == null) return

		binding.chatMessageInput.setText("")

		// Optimistic append — shows the sent message immediately rather than waiting on the
		// send + re-fetch round trip, matching how any messenger app behaves. Reconciled with
		// the server's own view once refreshConversation comes back below.
		showMessages(adapter.items + ChatMessage(text, "", isMine = true, timestampMs = System.currentTimeMillis()))

		lifecycleScope.launch {
			repository.sendMessage(activeGroupId, text)
			when (val result = repository.refreshConversation(activeGroupId))
			{
				is ConversationResult.Success -> showMessages(result.messages)
				is ConversationResult.Error -> { /* keep the optimistic state on screen */ }
			}
		}
	}

	private fun showMessages(messages: List<ChatMessage>)
	{
		binding.chatProgressBar.visibility = View.GONE

		if (messages.isEmpty())
		{
			showEmptyState(getString(R.string.friend_chat_empty_state))
			return
		}

		adapter.items = messages
		binding.chatEmptyStateText.visibility = View.GONE
		binding.chatRecyclerView.visibility = View.VISIBLE
		binding.chatRecyclerView.scrollToPosition(messages.size - 1)
	}

	private fun showEmptyState(message: String)
	{
		binding.chatProgressBar.visibility = View.GONE
		binding.chatRecyclerView.visibility = View.GONE
		binding.chatEmptyStateText.text = message
		binding.chatEmptyStateText.visibility = View.VISIBLE
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.menu_friend_chat, menu)
		// Same fix as FriendsActivity's toolbar: the default menu-icon tint doesn't pick up
		// ic_refresh's own hardcoded fill colour, rendering it dark against this dark toolbar.
		menu.findItem(R.id.action_refresh_chat)?.icon?.mutate()?.setTint(Color.WHITE)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId)
	{
		android.R.id.home -> { finish(); true }
		R.id.action_refresh_chat -> { refreshConversation(); true }
		else -> super.onOptionsItemSelected(item)
	}

	/** Re-fetches the open conversation on demand — same call made right after sending, just
	 *  triggerable manually so the latest messages (e.g. a friend's reply) show up without
	 *  leaving and re-entering the screen. */
	private fun refreshConversation()
	{
		val activeGroupId = groupId ?: return
		binding.chatProgressBar.visibility = View.VISIBLE
		lifecycleScope.launch {
			when (val result = repository.refreshConversation(activeGroupId))
			{
				is ConversationResult.Success -> showMessages(result.messages)
				is ConversationResult.Error -> {
					binding.chatProgressBar.visibility = View.GONE
					showEmptyState(result.message)
				}
			}
		}
	}

	/** Triangle/Y as a controller shortcut for the refresh button — same convention MainActivity
	 *  already uses for CloudPlayFragment's own refresh. */
	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_Y)
		{
			refreshConversation()
			return true
		}
		return super.dispatchKeyEvent(event)
	}
}
