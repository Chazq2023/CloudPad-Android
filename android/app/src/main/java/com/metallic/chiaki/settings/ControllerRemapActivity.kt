package com.metallic.chiaki.settings

import android.os.Bundle
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.alertDialogBuilder
import com.metallic.chiaki.common.ext.applyFocusHighlight
import com.metallic.chiaki.session.ControllerAction
import com.metallic.chiaki.session.ControllerRemapCapture
import com.metallic.chiaki.session.PhysicalInput
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityControllerRemapBinding

class ControllerRemapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControllerRemapBinding
    private lateinit var preferences: Preferences
    private lateinit var adapter: RemapAdapter
    private lateinit var capture: ControllerRemapCapture

    private val currentMapping: MutableMap<ControllerAction, PhysicalInput> = mutableMapOf()

    // ---- Activity lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = Preferences(this)
        if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
        super.onCreate(savedInstanceState)
        binding = ActivityControllerRemapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.titleTextView.text = getString(R.string.controller_remap_title)

        preferences = Preferences(this)
        currentMapping.putAll(PhysicalInput.resolveMapping(preferences.loadControllerMapping()))

        capture = ControllerRemapCapture(
            context = this,
            onInputDetected = { action, input ->
                currentMapping[action] = input
                saveAndRefresh()
            },
            onCleared = { action ->
                currentMapping.remove(action)
                saveAndRefresh()
            },
            onDialogClosed = { dropFocusAfterDialog() }
        )

        adapter = RemapAdapter(
            items = buildItems(),
            onActionClick = { action -> capture.startListeningFor(action) },
            onTopBoundary = {
                binding.backButton.isFocusableInTouchMode = true
                binding.backButton.requestFocus()
            },
            onRestoreDefaults = { confirmReset() }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        focusFirstRemapAction()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_controller_remap, menu)
        binding.toolbar.post {
            val overflowButton = findOverflowButton(binding.toolbar)
            overflowButton?.foreground = ContextCompat.getDrawable(this, R.drawable.bg_focus_highlight)
            binding.backButton.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { firstRemapRow()?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        overflowButton?.isFocusableInTouchMode = true
                        overflowButton?.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            overflowButton?.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { firstRemapRow()?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        binding.backButton.isFocusableInTouchMode = true
                        binding.backButton.requestFocus()
                        true
                    }
                    else -> false
                }
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reset_mapping -> { confirmReset(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ---- Activity-level dispatch (handles events when no dialog is showing) ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (capture.isListening) {
            // Mid-capture, B is a candidate remap input like any other button — must not be
            // stolen for back navigation here, unlike everywhere else in this Activity.
            return if (capture.handleCaptureKeyEvent(event)) true else super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (capture.isListening && capture.handleCaptureMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    /**
     * When a dialog closes while a controller is connected, Android restores focus to the
     * RecyclerView and enters D-pad navigation mode, making all visible items appear highlighted.
     * Posting clearFocus() lets Android finish its own focus-restoration pass first, then we
     * clear it so the list returns to its normal (no highlight) resting state.
     */
    private fun dropFocusAfterDialog() {
        binding.recyclerView.post {
            binding.recyclerView.clearFocus()
        }
    }

    private fun confirmReset() {
        alertDialogBuilder()
            .setTitle(R.string.controller_remap_reset_title)
            .setMessage(R.string.controller_remap_reset_message)
            .setPositiveButton(R.string.controller_remap_reset_confirm) { _, _ ->
                currentMapping.clear()
                currentMapping.putAll(PhysicalInput.DEFAULT_MAPPING)
                preferences.clearControllerMapping()
                adapter.updateItems(buildItems())
                restoreFocusToFirstRemapAction()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun saveAndRefresh() {
        preferences.saveControllerMapping(currentMapping)
        adapter.updateItems(buildItems())
    }

    private fun firstRemapRow(): View? =
        (binding.recyclerView.layoutManager as? LinearLayoutManager)?.findViewByPosition(1)

    private fun findOverflowButton(root: ViewGroup): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.javaClass.simpleName.contains("OverflowMenuButton")) return child
            if (child is ViewGroup) findOverflowButton(child)?.let { return it }
        }
        return null
    }

    private fun focusFirstRemapAction() {
        binding.recyclerView.post {
            firstRemapRow()?.requestFocus()
        }
    }

    /** RecyclerView falls back to focusing its whole container after notifyDataSetChanged() runs
     * while the reset dialog is closing. Return to the first mapping row (Left Stick Up) once
     * dismissal has completed so controller navigation resumes at the top of the page. */
    private fun restoreFocusToFirstRemapAction() {
        val firstActionPosition = 1
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        layoutManager.scrollToPosition(firstActionPosition)
        binding.recyclerView.postDelayed({
            layoutManager.findViewByPosition(firstActionPosition)?.requestFocus()
        }, 100)
    }

    private fun buildItems(): List<RemapItem> {
        val items = mutableListOf<RemapItem>()
        var lastGroupRes = 0
        for (action in ControllerAction.values()) {
            if (action.groupRes != lastGroupRes) {
                items.add(RemapItem.Header(getString(action.groupRes)))
                lastGroupRes = action.groupRes
            }
            items.add(RemapItem.ActionItem(action, currentMapping[action]))
        }
        items.add(RemapItem.RestoreDefaults)
        return items
    }
}

// ---- RecyclerView types ----

sealed class RemapItem {
    data class Header(val title: String) : RemapItem()
    data class ActionItem(val action: ControllerAction, val input: PhysicalInput?) : RemapItem()
    data object RestoreDefaults : RemapItem()
}

class RemapAdapter(
    private var items: List<RemapItem>,
    private val onActionClick: (ControllerAction) -> Unit,
    private val onTopBoundary: (() -> Unit)? = null,
    private val onRestoreDefaults: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ACTION = 1
        private const val VIEW_TYPE_RESTORE_DEFAULTS = 2
    }

    fun updateItems(newItems: List<RemapItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is RemapItem.Header -> VIEW_TYPE_HEADER
        is RemapItem.ActionItem -> VIEW_TYPE_ACTION
        RemapItem.RestoreDefaults -> VIEW_TYPE_RESTORE_DEFAULTS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_controller_section, parent, false)
            )
            VIEW_TYPE_ACTION -> ActionViewHolder(
                inflater.inflate(R.layout.item_controller_action, parent, false),
                onActionClick
            ).apply {
                // Focusable so D-pad/keyboard navigation can land on each row individually
                // (matches TrophyAdapter's item-focus treatment for the same reason).
                itemView.isFocusable = true
                itemView.isFocusableInTouchMode = true

                val tv = TypedValue()
                itemView.context.theme.resolveAttribute(R.attr.pyluxAccent, tv, true)
                itemView.applyFocusHighlight(tv.data)
                itemView.setOnKeyListener { v, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val direction = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                        KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                        else -> return@setOnKeyListener false
                    }
                    val next = v.focusSearch(direction)
                    if (next != null && next !== v) return@setOnKeyListener false
                    when (direction) {
                        View.FOCUS_UP -> onTopBoundary?.let { it(); true } ?: false
                        View.FOCUS_DOWN -> false
                        else -> false
                    }
                }
            }
            else -> RestoreDefaultsViewHolder(
                inflater.inflate(R.layout.item_controller_restore_defaults, parent, false),
                onRestoreDefaults
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RemapItem.Header -> (holder as HeaderViewHolder).bind(item.title)
            is RemapItem.ActionItem -> (holder as ActionViewHolder).bind(item.action, item.input)
            RemapItem.RestoreDefaults -> Unit
        }
    }

    override fun getItemCount() = items.size
}

