package io.github.stronghorse44.tunnels.trackers

import io.github.stronghorse44.tunnels.model.Observation

/** Observation key schema of the apk_excavation tunnel. Subject is always the package name. */
object ApkKeys {
    const val TUNNEL_ID = "apk_excavation"

    const val LABEL = "app:label"
    /** "true" for system (preinstalled) apps, "false" otherwise. */
    const val SYSTEM = "app:system"
    /** "versionName (versionCode)". */
    const val VERSION = "version"
    /** `sdk:<trackerId>` = categories joined by comma. */
    const val SDK_PREFIX = "sdk:"
    const val SDK_COUNT = "sdk:count"
    /** Present only when some dex could not be read; value says why (e.g. "dex too large"). */
    const val SDK_SKIPPED = "sdk:skipped"
    /** Uppercase hex SHA-256 of the first current signer. */
    const val CERT_SHA256 = "cert:sha256"
    const val CERT_COUNT = "cert:count"
    /** Number of earlier certificates in the signing lineage (0 when the key never rotated). */
    const val CERT_LINEAGE = "cert:lineage"
    /**
     * Uppercase hex SHA-256 of each earlier certificate in the signing lineage, comma-separated, oldest first.
     * Present only when the key rotated. Android verified each hand-over when it installed the update.
     */
    const val CERT_HISTORY = "cert:history"
    /** Installing package name or "unknown". */
    const val INSTALLER = "installer"
    const val TARGET_SDK = "targetSdk"
    const val MIN_SDK = "minSdk"
    /** Comma list of lib/<abi> directories, or "none". */
    const val NATIVE_ABIS = "native:abis"
    const val NATIVE_LIBS = "native:libs"
    const val SIZE_MB = "size:mb"
    /** "true" when the app is built debuggable; absent otherwise. */
    const val DEBUGGABLE = "app:debuggable"
    /** "true" when the manifest lets the app use unencrypted HTTP (usesCleartextTraffic), "false" otherwise. */
    const val CLEARTEXT = "net:cleartext"
    /** Exported components no permission guards, [ExportedCounts.encode]d. User apps only. */
    const val EXPORTED_OPEN = "exported:open"
    /** Up to [OPEN_PROVIDERS_MAX] authorities of exported providers no permission guards, comma-separated. */
    const val OPEN_PROVIDERS = "exported:providers"
    const val OPEN_PROVIDERS_MAX = 3

    const val UNKNOWN_INSTALLER = "unknown"
    const val NO_ABIS = "none"

    val ABIS_64 = setOf("arm64-v8a", "x86_64", "riscv64")

    fun sdkKey(trackerId: String) = SDK_PREFIX + trackerId

    /** True for `sdk:<trackerId>` keys, false for the count and skipped markers. */
    fun isTrackerKey(key: String) = key.startsWith(SDK_PREFIX) && key != SDK_COUNT && key != SDK_SKIPPED

    fun trackerId(key: String): String? = if (isTrackerKey(key)) key.removePrefix(SDK_PREFIX) else null

    fun categoriesOf(value: String): Set<TrackerCategory> =
        value.split(',').mapNotNull { TrackerCategory.byLabel(it.trim()) }.toSet()

    fun categoriesValue(categories: Set<TrackerCategory>): String =
        TrackerCategory.entries.filter { it in categories }.joinToString(",") { it.label }

    fun isSystem(obs: List<Observation>) = obs.firstOrNull { it.key == SYSTEM }?.value == "true"

    fun value(obs: List<Observation>, key: String): String? = obs.firstOrNull { it.key == key }?.value

    fun list(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** "AB:CD:EF:01…" for a hex fingerprint. */
    fun shortFingerprint(hex: String): String {
        val clean = hex.trim()
        if (clean.length < 8) return clean
        return clean.chunked(2).take(4).joinToString(":") + "…"
    }

    /** Whether a `native:abis` value has native code but no 64-bit build of it. */
    fun is32BitOnly(abis: String): Boolean {
        if (abis.isBlank() || abis == NO_ABIS) return false
        val list = abis.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return list.isNotEmpty() && list.none { it in ABIS_64 }
    }
}

/**
 * Components another app can start or query without holding any permission. A launcher activity is always among the
 * activities; providers are the ones that matter, since an unguarded provider can hand its data to any app.
 */
data class ExportedCounts(val activities: Int, val services: Int, val receivers: Int, val providers: Int) {
    fun encode(): String = "activities=$activities,services=$services,receivers=$receivers,providers=$providers"

    companion object {
        fun parse(value: String?): ExportedCounts? {
            if (value.isNullOrBlank()) return null
            val f = value.split(',').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim().toIntOrNull()
            }.toMap()
            return ExportedCounts(
                f["activities"] ?: return null,
                f["services"] ?: return null,
                f["receivers"] ?: return null,
                f["providers"] ?: return null,
            )
        }
    }
}
