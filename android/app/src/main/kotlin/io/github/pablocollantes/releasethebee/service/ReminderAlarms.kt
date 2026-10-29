package io.github.pablocollantes.releasethebee.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.pablocollantes.releasethebee.R
import io.github.pablocollantes.releasethebee.data.Library
import io.github.pablocollantes.releasethebee.data.Reminder
import io.github.pablocollantes.releasethebee.data.Reminders
import io.github.pablocollantes.releasethebee.ui.Format
import io.github.pablocollantes.releasethebee.ui.MainActivity

/**
 * Reminders as phone notifications. Each reminder with a time gets an exact alarm; when it
 * goes off, [ReminderReceiver] posts the notification (Done, Snooze 10 min). Alarms don't
 * survive a reboot, so [BootReceiver] calls [sync] again, and so does every change to the
 * list. A reminder whose time passed while the phone couldn't ring (off, or the command
 * was processed late) rings as soon as the app notices, if it's less than a day late.
 *
 * Kept in shared preferences: which alarms are set, which reminders have rung (by id and
 * time, so moving a reminder makes it ring again), snoozes, and which times the user
 * confirmed.
 */
object ReminderAlarms {

    private const val CHANNEL = "reminders"
    private const val LATE_LIMIT_MS = 24 * 3600 * 1000L
    const val SNOOZE_MS = 10 * 60 * 1000L

    /** Bring the alarms in line with [Library]. Main thread. */
    fun sync(ctx: Context, now: Long = System.currentTimeMillis()) {
        Library.load(ctx)
        val p = prefs(ctx)
        val all = Reminders.of(Library.index.entries)
        // The first time, reminders already past were from before reminders rang: let them be.
        val first = !p.getBoolean("initialized", false)
        val rung = p.getStringSet("rung", emptySet())!!.toMutableSet()
        val want = HashMap<String, Long>()          // id → when its alarm goes off
        for (r in all) {
            val at = r.at ?: continue
            if (r.done) continue
            val snooze = p.getLong("snooze:${r.id}", 0L)
            if (snooze == 0L && key(r) in rung) continue
            val fireAt = if (snooze > 0L) snooze else at
            when {
                fireAt > now -> want[r.id] = fireAt
                first || now - fireAt > LATE_LIMIT_MS -> rung += key(r)
                else -> {
                    show(ctx, r)
                    rung += key(r)
                    p.edit().remove("snooze:${r.id}").apply()
                }
            }
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val set = p.getStringSet("scheduled", emptySet())!!
        for (s in set) {
            val id = s.substringBefore('@')
            if (want[id] != s.substringAfter('@').toLongOrNull()) am.cancel(alarm(ctx, id))
        }
        for ((id, at) in want) {
            if ("$id@$at" in set) continue
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(ctx, id))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(ctx, id))
        }
        val ids = all.map { it.id }.toSet()
        p.edit()
            .putStringSet("scheduled", want.map { (id, at) -> "$id@$at" }.toSet())
            .putStringSet("rung", rung.filter { it.substringBefore('@') in ids }.toSet())
            .putBoolean("initialized", true)
            .apply()
    }

    /** After a reboot or an app update, which clear every alarm: set them all again. */
    fun rearm(ctx: Context) {
        prefs(ctx).edit().remove("scheduled").apply()
        sync(ctx)
    }

    /** The alarm for [id] went off. */
    fun ring(ctx: Context, id: String) {
        Library.load(ctx)
        val r = Reminders.of(Library.index.entries).firstOrNull { it.id == id } ?: return
        if (!r.done && r.at != null) {
            show(ctx, r)
            val p = prefs(ctx)
            p.edit()
                .putStringSet("rung", p.getStringSet("rung", emptySet())!! + key(r))
                .remove("snooze:$id")
                .apply()
        }
        sync(ctx)
    }

    fun snooze(ctx: Context, id: String) {
        dismiss(ctx, id)
        prefs(ctx).edit().putLong("snooze:$id", System.currentTimeMillis() + SNOOZE_MS).apply()
        sync(ctx)
    }

    fun dismiss(ctx: Context, id: String) =
        ctx.getSystemService(NotificationManager::class.java).cancel(TAG, id.hashCode())

    /** The user looked at the time and said it's right. */
    fun confirm(ctx: Context, r: Reminder) {
        val p = prefs(ctx)
        p.edit().putStringSet("confirmed", p.getStringSet("confirmed", emptySet())!! + key(r)).apply()
    }

    fun confirmed(ctx: Context, r: Reminder) = key(r) in prefs(ctx).getStringSet("confirmed", emptySet())!!

    // ------------------------------------------------------------------ notification

    private fun show(ctx: Context, r: Reminder) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you set by voice"
            },
        )
        val fmt = Format(use24h = android.text.format.DateFormat.is24HourFormat(ctx))
        val open = PendingIntent.getActivity(
            ctx, r.id.hashCode(), MainActivity.intent(ctx, MainActivity.Tab.TODO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(r.text)
            .setContentText("Reminder · ${r.at?.let { fmt.clock(it) } ?: ""}")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Done", action(ctx, ReminderReceiver.ACTION_DONE, r.id)).build())
            .addAction(Notification.Action.Builder(null, "Snooze 10 min", action(ctx, ReminderReceiver.ACTION_SNOOZE, r.id)).build())
            .build()
        nm.notify(TAG, r.id.hashCode(), n)
    }

    private fun alarm(ctx: Context, id: String) = action(ctx, ReminderReceiver.ACTION_RING, id)

    private fun action(ctx: Context, action: String, id: String) = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, ReminderReceiver::class.java).setAction(action).setData(Uri.fromParts("rtb-reminder", id, null)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun key(r: Reminder) = "${r.id}@${r.at}"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private const val TAG = "reminder"
}

/** Alarms going off, and the notification's buttons. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.data?.schemeSpecificPart ?: return
        when (intent.action) {
            ACTION_RING -> ReminderAlarms.ring(ctx, id)
            ACTION_SNOOZE -> ReminderAlarms.snooze(ctx, id)
            ACTION_DONE -> {
                ReminderAlarms.dismiss(ctx, id)
                Library.setDone(ctx, id, true)
                // Keep the process until the change is on disk; the receiver gets it when it can.
                val result = goAsync()
                Library.afterWrites { result.finish() }
            }
        }
    }

    companion object {
        const val ACTION_RING = "io.github.pablocollantes.releasethebee.REMINDER_RING"
        const val ACTION_DONE = "io.github.pablocollantes.releasethebee.REMINDER_DONE"
        const val ACTION_SNOOZE = "io.github.pablocollantes.releasethebee.REMINDER_SNOOZE"
    }
}