class RestoreDefaultsViewHolder(
    itemView: View,
    onRestoreDefaults: () -> Unit
) : RecyclerView.ViewHolder(itemView) {
    init {
        itemView.setOnClickListener { onRestoreDefaults() }
        val tv = TypedValue()
        itemView.context.theme.resolveAttribute(R.attr.pyluxAccent, tv, true)
        // MaterialButton (item_controller_restore_defaults.xml) — foreground, matching every
        // other MaterialButton's highlight treatment, so it doesn't fight the button's own
        // background/corner radius.
        itemView.applyFocusHighlight(tv.data, useForeground = true)
    }
}

class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val titleView: TextView = itemView.findViewById(R.id.sectionTitle)
    fun bind(title: String) { titleView.text = title }
}

class ActionViewHolder(
    itemView: View,
    private val onActionClick: (ControllerAction) -> Unit
) : RecyclerView.ViewHolder(itemView) {
    private val actionNameView: TextView = itemView.findViewById(R.id.actionName)
    private val currentMappingView: TextView = itemView.findViewById(R.id.currentMapping)

    fun bind(action: ControllerAction, input: PhysicalInput?) {
        actionNameView.text = itemView.context.getString(action.displayNameRes)
        currentMappingView.text = input?.displayName()
            ?: itemView.context.getString(R.string.controller_remap_not_mapped)
        itemView.setOnClickListener { onActionClick(action) }
    }
}
