package io.github.pablocollantes.releasethebee.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.pablocollantes.releasethebee.store.Settings

/**
 * After a reboot or an app update: set the reminders' alarms again (a reboot clears them),
 * and restart the link if the user had it running.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ReminderAlarms.rearm(ctx)
        if (!Settings(ctx).serviceEnabled || !RtbService.hasPermissions(ctx)) return
        RtbService.send(ctx, RtbService.ACTION_START)
    }
}
