package com.omnidroid.app.shared.game.viewmodel

import android.content.Context
import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.omnidroid.R
import com.omnidroid.app.shared.input.InputDeviceManager
import com.omnidroid.app.shared.input.InputKey
import com.omnidroid.app.shared.input.RetroKey
import com.omnidroid.app.shared.input.inputclass.getInputClass
import com.omnidroid.app.shared.settings.ControllerConfigsManager
import com.omnidroid.app.shared.settings.GameShortcut
import com.omnidroid.app.shared.settings.GameShortcutType
import com.omnidroid.common.coroutines.launchOnState
import com.omnidroid.common.coroutines.safeCollect
import com.omnidroid.common.kotlin.NTuple2
import com.omnidroid.common.kotlin.NTuple4
import com.omnidroid.common.kotlin.filterNotNullValues
import com.omnidroid.common.kotlin.toIndexedMap
import com.omnidroid.common.kotlin.zipOnKeys
import com.omnidroid.lib.controller.ControllerConfig
import com.omnidroid.lib.library.GameSystem
import com.omnidroid.lib.library.SystemCoreConfig
import com.omnidroid.lib.library.SystemID
import com.swordfish.libretrodroid.Controller
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_ANALOG_LEFT
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_ANALOG_RIGHT
import com.swordfish.libretrodroid.GLRetroView.Companion.MOTION_SOURCE_DPAD
import com.omnidroid.touchinput.radial.sensors.TiltConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

