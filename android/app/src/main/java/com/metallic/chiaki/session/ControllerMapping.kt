package com.metallic.chiaki.session

import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject

sealed class PhysicalInput {
    data class Button(val keyCode: Int) : PhysicalInput()
    data class AxisDirection(val axis: Int, val positive: Boolean) : PhysicalInput()
    /** Held modifier key + a second input as trigger. Trigger must be Button or AxisDirection, not another Combo. */
    data class Combo(val modifierKeyCode: Int, val trigger: PhysicalInput) : PhysicalInput()

    fun displayName(): String = when (this) {
        is Button -> formatKeyCode(keyCode)
        is AxisDirection -> "${MotionEvent.axisToString(axis)} ${if (positive) "(+)" else "(-)"}"
        is Combo -> "${formatKeyCode(modifierKeyCode)} + ${trigger.displayName()}"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@PhysicalInput) {
            is Button -> { put("type", "button"); put("keyCode", keyCode) }
            is AxisDirection -> { put("type", "axis"); put("axis", axis); put("positive", positive) }
            is Combo -> {
                put("type", "combo")
                put("modifierKeyCode", modifierKeyCode)
                put("trigger", trigger.toJson())
            }
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): PhysicalInput? = runCatching {
            when (obj.getString("type")) {
                "button" -> Button(obj.getInt("keyCode"))
                "axis" -> AxisDirection(obj.getInt("axis"), obj.getBoolean("positive"))
                "combo" -> {
                    val modifier = obj.getInt("modifierKeyCode")
                    val trigger = fromJson(obj.getJSONObject("trigger")) ?: return@runCatching null
                    Combo(modifier, trigger)
                }
                else -> null
            }
        }.getOrNull()

        fun formatKeyCode(keyCode: Int): String =
            KeyEvent.keyCodeToString(keyCode)
                .removePrefix("KEYCODE_")
                .replace("_", " ")
                .lowercase()
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

        val DEFAULT_MAPPING: Map<ControllerAction, PhysicalInput> = mapOf(
            ControllerAction.CROSS to Button(KeyEvent.KEYCODE_BUTTON_A),
            ControllerAction.CIRCLE to Button(KeyEvent.KEYCODE_BUTTON_B),
            ControllerAction.SQUARE to Button(KeyEvent.KEYCODE_BUTTON_X),
            ControllerAction.TRIANGLE to Button(KeyEvent.KEYCODE_BUTTON_Y),
            ControllerAction.L1 to Button(KeyEvent.KEYCODE_BUTTON_L1),
            ControllerAction.R1 to Button(KeyEvent.KEYCODE_BUTTON_R1),
            ControllerAction.L2 to AxisDirection(MotionEvent.AXIS_LTRIGGER, true),
            ControllerAction.R2 to AxisDirection(MotionEvent.AXIS_RTRIGGER, true),
            ControllerAction.L3 to Button(KeyEvent.KEYCODE_BUTTON_THUMBL),
            ControllerAction.R3 to Button(KeyEvent.KEYCODE_BUTTON_THUMBR),
            ControllerAction.START to Button(KeyEvent.KEYCODE_BUTTON_START),
            ControllerAction.SELECT to Button(KeyEvent.KEYCODE_BUTTON_SELECT),
            ControllerAction.HOME to Button(KeyEvent.KEYCODE_BUTTON_MODE),
            ControllerAction.DPAD_UP to AxisDirection(MotionEvent.AXIS_HAT_Y, false),
            ControllerAction.DPAD_DOWN to AxisDirection(MotionEvent.AXIS_HAT_Y, true),
            ControllerAction.DPAD_LEFT to AxisDirection(MotionEvent.AXIS_HAT_X, false),
            ControllerAction.DPAD_RIGHT to AxisDirection(MotionEvent.AXIS_HAT_X, true),
            ControllerAction.LEFT_STICK_LEFT to AxisDirection(MotionEvent.AXIS_X, false),
            ControllerAction.LEFT_STICK_RIGHT to AxisDirection(MotionEvent.AXIS_X, true),
            ControllerAction.LEFT_STICK_UP to AxisDirection(MotionEvent.AXIS_Y, false),
            ControllerAction.LEFT_STICK_DOWN to AxisDirection(MotionEvent.AXIS_Y, true),
            ControllerAction.RIGHT_STICK_LEFT to AxisDirection(MotionEvent.AXIS_Z, false),
            ControllerAction.RIGHT_STICK_RIGHT to AxisDirection(MotionEvent.AXIS_Z, true),
            ControllerAction.RIGHT_STICK_UP to AxisDirection(MotionEvent.AXIS_RZ, false),
            ControllerAction.RIGHT_STICK_DOWN to AxisDirection(MotionEvent.AXIS_RZ, true),
        )

        fun mappingToJson(mapping: Map<ControllerAction, PhysicalInput>): String {
            val obj = JSONObject()
            for ((action, input) in mapping) {
                obj.put(action.name, input.toJson())
            }
            return obj.toString()
        }

        fun mappingFromJson(json: String): Map<ControllerAction, PhysicalInput> {
            return runCatching {
                val obj = JSONObject(json)
                val result = mutableMapOf<ControllerAction, PhysicalInput>()
                for (action in ControllerAction.values()) {
                    if (obj.has(action.name)) {
                        val input = fromJson(obj.getJSONObject(action.name))
                        if (input != null) result[action] = input
                    }
                }
                result
            }.getOrDefault(emptyMap())
        }
    }
}
