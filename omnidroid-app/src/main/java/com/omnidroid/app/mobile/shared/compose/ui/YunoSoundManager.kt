package com.omnidroid.app.mobile.shared.compose.ui

import android.content.Context

object YunoSoundManager {
    fun init(context: Context) {
        // No-op for optimal performance and no audio resource contention on TV
    }

    fun playClick() {}
    fun playEnter() {}
    fun playOpenRail() {}
    fun playDouble() {}
    fun playIntro() {}
}
