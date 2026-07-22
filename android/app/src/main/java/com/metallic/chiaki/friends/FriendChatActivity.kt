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
import com.metallic.chiaki.common.ext.redirectDpadDownTo
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

	/** True once the user has actively entered the message history for D-pad scrolling (via
	 *  A/Cross or a tap while it's merely D-pad-highlighted) — see chatRecyclerView's key/click
	 *  listeners below. False just means it's a normal focus target like any other control:
	 *  up/down move focus elsewhere as usual, and B closes the screen like everywhere else. */
	private var chatHistoryEntered = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
		super.onCreate(savedInstanceState)

		binding = ActivityFriendChatBinding.inflate(layoutInflater)
		setContentView(binding.root)

		setSupportActionBar(binding.toolbar)
		binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
		binding.backButton.redirectDpadDownTo {
			binding.chatRecyclerView.isFocusableInTouchMode = true
			binding.chatRecyclerView
		}

		val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: ""
		val onlineId = intent.getStringExtra(EXTRA_ONLINE_ID) ?: ""
		binding.chatTitleTextView.text = onlineId

		repository = FriendsRepository(prefs)

		// stackFromEnd so the list starts pinned to the newest message, like any chat UI.
		val layoutManager = LinearLayoutManager(this)
		layoutManager.stackFromEnd = true
		binding.chatRecyclerView.layoutManager = layoutManager
		binding.chatRecyclerView.adapter = adapter

		// D-pad/controller selecting the message history only highlights it, same as any other
		// focusable control — up/down move focus elsewhere as normal until the user explicitly
		// enters it (A/Cross, handled in dispatchKeyEvent below, or a tap, here) at which point
		// up/down scroll the chat log instead (messages aren't individually focusable rows — this
		// is a scrollable pane). B exits back to the same "just highlighted" state, after which
		// up/down resume normal navigation to the input row.
		binding.chatRecyclerView.setOnClickListener { chatHistoryEntered = true }
		val chatScrollStepPx = (160f * resources.displayMetrics.density).toInt()
		binding.chatRecyclerView.setOnKeyListener { v, keyCode, event ->
			if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
			if (!chatHistoryEntered)
			{
				// Just D-pad-highlighted, not entered — up/down should move focus elsewhere as
				// normal, but both directions need an explicit redirect rather than relying on
				// the platform's own focus search: UP because RecyclerView.focusSearch() contains
				// arrow-key search to its own subtree rather than escaping to a sibling control
				// outside it (see redirectDpadUpAtListBoundary's doc comment for the general case;
				// this is a hand-rolled variant since the container itself, not a row, holds focus
				// here), and DOWN because default search prefers chatSendButton over
				// chatMessageInput despite the input spanning most of the row's width — confirmed
				// on-device — landing on the button first reads as skipping straight past typing.
				if (keyCode == KeyEvent.KEYCODE_DPAD_UP)
				{
					val next = v.focusSearch(View.FOCUS_UP)
					if (next == null || next == v)
					{
						// isFocusableInTouchMode flip needed — confirmed on-device that
						// requestFocus() alone silently fails here (still in touch mode at this
						// point), same as redirectDpadUpAtListBoundary's doc comment.
						binding.backButton.isFocusableInTouchMode = true
						binding.backButton.requestFocus()
						return@setOnKeyListener true
					}
				}
				if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
				{
					binding.chatMessageInput.requestFocus()
					return@setOnKeyListener true
				}
				return@setOnKeyListener false
			}
			when (keyCode)
			{
				KeyEvent.KEYCODE_DPAD_UP -> { binding.chatRecyclerView.smoothScrollBy(0, -chatScrollStepPx); true }
				KeyEvent.KEYCODE_DPAD_DOWN -> { binding.chatRecyclerView.smoothScrollBy(0, chatScrollStepPx); true }
				KeyEvent.KEYCODE_BUTTON_B -> { chatHistoryEntered = false; true }
				else -> false
			}
		}

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
	 *  already uses for CloudPlayFragment's own refresh. BUTTON_A (Cross) isn't one of Android's
	 *  built-in "confirm" keycodes (only DPAD_CENTER/ENTER are — same gap QuickSettingsPanel's own
	 *  key handling documents), so entering chat-scroll mode needs it translated by hand here,
	 *  same as a tap would via chatRecyclerView's click listener above. Circle/B mirrors the back
	 *  button, but only once the view hierarchy itself hasn't already consumed it —
	 *  chatRecyclerView's own key listener needs first refusal so B exits chat-scroll-focus
	 *  instead of closing this whole screen while the message history is entered. */
	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_Y)
		{
			refreshConversation()
			return true
		}
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_A &&
			!chatHistoryEntered && binding.chatRecyclerView.hasFocus())
		{
			chatHistoryEntered = true
			return true
		}
		if (super.dispatchKeyEvent(event)) return true
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
		{
			onBackPressedDispatcher.onBackPressed()
			return true
		}
		return false
	}
}
