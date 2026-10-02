package io.github.stronghorse44.tunnels.devicecheck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DeviceChecksState(
    val running: Boolean = false,
    val groups: List<CheckGroup> = emptyList(),
    val ranAt: Long = 0L,
    val error: String? = null,
)

class DeviceChecksViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(DeviceChecksState())
    val state: StateFlow<DeviceChecksState> = _state.asStateFlow()

    /** Runs every check again; a run already under way is left to finish. */
    fun run() {
        if (_state.value.running) return
        _state.update { it.copy(running = true, error = null) }
        viewModelScope.launch {
            try {
                val groups = withContext(Dispatchers.IO) { DeviceCheckRunner.run(app) }
                _state.update { it.copy(running = false, groups = groups, ranAt = System.currentTimeMillis()) }
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
