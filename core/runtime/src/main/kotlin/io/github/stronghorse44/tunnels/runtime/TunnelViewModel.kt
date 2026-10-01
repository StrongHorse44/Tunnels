package io.github.stronghorse44.tunnels.runtime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.SnapshotEngine.Companion.toModel
import io.github.stronghorse44.tunnels.store.ObservationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class TunnelScreenState(
    val module: TunnelModule? = null,
    val findings: List<Finding> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val lastScan: Instant? = null,
    val scan: ScanState = ScanState(),
    val lastResult: ScanResult? = null,
    val message: String? = null,
    val error: String? = null,
)

/** What a custom [TunnelUi] may ask the screen to do. */
interface TunnelScreenActions {
    fun scan()
    fun perform(action: FindingAction)
    fun dismiss(finding: Finding)
}

class TunnelViewModel(private val app: Application) : AndroidViewModel(app), TunnelScreenActions {
    private val _state = MutableStateFlow(TunnelScreenState())
    val state: StateFlow<TunnelScreenState> = _state.asStateFlow()
    private var runtime: TunnelsRuntime? = null
    private var tunnelId: String? = null

    fun open(id: String) {
        if (tunnelId == id) return
        tunnelId = id
        viewModelScope.launch {
            val rt = TunnelsRuntime.get(app).also { runtime = it }
            val module = rt.registry[id]
            if (module == null) {
                _state.update { it.copy(error = "No tunnel registered as \"$id\".") }
                return@launch
            }
            _state.update { it.copy(module = module) }
            launch { rt.engine.state.collect { s -> _state.update { it.copy(scan = s) } } }
            launch {
                rt.store.dao.findingsFlow(id).collect { rows -> _state.update { it.copy(findings = rows.map { r -> r.toModel(module) }) } }
            }
            reloadObservations()
        }
    }

    private suspend fun reloadObservations() {
        val rt = runtime ?: return
        val id = tunnelId ?: return
        withContext(Dispatchers.IO) {
            val snapshotId = rt.store.dao.latestSnapshotIdFor(id)
            val obs = snapshotId?.let { rt.store.dao.observations(it, id) }.orEmpty().map(ObservationEntity::toModel)
            val at = snapshotId?.let { rt.store.dao.snapshot(it)?.takenAt }?.let(Instant::ofEpochMilli)
            _state.update { it.copy(observations = obs, lastScan = at) }
        }
    }

    override fun scan() {
        val rt = runtime ?: return
        val id = tunnelId ?: return
        viewModelScope.launch {
            _state.update { it.copy(error = null, message = null) }
            val result = runCatching { rt.engine.scan(listOf(id)) }.getOrElse { e ->
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                return@launch
            }
            val failure = result.failures[id]
            _state.update { it.copy(lastResult = result, error = failure) }
            reloadObservations()
        }
    }

    override fun perform(action: FindingAction) {
        viewModelScope.launch {
            val msg = runCatching { ActionRunner.run(app, action) }.getOrElse { it.message ?: "Failed" }
            if (msg != null) _state.update { it.copy(message = msg) }
            if (action is FindingAction.Perform) scan()
        }
    }

    override fun dismiss(finding: Finding) {
        val rt = runtime ?: return
        viewModelScope.launch(Dispatchers.IO) { rt.store.dao.dismissFinding(finding.id) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
