package com.metallic.chiaki.settings

import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.session.ControllerAction
import com.metallic.chiaki.session.PhysicalInput
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivityControllerRemapBinding
import kotlin.math.abs

class ControllerRemapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControllerRemapBinding
    private lateinit var preferences: Preferences
    private lateinit var adapter: RemapAdapter

    private val currentMapping: MutableMap<ControllerAction, PhysicalInput> = mutableMapOf()

    private var listeningForAction: ControllerAction? = null
    private var listenDialog: AlertDialog? = null
    private var captureModifier: Int? = null

    // ---- Custom dialog that routes controller events back to this activity ----

    private inner class InputCaptureDialog : AlertDialog(this@ControllerRemapActivity) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (listeningForAction != null && handleCaptureKeyEvent(event)) return true
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (listeningForAction != null && handleCaptureMotionEvent(event)) return true
            return super.dispatchGenericMotionEvent(event)
        }
    }

    // ---- Activity lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = Preferences(this)
        if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
        super.onCreate(savedInstanceState)
        binding = ActivityControllerRemapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.titleTextView.text = getString(R.string.controller_remap_title)

        preferences = Preferences(this)
        val saved = preferences.loadControllerMapping()
        currentMapping.putAll(if (saved.isEmpty()) PhysicalInput.DEFAULT_MAPPING else saved)

        adapter = RemapAdapter(buildItems()) { action -> startListeningFor(action) }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_controller_remap, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_reset_mapping -> { confirmReset(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ---- Activity-level dispatch (handles events when no dialog is showing) ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (listeningForAction != null && handleCaptureKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (listeningForAction != null && handleCaptureMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    // ---- Shared capture logic (called from both activity and dialog dispatch) ----

    fun handleCaptureKeyEvent(event: KeyEvent): Boolean {
        // Ignore held-key repeat events
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true

        val ignoredKeyCodes = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU
        )
        if (event.keyCode in ignoredKeyCodes) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                listenDialog?.dismiss()
                cancelListening()
            }
            return true
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val mod = captureModifier
                when {
                    mod == null -> {
                        // First key down — record as potential modifier
                        captureModifier = event.keyCode
                        listenDialog?.setMessage(
                            getString(
                                R.string.controller_remap_modifier_held,
                                PhysicalInput.formatKeyCode(event.keyCode)
                            )
                        )
                    }
                    mod != event.keyCode -> {
                        // Second different key while first still held — save as combo
                        onInputDetected(PhysicalInput.Combo(mod, PhysicalInput.Button(event.keyCode)))
                    }
                }
            }
            KeyEvent.ACTION_UP -> {
                val mod = captureModifier
                if (mod != null && mod == event.keyCode) {
                    // Modifier released without a second input — save as single button
                    onInputDetected(PhysicalInput.Button(mod))
                }
            }
        }
        return true
    }

    fun handleCaptureMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false

        val axes = listOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        )
        for (axis in axes) {
            val value = event.getAxisValue(axis)
            if (abs(value) > 0.8f) {
                val axisInput = PhysicalInput.AxisDirection(axis, value > 0)
                val mod = captureModifier
                onInputDetected(if (mod != null) PhysicalInput.Combo(mod, axisInput) else axisInput)
                return true
            }
        }
        return false
    }

    // ---- Dialog management ----

    private fun startListeningFor(action: ControllerAction) {
        listeningForAction = action
        captureModifier = null

        val dialog = InputCaptureDialog()
        dialog.setTitle(action.displayName)
        dialog.setMessage(getString(R.string.controller_remap_press_button))
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.action_cancel)) { _, _ ->
            cancelListening()
        }
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, getString(R.string.controller_remap_clear)) { _, _ ->
            currentMapping.remove(action)
            saveAndRefresh()
            cancelListening()
        }
        dialog.setCancelable(false)
        listenDialog = dialog
        dialog.show()
    }

    private fun onInputDetected(input: PhysicalInput) {
        val action = listeningForAction ?: return
        listenDialog?.dismiss()
        listenDialog = null
        listeningForAction = null
        captureModifier = null

        currentMapping[action] = input
        saveAndRefresh()
        dropFocusAfterDialog()
    }

    private fun cancelListening() {
        listeningForAction = null
        captureModifier = null
        listenDialog = null
        dropFocusAfterDialog()
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
            )
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
