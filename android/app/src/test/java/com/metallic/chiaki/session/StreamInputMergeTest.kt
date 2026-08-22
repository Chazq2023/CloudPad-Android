package com.metallic.chiaki.session

import android.view.Surface
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.lib.ControllerTouch
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies [mergeControllerStates] (the single-allocation replacement for chaining three
 *  [ControllerState.or] calls, see its own doc comment) reproduces exactly what that old chain
 *  used to compute, for a representative set of sensor/key/motion/touch combinations — including
 *  the trickiest part, the motion-analog-overrides-digital-key L2/R2 behavior. */
class StreamInputMergeTest {

    /** Exactly the old getter's computation (`sensorControllerState or keyControllerState or
     *  motionControllerState`, rotation flip, l2/r2 override, `... or touchControllerState`) —
     *  kept independent of [mergeControllerStates] so this test actually cross-checks the
     *  rewrite against the original algorithm rather than against a copy of itself. */
    private fun oldChainAlgorithm(
        sensor: ControllerState,
        key: ControllerState,
        motion: ControllerState,
        touch: ControllerState,
        rotation: Int
    ): ControllerState {
        val controllerState = sensor or key or motion

        if (rotation == Surface.ROTATION_90) {
            controllerState.accelX *= -1.0f
            controllerState.accelZ *= -1.0f
            controllerState.gyroX *= -1.0f
            controllerState.gyroZ *= -1.0f
            controllerState.orientX *= -1.0f
            controllerState.orientZ *= -1.0f
        }

        if (motion.l2State > 0U) controllerState.l2State = motion.l2State
        if (motion.r2State > 0U) controllerState.r2State = motion.r2State

        return controllerState or touch
    }

    private fun state(
        buttons: UInt = 0U,
        l2State: UByte = 0U,
        r2State: UByte = 0U,
        leftX: Short = 0,
        leftY: Short = 0,
        rightX: Short = 0,
        rightY: Short = 0,
        touches: Array<ControllerTouch> = arrayOf(ControllerTouch(), ControllerTouch()),
        gyroX: Float = 0f, gyroY: Float = 0f, gyroZ: Float = 0f,
        accelX: Float = 0f, accelY: Float = 1f, accelZ: Float = 0f,
        orientX: Float = 0f, orientY: Float = 0f, orientZ: Float = 0f, orientW: Float = 1f
    ) = ControllerState(
        buttons = buttons, l2State = l2State, r2State = r2State,
        leftX = leftX, leftY = leftY, rightX = rightX, rightY = rightY,
        touches = touches,
        gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ,
        accelX = accelX, accelY = accelY, accelZ = accelZ,
        orientX = orientX, orientY = orientY, orientZ = orientZ, orientW = orientW
    )

    private fun assertMatchesOldAlgorithm(
        sensor: ControllerState, key: ControllerState, motion: ControllerState, touch: ControllerState,
        rotation: Int = Surface.ROTATION_0
    ) {
        val expected = oldChainAlgorithm(sensor, key, motion, touch, rotation)
        val actual = mergeControllerStates(sensor, key, motion, touch, rotation)
        assertEquals(expected, actual)
    }

    @Test
    fun `all-default states merge to a default state`() {
        assertMatchesOldAlgorithm(state(), state(), state(), state())
    }

    @Test
    fun `buttons OR across all four sources`() {
        assertMatchesOldAlgorithm(
            sensor = state(buttons = ControllerState.BUTTON_L1),
            key = state(buttons = ControllerState.BUTTON_CROSS),
            motion = state(buttons = ControllerState.BUTTON_DPAD_UP),
            touch = state()
        )
    }

    @Test
    fun `motion analog L2 overrides a harder digital key L2 press`() {
        assertMatchesOldAlgorithm(
            sensor = state(),
            key = state(l2State = 255U), // full digital press
            motion = state(l2State = 40U), // light analog squeeze — should still win
            touch = state()
        )
    }

    @Test
    fun `motion analog R2 overrides a harder digital key R2 press`() {
        assertMatchesOldAlgorithm(
            sensor = state(),
            key = state(r2State = 255U),
            motion = state(r2State = 12U),
            touch = state()
        )
    }

    @Test
    fun `key L2 wins when motion has no analog value`() {
        assertMatchesOldAlgorithm(
            sensor = state(),
            key = state(l2State = 200U),
            motion = state(l2State = 0U),
            touch = state()
        )
    }

    @Test
    fun `stick axes take whichever source has the largest magnitude`() {
        assertMatchesOldAlgorithm(
            sensor = state(),
            key = state(),
            motion = state(leftX = -12000, leftY = 8000, rightX = 30000, rightY = -1500),
            touch = state()
        )
    }

    @Test
    fun `active touch from touchControllerState is preserved`() {
        assertMatchesOldAlgorithm(
            sensor = state(),
            key = state(),
            motion = state(),
            touch = state(touches = arrayOf(ControllerTouch(x = 500U, y = 300U, id = 1), ControllerTouch()))
        )
    }

    @Test
    fun `no active touch anywhere yields the default inert touch pair`() {
        assertMatchesOldAlgorithm(state(), state(), state(), state())
    }

    @Test
    fun `gyro accel orient always come from sensor regardless of other sources`() {
        assertMatchesOldAlgorithm(
            sensor = state(gyroX = 1f, gyroY = 2f, gyroZ = 3f, accelX = 0.1f, accelY = 0.2f, accelZ = 0.3f, orientX = 0.4f, orientY = 0.5f, orientZ = 0.6f, orientW = 0.7f),
            key = state(buttons = ControllerState.BUTTON_CROSS),
            motion = state(leftX = 100),
            touch = state(touches = arrayOf(ControllerTouch(x = 1U, y = 1U, id = 0), ControllerTouch()))
        )
    }

    @Test
    fun `rotation 90 flips accel gyro orient X and Z from sensor`() {
        assertMatchesOldAlgorithm(
            sensor = state(gyroX = 1f, gyroY = 2f, gyroZ = 3f, accelX = 0.1f, accelY = 0.2f, accelZ = 0.3f, orientX = 0.4f, orientY = 0.5f, orientZ = 0.6f, orientW = 0.7f),
            key = state(),
            motion = state(),
            touch = state(),
            rotation = Surface.ROTATION_90
        )
    }

    @Test
    fun `rotation other than 90 leaves accel gyro orient unflipped`() {
        assertMatchesOldAlgorithm(
            sensor = state(gyroX = 1f, gyroZ = 3f, accelX = 0.1f, accelZ = 0.3f, orientX = 0.4f, orientZ = 0.6f),
            key = state(),
            motion = state(),
            touch = state(),
            rotation = Surface.ROTATION_270
        )
    }

    @Test
    fun `realistic combined frame matches old algorithm`() {
        assertMatchesOldAlgorithm(
            sensor = state(gyroX = -0.5f, gyroY = 0.25f, gyroZ = 0.1f, accelX = 0.02f, accelY = 0.98f, accelZ = -0.01f),
            key = state(buttons = ControllerState.BUTTON_R1 or ControllerState.BUTTON_OPTIONS, l2State = 255U),
            motion = state(leftX = -8000, leftY = 4000, l2State = 90U, buttons = ControllerState.BUTTON_DPAD_LEFT),
            touch = state(touches = arrayOf(ControllerTouch(x = 960U, y = 471U, id = 2), ControllerTouch())),
            rotation = Surface.ROTATION_90
        )
    }
}
