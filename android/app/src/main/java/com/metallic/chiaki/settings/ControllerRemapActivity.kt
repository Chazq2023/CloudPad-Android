package com.metallic.chiaki.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.common.Preferences
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

        adapter = RemapAdapter(buildItems()) { action -> capture.startListeningFor(action) }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.restoreDefaultsButton.setOnClickListener { confirmReset() }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.controller_remap_reset_title)
            .setMessage(R.string.controller_remap_reset_message)
            .setPositiveButton(R.string.controller_remap_reset_confirm) { _, _ ->
                currentMapping.clear()
                currentMapping.putAll(PhysicalInput.DEFAULT_MAPPING)
                preferences.clearControllerMapping()
                adapter.updateItems(buildItems())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun saveAndRefresh() {
        preferences.saveControllerMapping(currentMapping)
        adapter.updateItems(buildItems())
    }

    private fun buildItems(): List<RemapItem> {
        val items = mutableListOf<RemapItem>()
        var lastGroup = ""
        for (action in ControllerAction.values()) {
            if (action.group != lastGroup) {
                items.add(RemapItem.Header(action.group))
                lastGroup = action.group
            }
            items.add(RemapItem.ActionItem(action, currentMapping[action]))
        }
        return items
    }
}

// ---- RecyclerView types ----

sealed class RemapItem {
    data class Header(val title: String) : RemapItem()
    data class ActionItem(val action: ControllerAction, val input: PhysicalInput?) : RemapItem()
}

class RemapAdapter(
    private var items: List<RemapItem>,
    private val onActionClick: (ControllerAction) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ACTION = 1
    }

    fun updateItems(newItems: List<RemapItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is RemapItem.Header -> VIEW_TYPE_HEADER
        is RemapItem.ActionItem -> VIEW_TYPE_ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_controller_section, parent, false)
            )
            else -> ActionViewHolder(
                inflater.inflate(R.layout.item_controller_action, parent, false),
                onActionClick
            ).apply {
                // Focusable so D-pad/keyboard navigation can land on each row individually
                // (matches TrophyAdapter's item-focus treatment for the same reason).
                itemView.isFocusable = true
                itemView.isFocusableInTouchMode = true

                val originalBackground = itemView.background
                val tv = TypedValue()
                itemView.context.theme.resolveAttribute(R.attr.pyluxAccent, tv, true)
                val accent = tv.data
                itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    v.background = if (hasFocus)
                        GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setColor((0x30 shl 24) or (accent and 0x00FFFFFF))
                            setStroke(2, (0x99 shl 24) or (accent and 0x00FFFFFF))
                        }
                    else
                        originalBackground
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is RemapItem.Header -> (holder as HeaderViewHolder).bind(item.title)
            is RemapItem.ActionItem -> (holder as ActionViewHolder).bind(item.action, item.input)
        }
    }

    override fun getItemCount() = items.size
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
        actionNameView.text = action.displayName
        currentMappingView.text = input?.displayName()
            ?: itemView.context.getString(R.string.controller_remap_not_mapped)
        itemView.setOnClickListener { onActionClick(action) }
    }
}
