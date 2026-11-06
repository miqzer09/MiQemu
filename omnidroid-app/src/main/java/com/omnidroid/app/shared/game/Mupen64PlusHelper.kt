package com.omnidroid.app.shared.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import com.omnidroid.common.kotlin.serializable
import com.omnidroid.lib.preferences.SharedPreferencesHelper
import com.omnidroid.app.mobile.feature.gamemenu.GameMenuActivity
import com.omnidroid.app.shared.GameMenuContract
import com.omnidroid.app.shared.coreoptions.CoreOption
import com.omnidroid.app.shared.coreoptions.OmnidroidCoreOption
import com.omnidroid.lib.core.CoreVariable
import com.omnidroid.lib.core.CoreVariablesManager
import com.omnidroid.lib.library.CoreID
import com.omnidroid.lib.library.ExposedSetting
import com.omnidroid.lib.library.GameSystem
import com.omnidroid.lib.library.SystemCoreConfig
import com.omnidroid.lib.library.db.entity.Game
import com.omnidroid.lib.storage.DirectoriesManager
import com.omnidroid.touchinput.radial.sensors.TiltConfiguration
import com.omnidroid.touchinput.radial.sensors.TiltSensor
import paulscode.android.mupen64plusae.ActivityHelper
import paulscode.android.mupen64plusae.game.GameActivity
import paulscode.android.mupen64plusae.input.AbstractController
import paulscode.android.mupen64plusae.jni.CoreFragment
import paulscode.android.mupen64plusae.jni.CoreTypes
import paulscode.android.mupen64plusae.util.FileUtil
import paulscode.android.mupen64plusae.util.RomHeader
import timber.log.Timber
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object Mupen64PlusHelper {

    const val REQUEST_GAME_MENU = 10099

    val ALL_TILT_CONFIGS: Array<TiltConfiguration> = arrayOf(
        TiltConfiguration.Disabled,
        TiltConfiguration.Analog(1),
        TiltConfiguration.Cross(0),
        TiltConfiguration.ButtonPair(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)
    )

    fun getSavedTiltConfig(context: Context): TiltConfiguration {
        val appPrefs = SharedPreferencesHelper.getSharedPreferences(context)
        val tiltKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-tilt", "n64")
        return when (appPrefs.getString(tiltKey, "disabled")?.lowercase()) {
            "analog" -> TiltConfiguration.Analog(1)
            "cross" -> TiltConfiguration.Cross(0)
            "buttons" -> TiltConfiguration.ButtonPair(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)
            else -> TiltConfiguration.Disabled
        }
    }

    private var activeTiltSensor: TiltSensor? = null
    private var tiltJob: Job? = null
    private val tiltButtons = BooleanArray(16)

    fun applyTiltConfig(context: Context, tiltConfig: TiltConfiguration, coreFragment: CoreFragment?) {
        tiltJob?.cancel()
        activeTiltSensor?.isAllowedToRun = false
        activeTiltSensor?.shouldRun = false
        activeTiltSensor = null

        java.util.Arrays.fill(tiltButtons, false)
        coreFragment?.setControllerState(0, tiltButtons, 0.0, 0.0, false)

        if (tiltConfig is TiltConfiguration.Disabled) {
            Timber.i("Mupen64Plus Tilt Sensor disabled")
            return
        }

        val sensor = TiltSensor(context)
        if (!sensor.isAvailable()) {
            Timber.w("Tilt sensor not available on this device")
            return
        }

        activeTiltSensor = sensor
        sensor.isAllowedToRun = true
        sensor.shouldRun = true

        val scope = CoroutineScope(Dispatchers.Main + Job())
        tiltJob = scope.launch {
            sensor.getTiltEvents().collect { values ->
                when (tiltConfig) {
                    is TiltConfiguration.Analog -> {
                        val x = values[0].toDouble().coerceIn(-1.0, 1.0)
                        val y = (-values[1]).toDouble().coerceIn(-1.0, 1.0)
                        coreFragment?.setControllerState(0, tiltButtons, x, y, false)
                    }
                    is TiltConfiguration.Cross -> {
                        java.util.Arrays.fill(tiltButtons, false)
                        val x = values[0]
                        val y = values[1]
                        if (x < -0.3f) tiltButtons[AbstractController.DPD_L] = true
                        if (x > 0.3f) tiltButtons[AbstractController.DPD_R] = true
                        if (y < -0.3f) tiltButtons[AbstractController.DPD_U] = true
                        if (y > 0.3f) tiltButtons[AbstractController.DPD_D] = true
                        coreFragment?.setControllerState(0, tiltButtons, 0.0, 0.0, true)
                    }
                    is TiltConfiguration.ButtonPair -> {
                        java.util.Arrays.fill(tiltButtons, false)
                        val xTilt = values[0]
                        val yTilt = values[1]
                        val leftPressed = xTilt < -0.75f
                        val rightPressed = xTilt > 0.75f
                        val bothPressed = !leftPressed && !rightPressed && yTilt < -0.9f
                        if (leftPressed || bothPressed) tiltButtons[AbstractController.BTN_L] = true
                        if (rightPressed || bothPressed) tiltButtons[AbstractController.BTN_R] = true
                        coreFragment?.setControllerState(0, tiltButtons, 0.0, 0.0, true)
                    }
                    else -> {}
                }
            }
        }
    }

    private val ASSET_VERSIONS = mapOf(
        "mupen64plus_data/Glide64mk2.ini" to 4,
        "mupen64plus_data/RiceVideoLinux.ini" to 2,
        "mupen64plus_data/font.ttf" to 1,
        "mupen64plus_data/gln64.conf" to 1,
        "mupen64plus_data/gln64rom.conf" to 1,
        "mupen64plus_data/mupen64plus.ini" to 13,
        "mupen64plus_data/mupencheat.default" to 1,
        "mupen64plus_data/GLideN64.custom.ini" to 2,
    )

    fun ensureAssets(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val filesDir = context.filesDir
        val editor = prefs.edit()

        for ((assetPath, version) in ASSET_VERSIONS) {
            val fileName = assetPath.substringAfterLast("/")
            val destFile = File(filesDir, fileName)
            if (!destFile.exists() || destFile.length() < 1000 || prefs.getInt(assetPath, 0) < version) {
                try {
                    context.assets.open(assetPath).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    editor.putInt(assetPath, version)
                } catch (e: Exception) {
                    Timber.w(e, "Could not extract asset: %s", assetPath)
                }
            }
        }

        // Also extract GLideN64.custom.ini if not present
        val customIni = File(filesDir, "GLideN64.custom.ini")
        if (!customIni.exists()) {
            try {
                context.assets.open("mupen64plus_data/GLideN64.custom.ini").use { input ->
                    customIni.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not extract GLideN64.custom.ini")
            }
        }

        // Configure default preferences: hide on-screen touchscreen overlay on TV
        editor.putBoolean("assetCheck", false)
        if (!prefs.contains("touchscreenHidden")) {
            editor.putBoolean("touchscreenHidden", true)
        }

        // Configure upstream Mupen64Plus-AE display and emulation profile defaults
        editor.putString("emulationProfileDefault", "GlideN64-Accurate")
        editor.putString("emulationProfile", "GlideN64-Accurate")
        editor.putString("displayScaling", "original")
        editor.putBoolean("inputShareController", true)
        editor.remove("displayResolution")
        editor.remove("emulationProfile1Game")
        editor.remove("displayResolutionGame")
        editor.remove("controllerProfile1Game")
        editor.remove("hybridTextureFilter_v2")
        editor.remove("threadedGLideN64")

        // Xbox controller mapping
        editor.putString("controllerProfile1", "Xbox")
        editor.putString("controllerProfileDefault", "Xbox")
        editor.apply()
    }

    fun getVideoSettingsSnapshot(context: Context): Map<String, String> {
        val appPrefs = SharedPreferencesHelper.getSharedPreferences(context)
        val dbName = "n64"
        val keys = listOf(
            "mupen64plus-aspect",
            "mupen64plus-43screensize",
            "mupen64plus-CropMode",
            "mupen64plus-MSAA",
            "mupen64plus-BilinearMode",
            "mupen64plus-FXAA",
            "mupen64plus-MaxAnisotropy",
            "mupen64plus-txEnhancementMode",
            "mupen64plus-EnableHWLighting"
        )
        return keys.associateWith { k ->
            val prefKey = CoreVariablesManager.computeSharedPreferenceKey(k, dbName)
            appPrefs.getString(prefKey, "") ?: ""
        }
    }

    fun syncOmnidroidSettings(context: Context, romMd5: String): Boolean {
        try {
            val appPrefs = SharedPreferencesHelper.getSharedPreferences(context)
            val dbName = "n64"

            // 0. Aspect Ratio
            val aspectKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-aspect", dbName)
            val aspectVal = appPrefs.getString(aspectKey, "4:3") ?: "4:3"
            val (glAspectRatio, displayScaling) = when (aspectVal.lowercase()) {
                "16:9" -> "2" to "stretch169"
                "16:9 adjusted", "16:9_adjusted" -> "3" to "stretch169"
                else -> "1" to "original"
            }

            // 1. Resolution
            val resKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-43screensize", dbName)
            val resString = appPrefs.getString(resKey, "1440x1080") ?: "1440x1080"
            val parts = resString.split("x")
            val renderWidth = parts.getOrNull(0)?.toIntOrNull() ?: 1440
            val renderHeight = parts.getOrNull(1)?.toIntOrNull() ?: 1080

            // 2. Crop black borders (Overscan)
            val cropKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-CropMode", dbName)
            val cropVal = appPrefs.getString(cropKey, "Auto") ?: "Auto"
            val enableOverscan = if (cropVal.equals("Auto", ignoreCase = true)) "True" else "False"

            // 3. Anti-aliasing MSAA
            val msaaKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-MSAA", dbName)
            val msaaVal = appPrefs.getString(msaaKey, "0") ?: "0"

            // 4. Bilinear mode
            val bilinearKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-BilinearMode", dbName)
            val bilinearVal = appPrefs.getString(bilinearKey, "standard") ?: "standard"
            val bilinearMode = if (bilinearVal.equals("3point", ignoreCase = true)) "False" else "True"

            // 5. FXAA
            val fxaaKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-FXAA", dbName)
            val fxaaVal = appPrefs.getString(fxaaKey, "0") ?: "0"
            val enableFxaa = if (fxaaVal == "1" || fxaaVal.equals("true", ignoreCase = true)) "True" else "False"

            // 6. Anisotropic filtering
            val anisoKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-MaxAnisotropy", dbName)
            val anisoVal = appPrefs.getString(anisoKey, "0") ?: "0"

            // 7. Texture enhancement
            val txEnhanceKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-txEnhancementMode", dbName)
            val txEnhanceVal = appPrefs.getString(txEnhanceKey, "None") ?: "None"
            val txEnhancementMode = when (txEnhanceVal) {
                "As Is" -> "1"
                "X2" -> "2"
                "X2SAI" -> "3"
                "HQ2X" -> "4"
                "HQ2XS" -> "5"
                "LQ2X" -> "6"
                "LQ2XS" -> "7"
                "HQ4X" -> "8"
                "2xBRZ" -> "9"
                "3xBRZ" -> "10"
                "4xBRZ" -> "11"
                "5xBRZ" -> "12"
                "6xBRZ" -> "13"
                else -> "0"
            }

            // 8. Hardware per-pixel lighting
            val hwLightKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-EnableHWLighting", dbName)
            val hwLightVal = appPrefs.getString(hwLightKey, "False") ?: "False"
            val enableHWLighting = if (hwLightVal.equals("True", ignoreCase = true)) "True" else "False"

            // 9. Controller Pak 1 & Pak 2
            val pak1Key = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-pak1", dbName)
            val pak1Val = appPrefs.getString(pak1Key, "memory") ?: "memory"
            val inputPakType1 = when (pak1Val.lowercase()) {
                "rumble" -> CoreTypes.PakType.RAMBLE.ordinal.toString()
                "none" -> CoreTypes.PakType.NONE.ordinal.toString()
                else -> CoreTypes.PakType.MEMORY.ordinal.toString()
            }

            val pak2Key = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-pak2", dbName)
            val pak2Val = appPrefs.getString(pak2Key, "none") ?: "none"
            val inputPakType2 = when (pak2Val.lowercase()) {
                "memory" -> CoreTypes.PakType.MEMORY.ordinal.toString()
                "rumble" -> CoreTypes.PakType.RAMBLE.ordinal.toString()
                else -> CoreTypes.PakType.NONE.ordinal.toString()
            }

            // Write to game preferences: <romMd5>_preferences
            val gamePrefsName = romMd5.replace(' ', '_') + "_preferences"
            val gamePrefs = context.getSharedPreferences(gamePrefsName, Context.MODE_PRIVATE)
            gamePrefs.edit()
                .putString("displayResolutionGame", renderHeight.toString())
                .putString("displayScalingGame", displayScaling)
                .putString("emulationProfile", "GlideN64-Accurate")
                .putString("inputPakType1", inputPakType1)
                .putString("inputPakType2", inputPakType2)
                .apply()

            val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            defaultPrefs.edit()
                .putString("displayResolution", renderHeight.toString())
                .putString("displayScaling", displayScaling)
                .putString("emulationProfileDefault", "GlideN64-Accurate")
                .putString("emulationProfile", "GlideN64-Accurate")
                .apply()

            // Update Profiles/emulation.cfg for [GlideN64-Accurate]
            val filesDir = context.filesDir
            val profileDir = File(filesDir, "Profiles")
            profileDir.mkdirs()
            val emulationCfg = File(profileDir, "emulation.cfg")

            val profileContent = StringBuilder()
            profileContent.appendLine("[GlideN64-Accurate]")
            profileContent.appendLine("comment = GlideN64 Accurate Profile")
            profileContent.appendLine("videoPlugin = GLideN64")
            profileContent.appendLine("r4300Emulator = 2")
            profileContent.appendLine("audioPlugin = libmupen64plus-audio-android.so")
            profileContent.appendLine("rspPlugin = libmupen64plus-rsp-hle.so")
            profileContent.appendLine("txHiresEnable = True")
            profileContent.appendLine("txSaveCache = True")
            profileContent.appendLine("AspectRatio = $glAspectRatio")
            profileContent.appendLine("anisotropy = $anisoVal")
            profileContent.appendLine("MaxAnisotropy = $anisoVal")
            profileContent.appendLine("MultiSampling = $msaaVal")
            profileContent.appendLine("bilinearMode = $bilinearMode")
            profileContent.appendLine("FXAA = $enableFxaa")
            profileContent.appendLine("EnableHWLighting = $enableHWLighting")
            profileContent.appendLine("EnableOverscan = $enableOverscan")
            profileContent.appendLine("txEnhancementMode = $txEnhancementMode")
            profileContent.appendLine("EnableFBEmulation = True")
            profileContent.appendLine("EnableCopyColorToRDRAM = 2")
            profileContent.appendLine("EnableCopyDepthToRDRAM = 2")
            profileContent.appendLine("EnableCopyColorFromRDRAM = False")
            profileContent.appendLine("EnableCopyAuxiliaryToRDRAM = False")
            profileContent.appendLine("EnableLegacyBlending = False")
            profileContent.appendLine("EnableFragmentDepthWrite = True")
            profileContent.appendLine("EnableInaccurateTextureCoordinates = False")
            profileContent.appendLine("EnableNativeResTexrects = True")
            profileContent.appendLine("CorrectTexrectCoords = 0")
            profileContent.appendLine("EnableTexCoordBounds_v2 = True")
            profileContent.appendLine("BackgroundsMode = 0")
            profileContent.appendLine("EnableLOD = True")
            profileContent.appendLine("EnableCoverage = False")
            profileContent.appendLine("EnableClipping = True")
            profileContent.appendLine("EnableDitheringPattern = False")
            profileContent.appendLine("EnableHiresNoiseDithering = False")
            profileContent.appendLine("EnableDitheringQuantization = True")
            profileContent.appendLine("RdramImageDitheringMode = 3")
            profileContent.appendLine("txFilterMode = 0")
            profileContent.appendLine("txDeposterize = False")
            profileContent.appendLine("txFilterIgnoreBG = True")
            profileContent.appendLine("txCacheSize = 128")
            profileContent.appendLine("txHiresFullAlphaChannel = False")
            profileContent.appendLine("txCacheCompression = True")
            profileContent.appendLine("ForceGammaCorrection = False")
            profileContent.appendLine("GammaCorrectionLevel = 10")
            emulationCfg.writeText(profileContent.toString())

            // Update all existing mupen64plus.cfg in GameData
            val gameDataDir = File(filesDir, "GameData")
            if (gameDataDir.exists()) {
                val configFiles = gameDataDir.walkTopDown().filter { it.name == "mupen64plus.cfg" }.toList()
                for (cfgFile in configFiles) {
                    try {
                        val lines = cfgFile.readLines()
                        var inVideoGLideN64 = false
                        var inVideoGeneral = false
                        val updatedLines = mutableListOf<String>()

                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                                inVideoGLideN64 = trimmed.equals("[Video-GLideN64]", ignoreCase = true)
                                inVideoGeneral = trimmed.equals("[Video-General]", ignoreCase = true)
                                updatedLines.add(line)
                                continue
                            }

                            if (inVideoGeneral) {
                                if (trimmed.startsWith("ScreenWidth =")) {
                                    updatedLines.add("ScreenWidth = $renderWidth")
                                    continue
                                } else if (trimmed.startsWith("ScreenHeight =")) {
                                    updatedLines.add("ScreenHeight = $renderHeight")
                                    continue
                                }
                            }

                            if (inVideoGLideN64) {
                                if (trimmed.startsWith("MultiSampling =")) {
                                    updatedLines.add("MultiSampling = $msaaVal")
                                    continue
                                } else if (trimmed.startsWith("FXAA =")) {
                                    updatedLines.add("FXAA = $enableFxaa")
                                    continue
                                } else if (trimmed.startsWith("anisotropy =")) {
                                    updatedLines.add("anisotropy = $anisoVal")
                                    continue
                                } else if (trimmed.startsWith("MaxAnisotropy =")) {
                                    updatedLines.add("MaxAnisotropy = $anisoVal")
                                    continue
                                } else if (trimmed.startsWith("bilinearMode =")) {
                                    updatedLines.add("bilinearMode = $bilinearMode")
                                    continue
                                } else if (trimmed.startsWith("EnableOverscan =")) {
                                    updatedLines.add("EnableOverscan = $enableOverscan")
                                    continue
                                } else if (trimmed.startsWith("EnableHWLighting =")) {
                                    updatedLines.add("EnableHWLighting = $enableHWLighting")
                                    continue
                                } else if (trimmed.startsWith("txEnhancementMode =")) {
                                    updatedLines.add("txEnhancementMode = $txEnhancementMode")
                                    continue
                                } else if (trimmed.startsWith("AspectRatio =")) {
                                    updatedLines.add("AspectRatio = $glAspectRatio")
                                    continue
                                } else if (trimmed.startsWith("EnableCopyColorToRDRAM =")) {
                                    updatedLines.add("EnableCopyColorToRDRAM = 2")
                                    continue
                                } else if (trimmed.startsWith("EnableLegacyBlending =")) {
                                    updatedLines.add("EnableLegacyBlending = False")
                                    continue
                                } else if (trimmed.startsWith("EnableFragmentDepthWrite =")) {
                                    updatedLines.add("EnableFragmentDepthWrite = True")
                                    continue
                                } else if (trimmed.startsWith("EnableInaccurateTextureCoordinates =")) {
                                    updatedLines.add("EnableInaccurateTextureCoordinates = False")
                                    continue
                                } else if (trimmed.startsWith("EnableNativeResTexrects =")) {
                                    updatedLines.add("EnableNativeResTexrects = 1")
                                    continue
                                } else if (trimmed.startsWith("EnableTexCoordBounds =")) {
                                    updatedLines.add("EnableTexCoordBounds = 1")
                                    continue
                                }
                            }

                            updatedLines.add(line)
                        }
                        cfgFile.writeText(updatedLines.joinToString("\n"))
                    } catch (e: Exception) {
                        Timber.w(e, "Could not patch mupen64plus.cfg: %s", cfgFile.path)
                    }
                }
            }

            Timber.i("syncOmnidroidSettings success: res=%dx%d, overscan=%s, msaa=%s, bilinear=%s, fxaa=%s, aniso=%s, txEnhance=%s, hwLight=%s, pak1=%s, pak2=%s",
                renderWidth, renderHeight, enableOverscan, msaaVal, bilinearMode, enableFxaa, anisoVal, txEnhancementMode, enableHWLighting, inputPakType1, inputPakType2)
            return true
        } catch (e: Exception) {
            Timber.e(e, "Error syncing Omnidroid settings to Mupen64Plus-AE")
            return false
        }
    }

    fun resolveRomFile(context: Context, game: Game): File? {
        val uri = Uri.parse(game.fileUri)
        if (uri.scheme == null || uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: game.fileUri
            val file = File(path)
            if (file.exists()) return file
        }

        // Fallback for content URIs: resolve via ContentResolver or cached ROM
        val cacheFile = File(context.cacheDir, game.fileName)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile
        }

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve ROM file from URI: %s", game.fileUri)
            null
        }
    }

    fun launchGame(
        activity: Activity,
        game: Game,
        activityOptions: Bundle? = null,
        isExternalLaunch: Boolean = false,
        systemCoreConfig: SystemCoreConfig? = null,
    ): Boolean {
        ensureAssets(activity.applicationContext)

        val romFile = resolveRomFile(activity, game)
        if (romFile == null || !romFile.exists()) {
            Timber.e("ROM file could not be resolved for %s", game.title)
            return false
        }

        val romHeader = RomHeader(romFile)
        var romMd5 = ""
        try {
            java.io.BufferedInputStream(romFile.inputStream()).use { input ->
                romMd5 = FileUtil.computeMd5(input)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error computing MD5 for %s", romFile.name)
        }

        if (romMd5.isBlank()) {
            // Fallback MD5 if computation failed
            romMd5 = romHeader.crc?.replace(" ", "")?.takeIf { it.isNotBlank() } ?: "UNKNOWN_MD5"
        }

        val gamePrefsName = romMd5.replace(' ', '_') + "_preferences"
        val gamePrefs = activity.applicationContext.getSharedPreferences(gamePrefsName, Context.MODE_PRIVATE)
        val currentControllerProfile = gamePrefs.getString("controllerProfile1Game", null)
        if (currentControllerProfile == null) {
            gamePrefs.edit()
                .putString("inputShareController2", "Yes")
                .putString("controllerProfile1Game", "Xbox")
                .apply()
        }

        val countryByte = romHeader.countryCode?.value ?: 0.toByte()
        val romCrc = romHeader.crc ?: ""
        val headerName = romHeader.name ?: game.title

        Timber.i("Launching Mupen64Plus-AE: path=%s, md5=%s, crc=%s, header=%s", romFile.absolutePath, romMd5, romCrc, headerName)

        val system = try {
            GameSystem.findById(game.systemId)
        } catch (_: Exception) {
            null
        }

        val n64SystemConfig = system?.systemCoreConfigs?.firstOrNull { it.coreID == CoreID.MUPEN64_PLUS_NEXT }
        val coreConfig = systemCoreConfig ?: n64SystemConfig ?: SystemCoreConfig(
            coreID = CoreID.MUPEN64_PLUS_NEXT,
            controllerConfigs = hashMapOf(),
            statesSupported = true,
            skipDuplicateFrames = true,
            allowFrameCatchUp = true,
        )

        val exposedSettings = coreConfig.exposedSettings.ifEmpty {
            n64SystemConfig?.exposedSettings ?: emptyList()
        }
        val exposedAdvancedSettings = coreConfig.exposedAdvancedSettings.ifEmpty {
            n64SystemConfig?.exposedAdvancedSettings ?: emptyList()
        }

        fun buildN64CoreOptions(): Pair<Array<OmnidroidCoreOption>, Array<OmnidroidCoreOption>> {
            val appPrefs = SharedPreferencesHelper.getSharedPreferences(activity.applicationContext)
            val dbName = system?.id?.dbname ?: "n64"

            fun transformExposed(exposedSetting: ExposedSetting): OmnidroidCoreOption {
                val key = CoreVariablesManager.computeSharedPreferenceKey(exposedSetting.key, dbName)
                val coreOption = if (exposedSetting.values.isNotEmpty()) {
                    val defaultVal = coreConfig.defaultSettings.firstOrNull { it.key == exposedSetting.key }?.value
                        ?: exposedSetting.values.first().key
                    val rawVal = appPrefs.getString(key, defaultVal) ?: defaultVal
                    val currentVal = rawVal
                    CoreOption(
                        CoreVariable(exposedSetting.key, currentVal),
                        activity.getString(exposedSetting.titleId),
                        exposedSetting.values.map { it.key },
                    )
                } else {
                    val isEnabled = appPrefs.getBoolean(key, false)
                    CoreOption(
                        CoreVariable(exposedSetting.key, if (isEnabled) "enabled" else "disabled"),
                        activity.getString(exposedSetting.titleId),
                        listOf("disabled", "enabled"),
                    )
                }
                return OmnidroidCoreOption(exposedSetting, coreOption)
            }

            val options = exposedSettings.map { transformExposed(it) }.toTypedArray()
            val advancedOptions = exposedAdvancedSettings.map { transformExposed(it) }.toTypedArray()
            return Pair(options, advancedOptions)
        }

        var isAudioEnabled = true
        var currentFrameSpeed = 1
        var lastVideoSettingsSnapshot: Map<String, String>? = null

        // Register InGameMenuHandler to remap all Mupen64 menu requests to Omnidroid's in-game menu!
        GameActivity.sInGameMenuHandler = object : GameActivity.InGameMenuHandler {
            override fun openMenu(gameActivity: GameActivity, coreFragment: CoreFragment?): Boolean {
                Timber.i("Opening Omnidroid in-game menu from Mupen64Plus-AE")
                coreFragment?.pauseEmulator()
                lastVideoSettingsSnapshot = getVideoSettingsSnapshot(gameActivity.applicationContext)

                val (options, advancedOptions) = buildN64CoreOptions()

                val menuIntent = Intent(gameActivity, GameMenuActivity::class.java).apply {
                    putExtra(GameMenuContract.EXTRA_GAME, game)
                    putExtra(GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG, coreConfig)
                    putExtra(GameMenuContract.EXTRA_CORE_OPTIONS, options)
                    putExtra(GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS, advancedOptions)
                    putExtra(GameMenuContract.EXTRA_AUDIO_ENABLED, isAudioEnabled)
                    putExtra(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED, true)
                    putExtra(GameMenuContract.EXTRA_FRAME_SPEED, currentFrameSpeed)
                    putExtra(GameMenuContract.EXTRA_FAST_FORWARD, currentFrameSpeed > 1)
                    putExtra(GameMenuContract.EXTRA_CURRENT_DISK, 0)
                    putExtra(GameMenuContract.EXTRA_DISKS, 0)
                    putExtra(GameMenuContract.EXTRA_CURRENT_TILT_CONFIG, getSavedTiltConfig(gameActivity.applicationContext))
                    putExtra(GameMenuContract.EXTRA_TILT_ALL_CONFIGS, ALL_TILT_CONFIGS)
                }
                gameActivity.startActivityForResult(menuIntent, REQUEST_GAME_MENU)
                gameActivity.overridePendingTransition(0, 0)
                return true
            }

            override fun onMenuResult(
                gameActivity: GameActivity,
                coreFragment: CoreFragment?,
                requestCode: Int,
                resultCode: Int,
                data: Intent?,
            ) {
                if (requestCode != REQUEST_GAME_MENU) return

                Timber.i("Omnidroid in-game menu returned: resultCode=%d, data=%s", resultCode, data?.extras)

                if (data == null || resultCode != Activity.RESULT_OK) {
                    coreFragment?.resumeEmulator()
                    return
                }

                // 1. Quit Game
                if (data.getBooleanExtra(GameMenuContract.RESULT_QUIT, false)) {
                    Timber.i("Menu action: QUIT")
                    gameActivity.requestShutdown()
                    return
                }

                // 2. Reset / Restart
                if (data.getBooleanExtra(GameMenuContract.RESULT_RESET, false)) {
                    Timber.i("Menu action: RESET")
                    coreFragment?.restart()
                    coreFragment?.resumeEmulator()
                    return
                }

                // 3. Save State Slot
                if (data.hasExtra(GameMenuContract.RESULT_SAVE)) {
                    val slot = data.getIntExtra(GameMenuContract.RESULT_SAVE, 0)
                    Timber.i("Menu action: SAVE SLOT %d", slot)
                    coreFragment?.setSlot(slot)
                    coreFragment?.saveSlot()
                    recordSaveSlot(gameActivity, game, coreConfig.coreID, slot)
                    coreFragment?.resumeEmulator()
                    return
                }

                // 4. Load State Slot
                if (data.hasExtra(GameMenuContract.RESULT_LOAD)) {
                    val slot = data.getIntExtra(GameMenuContract.RESULT_LOAD, 0)
                    Timber.i("Menu action: LOAD SLOT %d", slot)
                    coreFragment?.setSlot(slot)
                    coreFragment?.loadSlot()
                    coreFragment?.resumeEmulator()
                    return
                }

                // 5. Mute Audio toggle
                if (data.hasExtra(GameMenuContract.RESULT_ENABLE_AUDIO)) {
                    val enabled = data.getBooleanExtra(GameMenuContract.RESULT_ENABLE_AUDIO, true)
                    Timber.i("Menu action: AUDIO ENABLED=%b", enabled)
                    isAudioEnabled = enabled
                    coreFragment?.setVolume(if (enabled) 100 else 0)
                }

                // 6. Fast Forward speed
                if (data.hasExtra(GameMenuContract.RESULT_SET_FRAME_SPEED)) {
                    val speed = data.getIntExtra(GameMenuContract.RESULT_SET_FRAME_SPEED, 1)
                    Timber.i("Menu action: SET_FRAME_SPEED=%d", speed)
                    currentFrameSpeed = speed
                    val mupenSpeed = if (speed > 1) speed * 100 else 100
                    coreFragment?.setCustomSpeed(mupenSpeed)
                } else if (data.hasExtra(GameMenuContract.RESULT_ENABLE_FAST_FORWARD)) {
                    val ff = data.getBooleanExtra(GameMenuContract.RESULT_ENABLE_FAST_FORWARD, false)
                    Timber.i("Menu action: FAST_FORWARD=%b", ff)
                    currentFrameSpeed = if (ff) 2 else 1
                    coreFragment?.setCustomSpeed(if (ff) 200 else 100)
                }

                // 7. Tilt configuration
                if (data.hasExtra(GameMenuContract.RESULT_CHANGE_TILT_CONFIG)) {
                    val tiltConfig = data.serializable<TiltConfiguration>(GameMenuContract.RESULT_CHANGE_TILT_CONFIG)
                    if (tiltConfig != null) {
                        Timber.i("Menu action: CHANGE TILT CONFIG to %s", tiltConfig)
                        val appPrefs = SharedPreferencesHelper.getSharedPreferences(gameActivity.applicationContext)
                        val tiltKey = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-tilt", "n64")
                        val tiltVal = when (tiltConfig) {
                            is TiltConfiguration.Analog -> "analog"
                            is TiltConfiguration.Cross -> "cross"
                            is TiltConfiguration.ButtonPair -> "buttons"
                            else -> "disabled"
                        }
                        appPrefs.edit().putString(tiltKey, tiltVal).apply()
                        applyTiltConfig(gameActivity.applicationContext, tiltConfig, coreFragment)
                    }
                }

                // Live update Controller Paks
                val appPrefs = SharedPreferencesHelper.getSharedPreferences(gameActivity.applicationContext)
                val pak1Key = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-pak1", "n64")
                val pak2Key = CoreVariablesManager.computeSharedPreferenceKey("mupen64plus-pak2", "n64")
                val pak1Val = appPrefs.getString(pak1Key, "memory")
                val pak2Val = appPrefs.getString(pak2Key, "none")
                val pak1Type = when (pak1Val?.lowercase()) {
                    "rumble" -> CoreTypes.PakType.RAMBLE
                    "none" -> CoreTypes.PakType.NONE
                    else -> CoreTypes.PakType.MEMORY
                }
                val pak2Type = when (pak2Val?.lowercase()) {
                    "memory" -> CoreTypes.PakType.MEMORY
                    "rumble" -> CoreTypes.PakType.RAMBLE
                    else -> CoreTypes.PakType.NONE
                }
                coreFragment?.updateControllerConfig(0, true, pak1Type)
                coreFragment?.updateControllerConfig(1, true, pak2Type)

                // Check if any video core settings changed
                val currentVideoSnapshot = getVideoSettingsSnapshot(gameActivity.applicationContext)
                if (lastVideoSettingsSnapshot != null && currentVideoSnapshot != lastVideoSettingsSnapshot) {
                    Timber.i("Video core settings changed! Reloading core with saved state...")
                    syncOmnidroidSettings(gameActivity.applicationContext, romMd5)
                    gameActivity.reloadWithSavedState()
                    return
                }

                coreFragment?.resumeEmulator()
            }
        }

        // Sync all Omnidroid core options to Mupen64Plus-AE configs before launch
        syncOmnidroidSettings(activity.applicationContext, romMd5)

        val intent = Intent(activity, GameActivity::class.java).apply {
            putExtra(ActivityHelper.Keys.ROM_PATH, romFile.absolutePath)
            putExtra(ActivityHelper.Keys.ZIP_PATH, "")
            putExtra(ActivityHelper.Keys.ROM_MD5, romMd5)
            putExtra(ActivityHelper.Keys.ROM_CRC, romCrc)
            putExtra(ActivityHelper.Keys.ROM_HEADER_NAME, headerName)
            putExtra(ActivityHelper.Keys.ROM_COUNTRY_CODE, countryByte)
            putExtra(ActivityHelper.Keys.ROM_ART_PATH, game.customCoverPath ?: "")
            putExtra(ActivityHelper.Keys.ROM_GOOD_NAME, game.title)
            putExtra(ActivityHelper.Keys.ROM_DISPLAY_NAME, game.displayName)
            putExtra(ActivityHelper.Keys.DO_RESTART, false)
            putExtra(ActivityHelper.Keys.NETPLAY_ENABLED, false)
            putExtra(ActivityHelper.Keys.NETPLAY_SERVER, false)

            val (options, advancedOptions) = buildN64CoreOptions()
            val menuBundle = Bundle().apply {
                putSerializable(GameMenuContract.EXTRA_GAME, game)
                putSerializable(GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG, coreConfig)
                putSerializable(GameMenuContract.EXTRA_CORE_OPTIONS, options)
                putSerializable(GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS, advancedOptions)
                putSerializable(GameMenuContract.EXTRA_CURRENT_TILT_CONFIG, getSavedTiltConfig(activity.applicationContext))
                putSerializable(GameMenuContract.EXTRA_TILT_ALL_CONFIGS, ALL_TILT_CONFIGS)
                putBoolean(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED, true)
                putInt(GameMenuContract.EXTRA_DISKS, 0)
                putInt(GameMenuContract.EXTRA_CURRENT_DISK, 0)
            }
            putExtra(GameActivity.EXTRA_OMNIDROID_MENU_BUNDLE, menuBundle)

            if (isExternalLaunch) {
                addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
            }
            if (activityOptions != null) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        }

        if (isExternalLaunch) {
            activity.startActivity(intent, activityOptions)
        } else {
            activity.startActivityForResult(intent, BaseGameActivity.REQUEST_PLAY_GAME, activityOptions)
        }
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        return true
    }

    private fun recordSaveSlot(context: Context, game: Game, coreID: CoreID, slot: Int) {
        try {
            val dm = DirectoriesManager(context)
            val dir = File(dm.getStatesDirectory(), coreID.coreName)
            dir.mkdirs()
            val file = File(dir, "${game.fileName}.slot${slot + 1}")
            file.writeBytes(byteArrayOf(1))
        } catch (e: Exception) {
            Timber.w(e, "Could not record save slot state file")
        }
    }
}
