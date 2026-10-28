package io.github.pablocollantes.releasethebee.service

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/** Buzzes that confirm a command recording started (one) or ended (two), felt through a pocket. */
class Haptics(ctx: Context) {

    private val vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator

    // A response to pressing the RTB's button: hardware feedback is what that is, and Android
    // plays it for an app in the background (a foreground service is foreground anyway).
    private val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

    fun commandStart() = play(longArrayOf(0, 150))
    fun commandEnd() = play(longArrayOf(0, 90, 110, 90))

    private fun play(timings: LongArray) {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1), attrs)
    }
}
