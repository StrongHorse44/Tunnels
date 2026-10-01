package io.github.stronghorse44.tunnels.install

enum class PackageShape { APK, BUNDLE, NOT_AN_APP }

object Bundles {
    private val abiTokens = mapOf(
        "arm64_v8a" to "arm64-v8a",
        "armeabi_v7a" to "armeabi-v7a",
        "armeabi" to "armeabi",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    /** Classifies a zip by its entry names: a single APK, a split bundle (.apks/.xapk/.apkm), or neither. */
    fun shape(entryNames: List<String>): PackageShape = when {
        "AndroidManifest.xml" in entryNames -> PackageShape.APK
        entryNames.any { it.endsWith(".apk", ignoreCase = true) && !it.endsWith("/") } -> PackageShape.BUNDLE
        else -> PackageShape.NOT_AN_APP
    }

    /**
     * Picks the APKs from a bundle to install: every APK except ABI config splits for processors the
     * device does not support. Density and language splits are all kept (harmless, a little larger).
     * Universal/standalone APKs are dropped when split APKs are present.
     */
    fun selectSplits(apkPaths: List<String>, supportedAbis: List<String>): List<String> {
        val apks = apkPaths.filter { it.endsWith(".apk", ignoreCase = true) }
        val splits = apks.filterNot { isStandalone(it) }
        val pool = splits.ifEmpty { apks }
        return pool.filter { path ->
            val abi = abiOf(path)
            abi == null || abi in supportedAbis
        }
    }

    fun abiOf(path: String): String? {
        val name = path.substringAfterLast('/').lowercase()
        // Longest token first so x86_64 is not read as x86.
        return abiTokens.entries.sortedByDescending { it.key.length }
            .firstOrNull { (token, _) -> name.contains(token) }?.value
    }

    private fun isStandalone(path: String): Boolean {
        val lower = path.lowercase()
        return lower.startsWith("standalones/") || lower.contains("universal")
    }
}
