package io.github.stronghorse44.tunnels.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.stronghorse44.tunnels.notifrules.NotifKeys
import io.github.stronghorse44.tunnels.notifrules.NotifRecord
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Bound by the system once the user grants Notification access. For every posted notification it writes
 * one events-table row: subject = posting package, summary = [NotifRecord] flags. No title, text, extras
 * or icons are read into memory beyond what the system hands over, and none of them are stored.
 * Group summaries and this app's own notifications are ignored. Work happens on a bounded queue drained
 * on Dispatchers.IO, so the callbacks never block.
 */
class NotifListenerService : NotificationListenerService() {
    private class Pending(val packageName: String, val summary: String, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pending>(capacity = QUEUE_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val tracker = PostTracker()

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            // Opening the store touches the Keystore: do it here, off the main thread, once.
            val store = runCatching { TunnelsStore.get(applicationContext) }.getOrNull() ?: return@launch
            for (p in queue) {
                runCatching {
                    store.recordEvent(NotifKeys.TUNNEL_ID, NotifKeys.EVENT_POSTED, p.packageName, p.summary, Instant.ofEpochMilli(p.at))
                }
            }
        }
    }

    override fun onListenerConnected() {
        connected = true
        scope.launch {
            // Everything already showing is an update from now on, not a new post.
            runCatching { activeNotifications }.getOrNull()?.forEach { tracker.seen(it.key) }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        tracker.clear()
    }

    override fun onDestroy() {
        connected = false
        queue.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val n = sbn?.notification ?: return
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (!tracker.shouldCount(sbn.key, sbn.isOngoing, alertOnce)) return

        val ranking = Ranking()
        val ranked = rankingMap != null && runCatching { rankingMap.getRanking(sbn.key, ranking) }.getOrDefault(false)
        val importance = if (ranked) ranking.importance else NotificationManager.IMPORTANCE_UNSPECIFIED
        val channelVisibility = if (ranked) ranking.channel?.lockscreenVisibility ?: VISIBILITY_NO_OVERRIDE else VISIBILITY_NO_OVERRIDE
        val visibility = if (channelVisibility != VISIBILITY_NO_OVERRIDE) channelVisibility else n.visibility
        val silent = ranked && importance <= NotificationManager.IMPORTANCE_LOW
        val postTime = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
        val hour = runCatching { Instant.ofEpochMilli(postTime).atZone(ZoneId.systemDefault()).hour }.getOrDefault(-1)

        val record = NotifRecord.of(importance, visibility, n.category, sbn.isOngoing, silent, hour)
        queue.trySend(Pending(sbn.packageName, record.encode(), postTime))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn?.key?.let(tracker::removed)
    }

    companion object {
        private const val QUEUE_CAPACITY = 256
        /** `NotificationManager.VISIBILITY_NO_OVERRIDE`, which is not in the public SDK: the channel defers to the notification. */
        private const val VISIBILITY_NO_OVERRIDE = -1000

        /** True between onListenerConnected and onListenerDisconnected, for the scan's summary. */
        @Volatile
        var connected: Boolean = false
            private set

        fun component(context: Context): ComponentName = ComponentName(context, NotifListenerService::class.java)

        /** Whether the user has granted Notification access to this listener. */
        fun isAccessGranted(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
        }.getOrDefault(false)

        /** Asks the system to bind the listener again (helps after a reinstall or a crash). */
        fun requestReconnect(context: Context) {
            runCatching { NotificationListenerService.requestRebind(component(context)) }
        }
    }
}
