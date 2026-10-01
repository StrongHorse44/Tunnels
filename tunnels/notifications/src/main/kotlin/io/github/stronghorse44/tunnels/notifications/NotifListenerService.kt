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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bound by the system once the user grants Notification access. For every posted notification it writes
 * one events-table row: subject = posting package, summary = [NotifRecord] flags. No title, text, extras
 * or icons are read into memory beyond what the system hands over, and none of them are stored.
 * Group summaries and this app's own notifications are ignored. Work happens on a bounded queue drained
 * on Dispatchers.IO, so the callbacks never block.
 *
 * The drain never gives up on the store: a Keystore or SQLCipher hiccup costs the posts that arrive while
 * it retries with backoff, and every post lost that way (or to a full queue) is counted in [dropped] so a
 * scan can say so. It also prunes the store itself ([TunnelsStore.maintain]) once a day, so rule #4's
 * 30-day retention holds even when the process lives for weeks without the app being opened.
 */
class NotifListenerService : NotificationListenerService() {
    private class Pending(val packageName: String, val summary: String, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pending>(capacity = QUEUE_CAPACITY)
    private val tracker = PostTracker()

    /** Wall time of the last [TunnelsStore.maintain] this service ran; 0 before the first. Drain coroutine only. */
    private var lastMaintainAt = 0L

    override fun onCreate() {
        super.onCreate()
        scope.launch { drain() }
    }

    /** Writes queued posts for as long as the service lives, reopening the store after every failure. */
    private suspend fun drain() {
        var failures = 0
        for (p in queue) {
            // Opening the store touches the Keystore; TunnelsStore.get caches the instance, so the happy path is one read.
            val store = runCatching { TunnelsStore.get(applicationContext) }.getOrNull()
            val written = store != null && runCatching {
                store.recordEvent(NotifKeys.TUNNEL_ID, NotifKeys.EVENT_POSTED, p.packageName, p.summary, Instant.ofEpochMilli(p.at))
            }.isSuccess
            if (written && store != null) {
                failures = 0
                maintainIfDue(store, System.currentTimeMillis())
            } else {
                droppedCount.incrementAndGet()
                failures++
                delay(backoffMs(failures))
            }
        }
    }

    /** Rule #4 in code: prunes expired rows from this long-lived writer, right after the first write and then daily. */
    private suspend fun maintainIfDue(store: TunnelsStore, now: Long) {
        if (lastMaintainAt != 0L && now - lastMaintainAt < MAINTAIN_EVERY_MS) return
        lastMaintainAt = now
        runCatching { store.maintain(Instant.ofEpochMilli(now)) }
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
        val result = queue.trySend(Pending(sbn.packageName, record.encode(), postTime))
        // A full queue (the store is stuck) drops the newest post; a closed one means we are being destroyed.
        if (result.isFailure && !result.isClosed) droppedCount.incrementAndGet()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn?.key?.let(tracker::removed)
    }

    companion object {
        private const val QUEUE_CAPACITY = 256
        private const val MAINTAIN_EVERY_MS = 24L * 60 * 60 * 1000
        private const val BACKOFF_BASE_MS = 2_000L
        private const val BACKOFF_MAX_MS = 60_000L
        /** `NotificationManager.VISIBILITY_NO_OVERRIDE`, which is not in the public SDK: the channel defers to the notification. */
        private const val VISIBILITY_NO_OVERRIDE = -1000

        private val droppedCount = AtomicInteger()

        /** True between onListenerConnected and onListenerDisconnected, for the scan's summary. */
        @Volatile
        var connected: Boolean = false
            private set

        /** Posts this process failed to record (store unavailable or queue full) since it started. */
        val dropped: Int get() = droppedCount.get()

        /** Exponential backoff after a failed write, capped at a minute: 2 s, 4 s, 8 s, ... 60 s. */
        fun backoffMs(failures: Int): Long =
            (BACKOFF_BASE_MS shl (failures - 1).coerceIn(0, 10)).coerceAtMost(BACKOFF_MAX_MS)

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
