package com.omnidroid.lib.core

import android.content.SharedPreferences
import com.omnidroid.lib.library.CoreID
import com.omnidroid.lib.library.SystemCoreConfig
import com.omnidroid.lib.library.SystemID
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.InvalidParameterException

class CoreVariablesManager(private val sharedPreferences: Lazy<SharedPreferences>) {
    suspend fun getOptionsForCore(
        systemID: SystemID,
        systemCoreConfig: SystemCoreConfig,
    ): List<CoreVariable> {
        val defaultMap = convertCoreVariablesToMap(systemCoreConfig.defaultSettings)
        val coreVariables = retrieveCustomCoreVariables(systemID, systemCoreConfig)
        val coreVariablesMap = (defaultMap + convertCoreVariablesToMap(coreVariables)).toMutableMap()

        if (systemID == SystemID.N64) {
            coreVariablesMap["mupen64plus-GLideN64IniBehaviour"] = "early"
            coreVariablesMap["mupen64plus-aspect"] = "16:9"
            val res43 = coreVariablesMap["mupen64plus-43screensize"] ?: "320x240"
            coreVariablesMap["mupen64plus-43screensize"] = res43
            coreVariablesMap["mupen64plus-169screensize"] = mapN64To169Resolution(res43)
            coreVariablesMap["mupen64plus-cpucore"] = "dynamic_recompiler"
            coreVariablesMap["mupen64plus-rdp-plugin"] = "gliden64"
            coreVariablesMap["mupen64plus-rsp-plugin"] = "hle"
            coreVariablesMap["mupen64plus-MaxTxCacheSize"] = "8000"
            coreVariablesMap["mupen64plus-EnableTextureCache"] = "False"
            coreVariablesMap["mupen64plus-EnableEnhancedTextureStorage"] = "False"
            coreVariablesMap["mupen64plus-EnableEnhancedHighResStorage"] = "False"
            coreVariablesMap["mupen64plus-txCacheCompression"] = "True"
            coreVariablesMap["mupen64plus-ThreadedRenderer"] = "False"
            coreVariablesMap["mupen64plus-EnableFBEmulation"] = "False"
            coreVariablesMap["mupen64plus-EnableCopyColorToRDRAM"] = "Off"
            coreVariablesMap["mupen64plus-EnableCopyDepthToRDRAM"] = "Off"
            coreVariablesMap["mupen64plus-EnableCopyAuxToRDRAM"] = "False"
            coreVariablesMap["mupen64plus-EnableCopyColorFromRDRAM"] = "False"
            coreVariablesMap["mupen64plus-EnableLegacyBlending"] = "True"
            coreVariablesMap["mupen64plus-BilinearMode"] = "standard"
            coreVariablesMap["mupen64plus-HybridFilter"] = "False"
            coreVariablesMap["mupen64plus-DitheringPattern"] = "False"
            coreVariablesMap["mupen64plus-DitheringQuantization"] = "False"
            coreVariablesMap["mupen64plus-RDRAMImageDitheringMode"] = "False"
            coreVariablesMap["mupen64plus-FXAA"] = "0"
            coreVariablesMap["mupen64plus-MultiSampling"] = "0"
            coreVariablesMap["mupen64plus-FrameDuping"] = "False"
            coreVariablesMap["mupen64plus-Framerate"] = "Original"
            coreVariablesMap["mupen64plus-virefresh"] = "Auto"
            coreVariablesMap["mupen64plus-EnableLODEmulation"] = "False"
            coreVariablesMap["mupen64plus-EnableN64DepthCompare"] = "False"
            coreVariablesMap["mupen64plus-EnableHWLighting"] = "False"
            coreVariablesMap["mupen64plus-CorrectTexrectCoords"] = "Off"
            coreVariablesMap["mupen64plus-EnableTexCoordBounds"] = "False"
            coreVariablesMap["mupen64plus-EnableInaccurateTextureCoordinates"] = "False"
            coreVariablesMap["mupen64plus-BackgroundMode"] = "OnePiece"
            coreVariablesMap["mupen64plus-EnableNativeResTexrects"] = "False"
            coreVariablesMap["mupen64plus-txFilterMode"] = "None"
            coreVariablesMap["mupen64plus-txEnhancementMode"] = "None"
            coreVariablesMap["mupen64plus-txFilterIgnoreBG"] = "True"
            coreVariablesMap["mupen64plus-txHiresEnable"] = "False"
            coreVariablesMap["mupen64plus-txHiresFullAlphaChannel"] = "False"
            coreVariablesMap["mupen64plus-MaxHiResTxVramLimit"] = "0"
        } else if (systemID == SystemID.NES) {
            if (!coreVariablesMap.containsKey("fceumm_turbo_enable")) {
                coreVariablesMap["fceumm_turbo_enable"] = "Both"
            }
            if (!coreVariablesMap.containsKey("fceumm_turbo_delay")) {
                coreVariablesMap["fceumm_turbo_delay"] = "3"
            }
        } else if (systemID == SystemID.PSX) {
            // High Definition (HD) 2x internal resolution with texture alignment fix and no glitchy speed hacks
            coreVariablesMap["pcsx_rearmed_neon_enhancement_enable"] = "enabled"
            coreVariablesMap["pcsx_rearmed_neon_enhancement_tex_adj_v2"] = "enabled"
            coreVariablesMap["pcsx_rearmed_neon_enhancement_no_main"] = "disabled"
            coreVariablesMap["pcsx_rearmed_neon_interlace_enable_v2"] = "disabled"
            coreVariablesMap["pcsx_rearmed_dithering"] = "disabled"
            coreVariablesMap["pcsx_rearmed_rgb32_output"] = "enabled"
            coreVariablesMap["pcsx_rearmed_drc"] = "enabled"
            coreVariablesMap["pcsx_rearmed_scale_hires"] = "disabled"
            coreVariablesMap["pcsx_rearmed_spu_thread"] = "enabled"
            coreVariablesMap["pcsx_rearmed_gpu_thread_rendering"] = "enabled"
            coreVariablesMap["pcsx_rearmed_spu_interpolation"] = "cubic"
            coreVariablesMap["pcsx_rearmed_spu_reverb"] = "enabled"
            coreVariablesMap["pcsx_rearmed_cd_readahead"] = "1024"
            coreVariablesMap["pcsx_rearmed_psxclock"] = "70"
            coreVariablesMap["pcsx_rearmed_aspect_ratio"] = "16:9"
        }

        return convertMapToCoreVariables(coreVariablesMap)
    }

