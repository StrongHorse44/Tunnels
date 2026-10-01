package io.github.stronghorse44.tunnels.permrules

import io.github.stronghorse44.tunnels.model.Observation

/** One app's permission picture, read back from its observations. */
data class AppPermissionState(
    val packageName: String,
    val label: String?,
    val isSystem: Boolean,
    val grantedPermissions: List<String>,
    val networkOn: Boolean,
    val accessibilityEnabled: Boolean,
) {
    /** Sensitive groups the app currently holds, including an enabled accessibility service. */
    val grantedGroups: Set<PermissionGroup> = buildSet {
        grantedPermissions.map(PermissionCatalog::groupOf).filter { it.isSensitive }.forEach { add(it) }
        if (accessibilityEnabled) add(PermissionGroup.ACCESSIBILITY)
    }

    fun has(group: PermissionGroup) = group in grantedGroups

    val displayName: String get() = label?.takeIf { it.isNotBlank() } ?: packageName

    companion object {
        fun of(packageName: String, observations: List<Observation>): AppPermissionState {
            var label: String? = null
            var system = false
            var network = false
            var accessibility = false
            val granted = mutableListOf<String>()
            for (o in observations) {
                when {
                    o.key == PermissionKeys.APP_LABEL -> label = o.value
                    o.key == PermissionKeys.APP_SYSTEM -> system = o.value == "true"
                    o.key == PermissionKeys.TOGGLE_NETWORK -> network = o.value == PermissionKeys.ON
                    o.key == PermissionKeys.ACCESS_ACCESSIBILITY -> accessibility = o.value == PermissionKeys.ENABLED
                    PermissionKeys.isPermKey(o.key) && o.value == PermissionKeys.GRANTED -> granted += PermissionKeys.permissionOf(o.key)
                }
            }
            return AppPermissionState(packageName, label, system, granted, network, accessibility)
        }

        fun all(observations: List<Observation>): Map<String, AppPermissionState> =
            observations.groupBy { it.subject }.mapValues { (pkg, obs) -> of(pkg, obs) }
    }
}
