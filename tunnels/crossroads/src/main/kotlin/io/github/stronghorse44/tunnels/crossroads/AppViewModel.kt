package io.github.stronghorse44.tunnels.crossroads

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stronghorse44.tunnels.crossrules.AppDossier
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.runtime.ActionRunner
import io.github.stronghorse44.tunnels.runtime.SnapshotEngine.Companion.toModel
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppState(
    val loaded: Boolean = false,
    val packageName: String = "",
    val dossier: AppDossier? = null,
    /** This app's open findings from every tunnel, most severe first. */
    val findings: List<Finding> = emptyList(),
    val installed: Boolean = true,
    val system: Boolean = false,
    val message: String? = null,
)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private var runtime: TunnelsRuntime? = null

    fun open(pkg: String) {
        if (_state.value.packageName == pkg) return
        _state.update { AppState(packageName = pkg) }
        viewModelScope.launch {
            val rt = TunnelsRuntime.get(app).also { runtime = it }
            val (installed, system) = withContext(Dispatchers.IO) { installState(pkg) }
            val dossier = withContext(Dispatchers.IO) {
                val dao = rt.store.dao
                val byTunnel = AppDossier.SOURCES.keys.associateWith { id ->
                    dao.latestSnapshotIdFor(id)?.let { sid -> dao.observations(sid, id).filter { it.subject == pkg }.map { it.toModel() } }.orEmpty()
                }
                AppDossier.of(pkg, byTunnel)
            }
            _state.update { it.copy(loaded = true, dossier = dossier, installed = installed, system = system) }
            rt.store.dao.allFindingsFlow()
                .map { rows ->
                    rows.filter { it.subject == pkg }
                        .map { r -> r.toModel(rt.registry[r.tunnelId]) }
                        .sortedWith(compareByDescending<Finding> { it.severity }.thenBy { it.kind })
                }
                .flowOn(Dispatchers.IO)
                .collect { list -> _state.update { it.copy(findings = list) } }
        }
    }

    fun run(action: FindingAction) {
        viewModelScope.launch {
            val message = ActionRunner.run(app, action)
            _state.update { it.copy(message = message) }
        }
    }

    fun dismiss(finding: Finding) {
        val rt = runtime ?: return
        viewModelScope.launch(Dispatchers.IO) { rt.store.dao.dismissFinding(finding.id) }
    }

    /** Installed at all, and whether it came with the OS (no Uninstall then). */
    private fun installState(pkg: String): Pair<Boolean, Boolean> = try {
        val info = app.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        true to ((info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0)
    } catch (_: PackageManager.NameNotFoundException) {
        false to false
    }
}
