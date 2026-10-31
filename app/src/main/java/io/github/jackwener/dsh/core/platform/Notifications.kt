package io.github.jackwener.dsh.core.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.jackwener.dsh.MainActivity
import io.github.jackwener.dsh.R

object Notifications {
    const val CHANNEL_ENGINE = "engine"
    const val CHANNEL_TASKS = "tasks"
    const val CHANNEL_ATTENTION = "attention"
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_UPDATES = "updates"
    const val ID_ENGINE = 1
    const val ID_MONITOR = 2
    const val ID_BALL = 3
    const val ID_UPDATE = 4

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_ENGINE, context.getString(R.string.channel_engine), NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(CHANNEL_TASKS, context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_MONITOR, context.getString(R.string.channel_monitor), NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        ))
    }

    fun openSession(context: Context, sessionId: String): PendingIntent =
        PendingIntent.getActivity(context, sessionId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** 设置 at [section] (a SettingsSection name), e.g. 关于 for an app update. */
    fun openSection(context: Context, section: String): PendingIntent =
        PendingIntent.getActivity(context, "section:$section".hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, section)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** [open] overrides the tap target ([sessionId]'s conversation otherwise). */
    fun post(context: Context, channel: String, id: Int, title: String, text: String, sessionId: String?, open: PendingIntent? = null) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        (open ?: sessionId?.let { openSession(context, it) })?.let(b::setContentIntent)
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id, b.build())
    }
}