class GameViewModelInput(
    private val appContext: Context,
    private val system: GameSystem,
    private val systemCoreConfig: SystemCoreConfig,
    private val inputDeviceManager: InputDeviceManager,
    private val controllerConfigsManager: ControllerConfigsManager,
    private val retroGameView: GameViewModelRetroGameView,
    private val tilt: GameViewModelTilt,
    private val sideEffects: GameViewModelSideEffects,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    private data class SingleAxisEvent(val axis: Int, val action: Int, val keyCode: Int, val port: Int)

    // Snapshot of the (InputDevice?) -> Int? port mapper, bindings and shortcuts. These are collected
    // into plain fields by their own collectors so that the hot key/motion collectors can subscribe
    // immediately at ViewModel creation instead of waiting inside combine() for a preference flow's
    // first emission - a stall that left all gamepad input dead in some sessions.
    private val portMapperState = MutableStateFlow<(InputDevice?) -> Int?> { null }
    private val bindingsState = MutableStateFlow<(InputDevice?) -> Map<InputKey, RetroKey>> { emptyMap() }
    private val shortcutsState = MutableStateFlow<Map<InputDevice, List<GameShortcut>>>(emptyMap())

    private val controllerConfigsState = MutableStateFlow<Map<Int, ControllerConfig>>(mapOf())

    // Buffered shared flows so a (re)starting collector cannot lose events, and tryEmit below
    // never suspends. DROP_OLDEST keeps the most recent input states during collector restarts.
    private val keyEventsFlow: MutableSharedFlow<KeyEvent> =
        MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val motionEventsFlow: MutableSharedFlow<MotionEvent> =
        MutableSharedFlow(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun getAllTiltConfigurations(): List<TiltConfiguration> {
        return controllerConfigsState.value[0]
            ?.tiltConfigurations
            ?: emptyList()
    }

    fun getControllerConfigState(): Flow<Map<Int, ControllerConfig>> {
        return controllerConfigsState
    }

    private fun sendStickMotions(
        event: MotionEvent,
        port: Int,
    ) {
        if (port < 0) return
        // Controllers report a source *mask*, not a single source. An Xbox pad reports
        // JOYSTICK|GAMEPAD (0x01000511), so an equality check against SOURCE_JOYSTICK is
        // never true and every stick and hat event is silently dropped - the d-pad and
        // analog stick then look dead while buttons keep working.
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return
        }
        if (controllerConfigsState.value[port]?.mergeDPADAndLeftStickEvents == true) {
            sendMergedMotionEvents(event, port)
        } else {
            sendSeparateMotionEvents(event, port)
        }
    }

    private fun sendMergedMotionEvents(
        event: MotionEvent,
        port: Int,
    ) {
        val events =
            listOf(
                retrieveCoordinates(event, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y),
                retrieveCoordinates(event, MotionEvent.AXIS_X, MotionEvent.AXIS_Y),
            )

        val xVal = events.maxByOrNull { abs(it.x) }?.x ?: 0f
        val yVal = events.maxByOrNull { abs(it.y) }?.y ?: 0f

        retroGameView.retroGameView?.sendMotionEvent(MOTION_SOURCE_DPAD, xVal, yVal, port)
        retroGameView.retroGameView?.sendMotionEvent(MOTION_SOURCE_ANALOG_LEFT, xVal, yVal, port)

        sendStickMotion(
            event,
            MOTION_SOURCE_ANALOG_RIGHT,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            port,
        )
    }

    private fun sendStickMotion(
        event: MotionEvent,
        source: Int,
        xAxis: Int,
        yAxis: Int,
        port: Int,
    ) {
        val coords = retrieveCoordinates(event, xAxis, yAxis)
        retroGameView.retroGameView?.sendMotionEvent(source, coords.x, coords.y, port)
    }

    private fun sendDPADMotion(
        event: MotionEvent,
        source: Int,
        xAxis: Int,
        yAxis: Int,
        port: Int,
    ) {
        retroGameView.retroGameView?.sendMotionEvent(
            source,
            event.getAxisValue(xAxis),
            event.getAxisValue(yAxis),
            port,
        )
    }

    private fun sendSeparateMotionEvents(
        event: MotionEvent,
        port: Int,
    ) {
        sendDPADMotion(
            event,
            MOTION_SOURCE_DPAD,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
            port,
        )
        sendStickMotion(
            event,
            MOTION_SOURCE_ANALOG_LEFT,
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            port,
        )
        sendStickMotion(
            event,
            MOTION_SOURCE_ANALOG_RIGHT,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            port,
        )
    }

    private fun retrieveCoordinates(
        event: MotionEvent,
        xAxis: Int,
        yAxis: Int,
    ): PointF {
        return PointF(event.getAxisValue(xAxis), event.getAxisValue(yAxis))
    }

    fun sendKeyEvent(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val accepted = InputKey(keyCode) in event.device.getInputClass().getInputKeys()
        if (accepted) {
            keyEventsFlow.tryEmit(event)
            return true
        }
        return false
    }

    fun sendMotionEvent(event: MotionEvent): Boolean {
        motionEventsFlow.tryEmit(event)
        return true
    }

    fun getEnabledInputDevices(): Flow<List<InputDevice>> {
        return inputDeviceManager.getEnabledInputsObservable()
    }

    private fun updateControllers(controllers: Map<Int, ControllerConfig>) {
        val supportedControllers = retroGameView.retroGameView?.getControllers()?.toIndexedMap()
        if (supportedControllers.isNullOrEmpty()) {
            controllers.forEach { (port, config) ->
                val controllerId = config.libretroId ?: 1
                Timber.i("Controls setting fallback $port to $controllerId")
                retroGameView.retroGameView?.setControllerType(port, controllerId)
            }
            for (port in 1..3) {
                if (!controllers.containsKey(port)) {
                    retroGameView.retroGameView?.setControllerType(port, 0)
                }
            }
            return
        }

        supportedControllers.forEach { (port, supported) ->
            Timber.i("CONTROLLERS port=%d: %s", port, supported.joinToString { "${it.id}:${it.description}" })
            val config = controllers[port]
            if (config != null) {
                // Never leave a port on the library default: when the system config defines no
                // matching id/descriptor, fall back to the appropriate supported controller class.
                // For analog systems (N64, DualShock), pick the analog pad (conventionally last);
                // for digital systems (NES, SNES, etc.), pick the primary Gamepad (first), never
                // a peripheral like Zapper/Lightgun.
                val controllerId =
                    findControllerId(supported, config)
                        ?: config.libretroId
                        ?: (if (config.hasAnalogStick) supported.lastOrNull()?.id else supported.firstOrNull()?.id)
                if (controllerId != null) {
                    Timber.i("Controls setting $port to $controllerId (${config.libretroDescriptor})")
                    retroGameView.retroGameView?.setControllerType(port, controllerId)
                }
            } else if (port > 0) {
                Timber.i("Controls disconnecting unused port $port")
                retroGameView.retroGameView?.setControllerType(port, 0)
            } else {
                // Port 0 has no per-system controller config.
                val fallbackId = supported.firstOrNull()?.id ?: 1
                Timber.i("Controls fallback port 0 to $fallbackId")
                fallbackId.let { retroGameView.retroGameView?.setControllerType(port, it) }
            }
        }
    }

    private fun findControllerId(
        supported: Array<Controller>,
        controllerConfig: ControllerConfig,
    ): Int? {
        return supported
            .firstOrNull { controller ->
                sequenceOf(
                    controller.id == controllerConfig.libretroId,
                    controller.description == controllerConfig.libretroDescriptor,
                ).any { it }
            }?.id
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        // Every input collector runs under supervision: if a flow dies for any transient reason
        // (the dead-collector race observed in the field), it restarts within 500 ms instead of
        // staying silently dead for the rest of the session.
        owner.launchOnState(Lifecycle.State.CREATED) {
            runSupervised("PORTS") { updatePortMapperSnapshot() }
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            runSupervised("BINDINGS") { updateBindingsSnapshot() }
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            runSupervised("SHORTCUTS") { updateShortcutsSnapshot() }
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            runSupervised("CONFIGS") { initializeControllerConfigsFlow() }
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            runSupervised("TOAST") { initializeGamePadShortcutsFlow() }
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            runSupervised("KEYS") { initializeGamePadKeysFlow() }
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            runSupervised("VSTICK") { initializeVirtualGamePadMotionsFlow() }
        }

        owner.launchOnState(Lifecycle.State.CREATED) {
            runSupervised("MOTIONS") { initializeGamePadMotionsFlow() }
        }

        owner.launchOnState(Lifecycle.State.RESUMED) {
            initializeControllersConfigFlow()
        }

    }

    private suspend fun initializeControllerConfigsFlow() {
        retroGameView.waitGLEvent<GLRetroView.GLRetroEvents.FrameRendered>()
        controllerConfigsState.safeCollect {
            updateControllers(it)
        }
    }

    private suspend fun initializeGamePadShortcutsFlow() {
        inputDeviceManager.getGameShortcutsObservable()
            .distinctUntilChanged()
            .safeCollect { allShortcuts ->
                allShortcuts.values
                    .firstNotNullOfOrNull { shortcuts ->
                        shortcuts.firstOrNull { it.type == GameShortcutType.MENU }
                    }
                    ?.let {
                        val message =
                            appContext.resources.getString(
                                R.string.game_toast_settings_button_using_gamepad,
                                it.name,
                            )
                        sideEffects.showToast(message)
                    }
            }
    }

    private suspend fun runSupervised(
        name: String,
        block: suspend () -> Unit,
    ) {
        while (true) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "INPUT %s crashed, restarting", name)
            } finally {
                Timber.i("INPUT %s restarting", name)
            }
            delay(500)
        }
    }

    private suspend fun updatePortMapperSnapshot() {
        inputDeviceManager.getGamePadsPortMapperObservable()
            .safeCollect { portMapperState.value = it }
    }

    private suspend fun updateBindingsSnapshot() {
        inputDeviceManager.getInputBindingsObservable()
            .safeCollect { bindingsState.value = it }
    }

    private suspend fun updateShortcutsSnapshot() {
        inputDeviceManager.getGameShortcutsObservable()
            .safeCollect { shortcutsState.value = it }
    }

    private suspend fun initializeGamePadKeysFlow() {
        val pressedKeys = mutableSetOf<Int>()

        keyEventsFlow
            .filter { it.repeatCount == 0 }
            .map { Triple(it.device, it.action, it.keyCode) }
            .distinctUntilChanged()
            .onStart {
                Timber.i("KEYS subscribed")
                pressedKeys.clear()
            }
            .onCompletion { pressedKeys.clear() }
            .safeCollect { (device, action, keyCode) ->
                val port = portMapperState.value(device)
                val bindings = bindingsState.value(device)
                val rawBound = bindings[InputKey(keyCode)]?.keyCode ?: keyCode
                val bindKeyCode = when (system.id) {
                    SystemID.N64 -> when (keyCode) {
                        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_L2
                        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_R2
                        else -> rawBound
                    }
                    SystemID.PSX -> when (keyCode) {
                        // Xbox face buttons match PlayStation layout:
                        // Xbox A (bottom) -> RetroPad B = PSX Cross
                        // Xbox B (right)  -> RetroPad A = PSX Circle
                        // Xbox X (left)   -> RetroPad Y = PSX Square
                        // Xbox Y (top)    -> RetroPad X = PSX Triangle
                        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
                        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
                        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
                        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
                        else -> rawBound
                    }
                    SystemID.NES -> when (keyCode) {
                        // Xbox face buttons for NES (X and Y are Turbo):
                        // Xbox A (bottom) -> RetroPad B = NES B (normal)
                        // Xbox B (right)  -> RetroPad A = NES A (normal)
                        // Xbox X (left)   -> RetroPad Y = NES Turbo B
                        // Xbox Y (top)    -> RetroPad X = NES Turbo A
                        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
                        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
                        KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
                        KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
                        else -> rawBound
                    }
                    else -> rawBound
                }

                if (port == 0) {
                    if ((bindKeyCode == KeyEvent.KEYCODE_BUTTON_MODE || bindKeyCode == KeyEvent.KEYCODE_MENU) && action == KeyEvent.ACTION_DOWN) {
                        pressedKeys.clear()
                        sideEffects.showMenu(tilt, this)
                        return@safeCollect
                    }

                    if (action == KeyEvent.ACTION_DOWN) {
                        pressedKeys.add(keyCode)
                    } else if (action == KeyEvent.ACTION_UP) {
                        pressedKeys.remove(keyCode)
                    }

                    val isSelectStart = pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_SELECT) &&
                        pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_START)
                    val isL3R3 = pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_THUMBL) &&
                        pressedKeys.contains(KeyEvent.KEYCODE_BUTTON_THUMBR)
                    if (isSelectStart || isL3R3) {
                        pressedKeys.clear()
                        sideEffects.showMenu(tilt, this)
                        return@safeCollect
                    }

                    shortcutsState.value[device]?.forEach { shortcut ->
                        if (shortcut.keys.isNotEmpty() && pressedKeys.containsAll(shortcut.keys)) {
                            when (shortcut.type) {
                                GameShortcutType.MENU -> {
                                    pressedKeys.clear()
                                    sideEffects.showMenu(tilt, this)
                                }
                                GameShortcutType.QUICK_LOAD -> sideEffects.loadQuickSave()
                                GameShortcutType.QUICK_SAVE -> sideEffects.saveQuickSave()
                                GameShortcutType.TOGGLE_FAST_FORWARD -> sideEffects.toggleFastForward()
                            }
                            return@safeCollect
                        }
                    }
                }

                port?.let {
                    retroGameView.retroGameView?.sendKeyEvent(action, bindKeyCode, it)
                }
            }
    }

    private suspend fun initializeVirtualGamePadMotionsFlow() {
        motionEventsFlow
            .mapNotNull { event ->
                portMapperState.value(event.device)?.let { it to event }
            }
            .map { (port, event) ->
                val axes = event.device.getInputClass().getAxesMap().entries

                axes.map { (axis, button) ->
                    val action =
                        if (event.getAxisValue(axis) > 0.5) {
                            KeyEvent.ACTION_DOWN
                        } else {
                            KeyEvent.ACTION_UP
                        }
                    SingleAxisEvent(axis, action, button, port)
                }.toSet()
            }
            .scan(emptySet<SingleAxisEvent>()) { prev, next ->
                next.minus(prev).forEach {
                    retroGameView.retroGameView?.sendKeyEvent(it.action, it.keyCode, it.port)
                }
                next
            }
            .safeCollect { }
    }

    private suspend fun initializeGamePadMotionsFlow() {
        motionEventsFlow
            .onStart { Timber.i("MOTIONS subscribed") }
            .safeCollect { event ->
                portMapperState.value(event.device)?.let {
                    sendStickMotions(event, it)
                }
            }
    }

    private suspend fun initializeControllersConfigFlow() {
        try {
            retroGameView.waitRetroGameViewInitialized()
            val controllers = controllerConfigsManager.getControllerConfigs(system.id, systemCoreConfig)
            controllerConfigsState.value = controllers
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
}
