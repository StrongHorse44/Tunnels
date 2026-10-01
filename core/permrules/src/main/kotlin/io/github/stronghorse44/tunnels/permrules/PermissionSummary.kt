package io.github.stronghorse44.tunnels.permrules

import io.github.stronghorse44.tunnels.model.Observation

/** Headline numbers for the permissions tunnel panel, computed from one scan's observations. */
data class PermissionSummary(
    val apps: Int,
    val userApps: Int,
    /** Apps holding each sensitive group, most apps first; groups nobody holds are left out. */
    val perGroup: List<Pair<PermissionGroup, Int>>,
    val networkOn: Int,
    val networkOff: Int,
    val networkNotRequested: Int,
    val sensorsOff: Int,
    /** False when the Sensors toggle is not visible on this device (every app reported "unknown"). */
    val sensorsVisible: Boolean,
) {
    companion object {
        val EMPTY = PermissionSummary(0, 0, emptyList(), 0, 0, 0, 0, false)

        fun of(observations: List<Observation>): PermissionSummary {
            if (observations.isEmpty()) return EMPTY
            val states = AppPermissionState.all(observations)
            val counts = HashMap<PermissionGroup, Int>()
            for (app in states.values) for (g in app.grantedGroups) counts[g] = (counts[g] ?: 0) + 1
            var on = 0
            var off = 0
            var na = 0
            var sensorsOff = 0
            var sensorsVisible = false
            for (o in observations) {
                when (o.key) {
                    PermissionKeys.TOGGLE_NETWORK -> when (o.value) {
                        PermissionKeys.ON -> on++
                        PermissionKeys.OFF -> off++
                        else -> na++
                    }
                    PermissionKeys.TOGGLE_SENSORS -> when (o.value) {
                        PermissionKeys.OFF -> { sensorsOff++; sensorsVisible = true }
                        PermissionKeys.ON -> sensorsVisible = true
                    }
                }
            }
            return PermissionSummary(
                apps = states.size,
                userApps = states.values.count { !it.isSystem },
                perGroup = counts.entries.sortedWith(compareByDescending<Map.Entry<PermissionGroup, Int>> { it.value }.thenByDescending { it.key.weight }).map { it.key to it.value },
                networkOn = on,
                networkOff = off,
                networkNotRequested = na,
                sensorsOff = sensorsOff,
                sensorsVisible = sensorsVisible,
            )
        }
    }
}
