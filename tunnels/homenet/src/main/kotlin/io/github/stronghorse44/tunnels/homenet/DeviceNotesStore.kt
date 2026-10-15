package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import io.github.stronghorse44.tunnels.lan.CensusMessages
import io.github.stronghorse44.tunnels.lan.DeviceKeys
import io.github.stronghorse44.tunnels.lan.Labels
import io.github.stronghorse44.tunnels.lan.LanHost
import io.github.stronghorse44.tunnels.lan.Presence
import io.github.stronghorse44.tunnels.lan.Watch
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One network's notes as the screen shows them: the user's names, the presence counts and the watch list. */
data class NotesView(val tag: String, val labels: Map<String, String>, val book: Presence.Book, val watched: List<Watch.State>) {
    fun label(host: LanHost): String? = labels[host.noteKey]

    fun entry(host: LanHost): Presence.Entry? = book.entries[host.noteKey]

    fun pattern(host: LanHost): Presence.Pattern? = entry(host)?.let { Presence.pattern(it, book, today()) }

    fun isWatched(host: LanHost): Boolean = Watch.isWatched(watched, tag, host.noteKey)

    companion object {
        fun today(): Long = LocalDate.now().toEpochDay()
    }
}

/**
 * The device notes' rows in the encrypted store's settings table (RJ, 2026-10-11): per network, the presence counts
 * ([PRESENCE] + tag) and the user's names ([LABELS] + tag); one watch list for every network ([WATCH]). The logic is in
 * `core/lan` ([Presence], [Labels], [Watch], [DeviceKeys]); this is the adapter. Every change goes through
 * [TunnelsStore.updateSetting], so a scan and an edit on the screen never overwrite each other.
 */
class DeviceNotesStore(context: Context) {
    private val app = context.applicationContext
    private val store: TunnelsStore get() = TunnelsStore.get(app)

    /** What a scan adds to its observations: the names to show and the news about watched devices, by key. */
    data class ScanNotes(val labels: Map<String, String>, val events: Map<String, Watch.Event>) {
        companion object {
            val EMPTY = ScanNotes(emptyMap(), emptyMap())
        }
    }

    /**
     * The notes' part of a scan of the network [tag] on [today] that saw [hosts] ([now] is what it saw of each, by key):
     * moves notes to keys that now recognise their device ([DeviceKeys.rekey]), counts the presence, and compares the
     * watched devices. A store that cannot be read or changed gives no notes and leaves them as they were.
     */
    suspend fun afterScan(tag: String, hosts: List<DeviceKeys.Host>, today: Long, now: Map<String, Watch.Now>, portsComplete: Boolean): ScanNotes =
        withContext(Dispatchers.IO) {
            try {
                var labels = emptyMap<String, String>()
                store.updateSetting(LABELS + tag) { old ->
                    labels = DeviceKeys.rekey(Labels.parse(old), hosts)
                    Labels.encode(labels).ifEmpty { null }
                }
                store.updateSetting(PRESENCE + tag) { old ->
                    val book = Presence.Book.parse(old)
                    Presence.update(book.copy(entries = DeviceKeys.rekey(book.entries, hosts)), today, hosts.map { it.key }).encode()
                }
                var events = emptyMap<String, Watch.Event>()
                store.updateSetting(WATCH) { old ->
                    val states = Watch.parse(old)
                    val here = DeviceKeys.rekey(states.filter { it.tag == tag }.associateBy { it.key }, hosts).map { (key, s) -> s.copy(key = key) }
                    val outcome = Watch.afterScan(states.filter { it.tag != tag } + here, tag, now, portsComplete)
                    events = outcome.events
                    Watch.encode(outcome.states).ifEmpty { null }
                }
                ScanNotes(labels, events)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                ScanNotes.EMPTY
            }
        }

    /** The network [tag]'s notes for the screen, or null when the store cannot be read. */
    suspend fun view(tag: String): NotesView? = withContext(Dispatchers.IO) {
        try {
            NotesView(
                tag,
                Labels.parse(store.setting(LABELS + tag)),
                Presence.Book.parse(store.setting(PRESENCE + tag)),
                Watch.parse(store.setting(WATCH)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** Names the device [key] on [tag] (a blank [label] removes the name). Answers a one-line result. */
    suspend fun setLabel(tag: String, key: String, label: String?): String = guarded {
        var full = false
        store.updateSetting(LABELS + tag) { old ->
            val labels = Labels.parse(old)
            val next = Labels.with(labels, key, label)
            if (next == null) full = true
            Labels.encode(next ?: labels).ifEmpty { null }
        }
        when {
            full -> "You have named ${Labels.MAX_LABELS} devices on this network, the most it keeps: clear a name to make room."
            Labels.clean(label) == null -> "Name removed."
            else -> "Saved. Findings use it from your next scan."
        }
    }

    /** Starts or stops watching the device [key] on [tag], as the screen shows it now ([now]). Answers a one-line result. */
    suspend fun setWatched(tag: String, key: String, watch: Boolean, now: Watch.Now): String = guarded {
        var full = false
        store.updateSetting(WATCH) { old ->
            val states = Watch.parse(old)
            val next = Watch.with(states, tag, key, watch, now)
            if (next == null) full = true
            Watch.encode(next ?: states).ifEmpty { null }
        }
        when {
            full -> "You watch ${Watch.MAX_WATCHED} devices, the most Tunnels keeps: stop watching one to make room."
            watch -> "Watching it. Your next scans say when it comes back, opens or closes a port, or changes its name."
            else -> "No longer watched."
        }
    }

    /** "Stop watching" from a finding: the device [key] on every network. Answers a one-line result. */
    suspend fun unwatch(key: String): String = guarded {
        store.updateSetting(WATCH) { old -> Watch.encode(Watch.without(Watch.parse(old), key)).ifEmpty { null } }
        "No longer watched. Dismiss the finding to clear it."
    }

    /** Forgets the network [tag]'s notes (Forget on the network). */
    suspend fun forget(tag: String) {
        guarded {
            store.putSetting(LABELS + tag, null)
            store.putSetting(PRESENCE + tag, null)
            store.updateSetting(WATCH) { old -> Watch.encode(Watch.parse(old).filterNot { it.tag == tag }).ifEmpty { null } }
            ""
        }
    }

    /** Runs a write on the IO dispatcher; a store that cannot be changed answers with a message and keeps what it had. */
    private suspend fun guarded(block: suspend () -> String): String = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            CensusMessages.NOT_SAVED
        }
    }

    companion object {
        const val PRESENCE = Presence.SETTINGS_PREFIX
        const val LABELS = "homenet.labels."
        const val WATCH = "homenet.watch"
    }
}
