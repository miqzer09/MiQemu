package com.omnidroid.app.shared.input.omnidroiddevice

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import com.omnidroid.app.shared.input.InputDeviceManager
import com.omnidroid.app.shared.input.InputKey
import com.omnidroid.app.shared.input.RetroKey
import com.omnidroid.app.shared.input.bindingsOf
import com.omnidroid.app.shared.input.inputKeysOf
import com.omnidroid.app.shared.input.inputclass.getInputClass
import com.omnidroid.app.shared.input.retroKeysOf
import com.omnidroid.app.shared.input.supportsAllKeys
import com.omnidroid.app.shared.settings.GameShortcutType

class OmnidroidInputDeviceGamePad(private val device: InputDevice) : OmnidroidInputDevice {
    override fun getDefaultBindings(): Map<InputKey, RetroKey> {
        val allAvailableInputs =
            InputDeviceManager.OUTPUT_KEYS
                .associate {
                    InputKey(it.keyCode) to getDefaultBindingForKey(device, it)
                }

        // Follows mupen64plus-ae's [Xbox] controller profile (assets/mupen64plus_data/profiles/controller.cfg):
        // N64 A on the bottom face button (6:99 style: N64 controls live on A/X), shoulders on L1/R1,
        // triggers as L2/R2. The RetroPad A/X ids are only consumed by the core as C-buttons while
        // the C-mode modifier is held, so the remaining face buttons get real N64 functions:
        // B = Z trigger (same as the left-trigger axis path) and Y = C-button mode modifier.
        val defaultOverride =
            bindingsOf(
                // Bottom face button -> RetroPad B = N64 A.
                KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B,
                // Left face button -> RetroPad Y = N64 B (mupen64plus-ae [Xbox] 6:99 pattern).
                KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_Y,
                // Right face button -> RetroPad L2 = N64 Z.
                KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_L2,
                // Top face button -> RetroPad R2 = C-buttons mode modifier.
                KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_BUTTON_R2,
            )

        return allAvailableInputs + defaultOverride
    }

    private fun getDefaultBindingForKey(
        device: InputDevice,
        it: RetroKey,
    ): RetroKey {
        val defaultBinding =
            if (device.hasKeys(it.keyCode).first()) {
                RetroKey(it.keyCode)
            } else {
                RetroKey(KeyEvent.KEYCODE_UNKNOWN)
            }
        return defaultBinding
    }

    override fun isEnabledByDefault(appContext: Context): Boolean {
        return device.supportsAllKeys(MINIMAL_KEYS_DEFAULT_ENABLED)
    }

    override fun getSupportedShortcuts(): List<GameShortcutType> = GameShortcutType.values().toList()

    override fun isSupported(): Boolean {
        return sequenceOf(
            device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD,
            device.supportsAllKeys(MINIMAL_SUPPORTED_KEYS),
            device.isVirtual.not(),
            device.controllerNumber > 0,
        ).all { it }
    }

    override fun getCustomizableKeys(): List<RetroKey> {
        val deviceAxis =
            device.motionRanges
                .map { it.axis }
                .toSet()

        val keysMappedToAxis =
            device.getInputClass().getAxesMap()
                .filter { it.key in deviceAxis }
                .map { it.value }
                .toSet()

        return CUSTOMIZABLE_KEYS
            .filter { it.keyCode !in keysMappedToAxis }
    }

    companion object {
        private val MINIMAL_SUPPORTED_KEYS =
            inputKeysOf(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
            )

        private val MINIMAL_KEYS_DEFAULT_ENABLED =
            MINIMAL_SUPPORTED_KEYS +
                inputKeysOf(
                    KeyEvent.KEYCODE_BUTTON_START,
                    KeyEvent.KEYCODE_BUTTON_SELECT,
                )

        private val CUSTOMIZABLE_KEYS: List<RetroKey> =
            retroKeysOf(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_THUMBL,
                KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_BUTTON_MODE,
            )
    }
}