    private fun mapN64To169Resolution(res43: String): String {
        return when (res43) {
            "320x240" -> "640x360"
            "640x480" -> "960x540"
            "960x720" -> "1280x720"
            "1280x960" -> "1920x1080"
            "1600x1200" -> "1920x1080"
            "1920x1440" -> "2560x1440"
            "2240x1680" -> "2560x1440"
            "2560x1920" -> "3840x2160"
            "2880x2160" -> "3840x2160"
            "3200x2400" -> "3840x2160"
            "3520x2640" -> "7680x4320"
            "3840x2880" -> "7680x4320"
            else -> "640x360"
        }
    }

    private fun convertMapToCoreVariables(variablesMap: Map<String, String>): List<CoreVariable> {
        return variablesMap.entries.map { CoreVariable(it.key, it.value) }
    }

    private fun convertCoreVariablesToMap(coreVariables: List<CoreVariable>): Map<String, String> {
        return coreVariables.associate { it.key to it.value }
    }

    private suspend fun retrieveCustomCoreVariables(
        systemID: SystemID,
        systemCoreConfig: SystemCoreConfig,
    ): List<CoreVariable> =
        withContext(Dispatchers.IO) {
            val exposedKeys = systemCoreConfig.exposedSettings
            val exposedAdvancedKeys = systemCoreConfig.exposedAdvancedSettings

            val requestedKeys =
                (exposedKeys + exposedAdvancedKeys).map { it.key }
                    .map { computeSharedPreferenceKey(it, systemID.dbname) }

            sharedPreferences.get().all.filter { it.key in requestedKeys }
                .map { (key, value) ->
                    val result =
                        when (value!!) {
                            is Boolean -> if (value as Boolean) "enabled" else "disabled"
                            is String -> value as String
                            else -> throw InvalidParameterException("Invalid setting in SharedPreferences")
                        }
                    CoreVariable(computeOriginalKey(key, systemID.dbname), result)
                }
        }

    companion object {
        private const val RETRO_OPTION_PREFIX = "cv"

        fun computeSharedPreferenceKey(
            retroVariableName: String,
            systemID: String,
        ): String {
            return "${computeSharedPreferencesPrefix(systemID)}$retroVariableName"
        }

        fun computeOriginalKey(
            sharedPreferencesKey: String,
            systemID: String,
        ): String {
            return sharedPreferencesKey.replace(computeSharedPreferencesPrefix(systemID), "")
        }

        private fun computeSharedPreferencesPrefix(systemID: String): String {
            return "${RETRO_OPTION_PREFIX}_${systemID}_"
        }
    }
}
