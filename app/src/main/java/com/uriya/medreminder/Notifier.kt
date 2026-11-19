package com.uriya.medreminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.provider.Settings
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CH_DOSE_PREFIX = "doses"
    private const val CH_REFILL = "refill"

    /**
     * Android fixes a channel's sound once it is created, so every sound setting
     * gets its own channel id; old ones are deleted.
     */
    private fun doseChannelId(ctx: Context): String {
        val mode = SoundPrefs.mode(ctx)
        val tone = SoundPrefs.tone(ctx)?.toString()?.hashCode()?.toUInt()?.toString(16) ?: "def"
        return if (mode == SoundMode.SOUND) "${CH_DOSE_PREFIX}_sound_$tone" else "${CH_DOSE_PREFIX}_${mode.name.lowercase()}"
    }

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val id = doseChannelId(ctx)
        nm.notificationChannels
            .filter { it.id.startsWith(CH_DOSE_PREFIX) && it.id != id }
            .forEach { nm.deleteNotificationChannel(it.id) }

        val mode = SoundPrefs.mode(ctx)
        val dose = NotificationChannel(id, tr("תזכורות לתרופות", "Medication reminders"), NotificationManager.IMPORTANCE_HIGH).apply {
            description = tr("התראה בזמן שצריך לקחת תרופה", "Alert when it's time to take a medication")
            when (mode) {
                SoundMode.SOUND -> {
                    setSound(
                        SoundPrefs.tone(ctx) ?: Settings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                }
                SoundMode.VIBRATE -> {
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                }
                SoundMode.SILENT -> {
                    setSound(null, null)
                    enableVibration(false)
                }
            }
        }
        val refill = NotificationChannel(CH_REFILL, tr("מלאי נמוך", "Low stock"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = tr("התראה כשהמלאי של תרופה עומד להיגמר", "Alert when a medication is running out")
        }
        nm.createNotificationChannels(listOf(dose, refill))
    }

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(ctx: Context, req: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun dose(ctx: Context, d: Dose) {
        if (!hasPermission(ctx)) return
        val id = d.key.hashCode()
        fun action(act: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, ReminderReceiver::class.java)
                .setAction(act)
                .putExtra(Scheduler.EXTRA_MED, d.med.id)
                .putExtra(Scheduler.EXTRA_DATE, d.date.toString())
                .putExtra(Scheduler.EXTRA_TIME, d.time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val details = listOf(
            d.med.dose,
            if (d.med.perDose > 1) tr("${d.med.perDose} יח׳", "${d.med.perDose} units") else "",
            if (d.med.food != FoodRule.NONE) d.med.food.label else "",
            d.med.notes
        ).filter { it.isNotBlank() }.joinToString(" · ")

        val n = NotificationCompat.Builder(ctx, doseChannelId(ctx))
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(MED_COLORS[d.med.colorIndex.mod(MED_COLORS.size)].toInt())
            .setContentTitle(tr("${d.med.form.emoji} הגיע הזמן: ${d.med.name}", "${d.med.form.emoji} Time for ${d.med.name}"))
            .setContentText(listOf(d.time, details).filter { it.isNotBlank() }.joinToString(" · "))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(ctx, id))
            .setAutoCancel(false)
            .addAction(0, tr("✓ נלקח", "✓ Taken"), action(Scheduler.ACTION_TAKE))
            .addAction(0, tr("⏰ עוד ${Scheduler.SNOOZE_MINUTES} דק׳", "⏰ ${Scheduler.SNOOZE_MINUTES} more min"), action(Scheduler.ACTION_SNOOZE))
            .addAction(0, tr("דלג", "Skip"), action(Scheduler.ACTION_SKIP))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    /** One notification per time slot: single dose → regular, several → combined with "take all". */
    fun notifyDoses(ctx: Context, doses: List<Dose>) {
        doses.groupBy { it.date to it.time }.values.forEach { list ->
            if (list.size == 1) dose(ctx, list.first()) else group(ctx, list)
        }
    }

    private fun groupId(date: String, time: String) = "group|$date|$time".hashCode()

    fun group(ctx: Context, list: List<Dose>) {
        if (!hasPermission(ctx) || list.isEmpty()) return
        val date = list.first().date.toString()
        val time = list.first().time
        val id = groupId(date, time)
        fun action(act: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, ReminderReceiver::class.java)
                .setAction(act)
                .putExtra(Scheduler.EXTRA_MED, Scheduler.ALL)
                .putExtra(Scheduler.EXTRA_DATE, date)
                .putExtra(Scheduler.EXTRA_TIME, time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val style = NotificationCompat.InboxStyle()
        list.forEach { d ->
            val extra = listOf(
                d.med.dose,
                if (d.med.perDose > 1) tr("${d.med.perDose} יח׳", "${d.med.perDose} units") else "",
                if (d.med.food != FoodRule.NONE) d.med.food.label else ""
            ).filter { it.isNotBlank() }.joinToString(" · ")
            style.addLine("${d.med.form.emoji} ${d.med.name}" + if (extra.isNotEmpty()) " — $extra" else "")
        }
        val title = tr("הגיע הזמן ל-${list.size} תרופות ($time)", "Time for ${list.size} medications ($time)")
        style.setBigContentTitle(title)
        val n = NotificationCompat.Builder(ctx, doseChannelId(ctx))
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setColor(MED_COLORS[0].toInt())
            .setContentTitle("💊 $title")
            .setContentText(list.joinToString(", ") { it.med.name })
            .setStyle(style)
            .setNumber(list.size)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(ctx, id))
            .setAutoCancel(false)
            .addAction(0, tr("✓ לקחתי הכול (${list.size})", "✓ Took all (${list.size})"), action(Scheduler.ACTION_TAKE_ALL))
            .addAction(0, tr("⏰ עוד ${Scheduler.SNOOZE_MINUTES} דק׳", "⏰ ${Scheduler.SNOOZE_MINUTES} more min"), action(Scheduler.ACTION_SNOOZE))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelGroup(ctx: Context, date: String, time: String) {
        NotificationManagerCompat.from(ctx).cancel(groupId(date, time))
    }

    fun refill(ctx: Context, med: Medication) {
        if (!hasPermission(ctx)) return
        val id = "refill|${med.id}".hashCode()
        val n = NotificationCompat.Builder(ctx, CH_REFILL)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle(tr("המלאי של ${med.name} נמוך", "${med.name} is running low"))
            .setContentText(tr("נשארו ${med.stock} יחידות — כדאי לחדש מרשם / לקנות", "${med.stock} units left — time to refill"))
            .setContentIntent(openAppIntent(ctx, id))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun test(ctx: Context) {
        if (!hasPermission(ctx)) return
        val n = NotificationCompat.Builder(ctx, doseChannelId(ctx))
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle(tr("💊 התראת בדיקה", "💊 Test notification"))
            .setContentText(tr("ההתראות עובדות כמו שצריך", "Notifications are working"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(ctx, 7))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(7, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(ctx: Context, key: String) {
        NotificationManagerCompat.from(ctx).cancel(key.hashCode())
    }
}
