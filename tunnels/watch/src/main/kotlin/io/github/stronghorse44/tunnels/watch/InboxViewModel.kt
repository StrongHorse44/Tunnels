package io.github.stronghorse44.tunnels.watch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.runtime.ActionRunner
import io.github.stronghorse44.tunnels.runtime.ScanState
import io.github.stronghorse44.tunnels.runtime.SnapshotEngine.Companion.toModel
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import io.github.stronghorse44.tunnels.watchrules.Inbox
import io.github.stronghorse44.tunnels.watchrules.WatchPolicy
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import io.github.stronghorse44.tunnels.watchrules.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class InboxState(
    val loaded: Boolean = false,
    /** Every open finding, most severe first. */
    val findings: List<Finding> = emptyList(),
    /** The previous visit; null on the first. Findings first seen after it are marked new. */
    val lastVisit: Instant? = null,
    val filter: Inbox.Filter = Inbox.Filter.ALL,
    val tunnel: String? = null,
    val settings: WatchSettings = WatchSettings(),
    val status: WatchStatus = WatchStatus(),
    val scan: ScanState = ScanState(),
    val checking: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val scheduled: Boolean = false,
    val message: String? = null,
)

class InboxViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(InboxState())
    val state: StateFlow<InboxState> = _state.asStateFlow()
    private var runtime: TunnelsRuntime? = null
    private var opened = false

    fun open() {
        if (opened) return
        opened = true
        viewModelScope.launch {
            val rt = TunnelsRuntime.get(app).also { runtime = it }
            val store = rt.store
            val previous = withContext(Dispatchers.IO) {
                val last = store.setting(Inbox.LAST_VISIT_KEY)?.toLongOrNull()
                store.putSetting(Inbox.LAST_VISIT_KEY, System.currentTimeMillis().toString())
                last
            }
            _state.update { it.copy(lastVisit = previous?.let(Instant::ofEpochMilli)) }
            launch { rt.engine.state.collect { s -> _state.update { it.copy(scan = s) } } }
            launch {
                rt.store.dao.allFindingsFlow()
                    // Each tunnel attaches its actions (some ask PackageManager), so off the main thread.
                    .map { rows -> Inbox.sort(rows.map { r -> r.toModel(rt.registry[r.tunnelId]) }) }
                    .flowOn(Dispatchers.IO)
                    .collect { list -> _state.update { it.copy(loaded = true, findings = list) } }
            }
            launch { store.settingFlow(WatchSettings.KEY).collect { v -> _state.update { it.copy(settings = WatchSettings.decode(v)) } } }
            launch { store.settingFlow(WatchStatus.KEY).collect { v -> _state.update { it.copy(status = WatchStatus.decode(v)) } } }
            launch(Dispatchers.IO) {
                runCatching { WatchScheduler.ensure(app) }
                refreshSystem()
            }
        }
    }

    /** Re-reads what can change behind the app's back: the notification permission and the scheduled job. */
    fun refreshSystem() {
        _state.update { it.copy(notificationsAllowed = WatchNotifier.allowed(app), scheduled = WatchScheduler.pending(app) != null) }
    }

    fun setFilter(filter: Inbox.Filter) = _state.update { it.copy(filter = filter) }

    fun setTunnel(id: String?) = _state.update { it.copy(tunnel = id) }

    fun perform(action: FindingAction) {
        viewModelScope.launch {
            val msg = runCatching { ActionRunner.run(app, action) }.getOrElse { it.message ?: "Failed" }
            if (msg != null) _state.update { it.copy(message = msg) }
        }
    }

    fun dismiss(finding: Finding) {
        val rt = runtime ?: return
        viewModelScope.launch(Dispatchers.IO) { rt.store.dao.dismissFinding(finding.id) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Switches checks on or off. The first switch-on runs a check at once, so later ones compare against a baseline. */
    fun setEnabled(on: Boolean) = save(_state.value.settings.copy(enabled = on), reschedule = true) {
        if (on && _state.value.status.neverRan) checkNow()
    }

    fun setInterval(hours: Int) = save(_state.value.settings.copy(intervalHours = hours), reschedule = true)

    fun setNotifyAt(severity: Severity) = save(_state.value.settings.copy(notifyAt = severity), reschedule = false)

    fun toggleExtra(id: String) {
        val extra = _state.value.settings.extra
        save(_state.value.settings.copy(extra = if (id in extra) extra - id else extra + id), reschedule = false)
    }

    private fun save(settings: WatchSettings, reschedule: Boolean, then: () -> Unit = {}) {
        val rt = runtime ?: return
        _state.update { it.copy(settings = settings) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                rt.store.putSetting(WatchSettings.KEY, settings.encode())
                if (reschedule) WatchScheduler.apply(app, settings)
            }
            refreshSystem()
            then()
        }
    }

    /** Runs one check now, in the foreground: no notification, the inbox updates as findings arrive. */
    fun checkNow() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, message = null) }
        viewModelScope.launch {
            val msg = try {
                val outcome = WatchRunner.run(app, notify = false)
                val added = outcome.result.added.size
                buildString {
                    append("Checked ${outcome.plan.tunnels.size} tunnels (${outcome.plan.reason}): ")
                    append(if (added == 0) "nothing new" else "$added new finding${if (added == 1) "" else "s"}")
                    if (!outcome.result.stored) append(", no changes since the last snapshot")
                    if (outcome.result.failures.isNotEmpty()) append(". Failed: ${outcome.result.failures.keys.joinToString()}")
                    append('.')
                }
            } catch (e: Exception) {
                "The check failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _state.update { it.copy(checking = false, message = msg) }
        }
    }

    companion object {
        val INTERVALS: List<Int> = WatchPolicy.INTERVALS_HOURS
    }
}
