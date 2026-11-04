package com.omnidroid.app.shared.game

import android.content.Context
import com.omnidroid.app.shared.settings.HDModeQuality
import com.omnidroid.app.utils.android.getGLSLVersion
import com.omnidroid.lib.library.GameSystem
import com.omnidroid.lib.library.SystemID
import com.swordfish.libretrodroid.ShaderConfig
import timber.log.Timber

object ShaderChooser {
    fun getShaderForSystem(
        context: Context,
        hdMode: Boolean,
        requestedHdModeQuality: HDModeQuality,
        screenFilter: String,
        system: GameSystem,
    ): ShaderConfig {
        Timber.i(
            "Choosing shader for this config: screenFilter= $screenFilter hdMode=$hdMode hdModeQuality=$requestedHdModeQuality",
        )
        val hdModeQuality =
            if (context.getGLSLVersion() >= 3) {
                requestedHdModeQuality
            } else {
                HDModeQuality.LOW
            }
        return when {
            hdMode -> getHDShaderForSystem(system, hdModeQuality)
            else ->
                when (screenFilter) {
                    "crt" -> ShaderConfig.CRT
                    "lcd" -> ShaderConfig.LCD
                    "smooth" -> ShaderConfig.Default
                    "sharp" -> ShaderConfig.Sharp
                    else -> getDefaultShaderForSystem(system)
                }
        }
    }

    private fun getDefaultShaderForSystem(system: GameSystem): ShaderConfig {
        return ShaderConfig.Sharp
    }

    private fun is3DSystem(system: GameSystem): Boolean {
        return when (system.id) {
            SystemID.PSX,
            SystemID.N64,
            SystemID.PSP,
            SystemID.NDS,
            SystemID.NINTENDO_3DS,
            SystemID.PS2,
            SystemID.GAMECUBE,
            SystemID.WII,
            SystemID.DREAMCAST,
            SystemID.WII_U -> true
            else -> false
        }
    }

    private fun getHDShaderForSystem(
        system: GameSystem,
        hdModeQuality: HDModeQuality,
    ): ShaderConfig {
        // 3D systems must always use ShaderConfig.Sharp:
        // Complex 2D vectorization shaders (CUT/CUT2/CUT3) on 3D geometry causes GPU stalls
        // dropping framerate to ~33 FPS and starving the audio buffer.
        // Sharp provides pristine, full-speed 60 FPS HD rendering without audio issues.
        if (is3DSystem(system)) {
            return ShaderConfig.Sharp
        }
        return when (hdModeQuality) {
            HDModeQuality.LOW -> getLowQualityHdMode(system)
            HDModeQuality.MEDIUM -> getMediumQualityHdMode(system)
            HDModeQuality.HIGH -> getHighQualityHdMode(system)
        }
    }

    private fun getLowQualityHdMode(system: GameSystem): ShaderConfig {
        return ShaderConfig.Sharp
    }

    private fun getMediumQualityHdMode(system: GameSystem): ShaderConfig {
        val upscale8BitsMobile =
            ShaderConfig.CUT(
                blendMinContrastEdge = 0.00f,
                blendMaxContrastEdge = 0.50f,
                blendMaxSharpness = 0.80f,
            )

        val upscale8Bits =
            ShaderConfig.CUT(
                blendMinContrastEdge = 0.00f,
                blendMaxContrastEdge = 0.50f,
                blendMaxSharpness = 0.75f,
            )

        val upscale16BitsMobile =
            ShaderConfig.CUT(
                blendMinContrastEdge = 0.10f,
                blendMaxContrastEdge = 0.60f,
                blendMaxSharpness = 0.80f,
            )

        val upscale16Bits =
            ShaderConfig.CUT(
                blendMinContrastEdge = 0.10f,
                blendMaxContrastEdge = 0.60f,
                blendMaxSharpness = 0.75f,
            )

        val upscale32Bits = ShaderConfig.Sharp
        val modern = ShaderConfig.Sharp

        return getConfigForSystem(
            system,
            upscale16BitsMobile,
            upscale8BitsMobile,
            upscale32Bits,
            upscale16Bits,
            upscale8Bits,
            modern,
        )
    }

    private fun getHighQualityHdMode(system: GameSystem): ShaderConfig {
        // Use optimized single-pass CUT without iterative loops (CUT3) so framerates remain 60 FPS
        // without audio stutter or frame drops on TV GPUs.
        val upscale8BitsMobile =
            ShaderConfig.CUT(
                blendMinContrastEdge = 0.00f,
                blendMaxContrastEdge = 0.40f,
                blendMaxSharpness = 0.85f,
            )

        val upscale8Bits =
            ShaderConfig.CUT(
                blendMinContrastEdge = 0.00f,
                blendMaxContrastEdge = 0.40f,
                blendMaxSharpness = 0.80f,
            )

        val upscale16BitsMobile =
            ShaderConfig.CUT(
                blendMinContrastEdge = 0.10f,
                blendMaxContrastEdge = 0.50f,
                blendMaxSharpness = 0.85f,
            )

        val upscale16Bits =
            ShaderConfig.CUT(
                blendMinContrastEdge = 0.10f,
                blendMaxContrastEdge = 0.50f,
                blendMaxSharpness = 0.80f,
            )

        val upscale32Bits = ShaderConfig.Sharp
        val modern = ShaderConfig.Sharp

        return getConfigForSystem(
            system,
            upscale16BitsMobile,
            upscale8BitsMobile,
            upscale32Bits,
            upscale16Bits,
            upscale8Bits,
            modern,
        )
    }

    private fun getConfigForSystem(
        system: GameSystem,
        upscale16BitsMobile: ShaderConfig,
        upscale8BitsMobile: ShaderConfig,
        upscale32Bits: ShaderConfig,
        upscale16Bits: ShaderConfig,
        upscale8Bits: ShaderConfig,
        modern: ShaderConfig,
    ): ShaderConfig {
        return when (system.id) {
            SystemID.GBA -> upscale16BitsMobile
            SystemID.GBC -> upscale8BitsMobile
            SystemID.GB -> upscale8BitsMobile
            SystemID.N64 -> upscale32Bits
            SystemID.GENESIS -> upscale16Bits
            SystemID.SEGACD -> upscale16Bits
            SystemID.NES -> upscale8Bits
            SystemID.SNES -> upscale16Bits
            SystemID.FBNEO -> upscale32Bits
            SystemID.SMS -> upscale8Bits
            SystemID.PSP -> modern
            SystemID.NDS -> upscale32Bits
            SystemID.GG -> upscale8BitsMobile
            SystemID.ATARI2600 -> upscale8Bits
            SystemID.PSX -> upscale32Bits
            SystemID.MAME2003PLUS -> upscale32Bits
            SystemID.ATARI7800 -> upscale8Bits
            SystemID.PC_ENGINE -> upscale16Bits
            SystemID.LYNX -> upscale8BitsMobile
            SystemID.DOS -> upscale32Bits
            SystemID.NGP -> upscale8BitsMobile
            SystemID.NGC -> upscale8BitsMobile
            SystemID.WS -> upscale16BitsMobile
            SystemID.WSC -> upscale16BitsMobile
            SystemID.NINTENDO_3DS -> modern
            SystemID.PS2 -> modern
            SystemID.GAMECUBE -> modern
            SystemID.WII -> modern
            SystemID.DREAMCAST -> modern
            SystemID.WII_U -> modern
        }
    }
}
