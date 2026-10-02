package io.github.stronghorse44.tunnels.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.TunnelCatalog
import io.github.stronghorse44.tunnels.watchrules.WatchPolicy

/**
 * The one notification a check may raise. It names tunnels and kinds of finding, never apps or evidence, and the
 * lock screen shows only "New findings to review". Tapping it opens the inbox.
 */
object WatchNotifier {
    const val CHANNEL_ID = "watch_findings"
    private const val NOTIFICATION_ID = 4202

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts (or replaces) the notification for [findings]; returns how many it covers, 0 when it posted nothing. */
    fun post(context: Context, findings: List<Finding>): Int {
        if (findings.isEmpty() || !allowed(context)) return 0
        val manager = context.getSystemService(NotificationManager::class.java) ?: return 0
        val content = WatchPolicy.notification(findings) { id -> TunnelCatalog.byId(id)?.title ?: id } ?: return 0
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Background check findings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New findings from the scheduled checks. Names tunnels and kinds only, never apps."
            },
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, InboxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(content.publicTitle)
            .setContentText(content.publicText)
            .build()
        val style = Notification.InboxStyle().setSummaryText(content.text)
        content.lines.forEach { style.addLine(it) }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(style)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return findings.size
    }
}
