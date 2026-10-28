package io.github.pablocollantes.releasethebee.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: send a raw control request to the connected RTB from adb, to explore
 * commands the protocol doesn't document. Only the shell can call it (the manifest
 * requires the DUMP permission, which apps can't hold):
 *
 * ```
 * adb shell am broadcast -n io.github.pablocollantes.releasethebee/.service.DebugReceiver \
 *     --es hex 17c0
 * adb logcat -s releasethebee        # the reply shows up as "RTB ok …" / "RTB: …"
 * ```
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!AppStatus.snapshot.serviceRunning) return AppStatus.log("debug: the service isn't running")
        ctx.startService(
            Intent(ctx, RtbService::class.java)
                .setAction(RtbService.ACTION_DEBUG_SEND)
                .putExtra(RtbService.EXTRA_HEX, intent.getStringExtra(RtbService.EXTRA_HEX)),
        )
    }
}
