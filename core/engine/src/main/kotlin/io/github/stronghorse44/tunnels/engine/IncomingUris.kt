package io.github.stronghorse44.tunnels.engine

/**
 * Which addresses an incoming VIEW or SEND may hand Tunnels' file screens (audit L1). Only `content://`: an explicit intent
 * bypasses the manifest's `scheme="content"` filter, and a `file://` path would make Tunnels copy whatever its own uid can
 * read. Never one of Tunnels' own providers, whose authorities all start with the package name (`<package>.breaches`,
 * `<package>.shizuku`), so another app cannot route Tunnels' own content back into it. Derived from the package name, so
 * the debug and release packages both work.
 */
object IncomingUris {
    fun accepts(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return false
        val host = authority?.substringAfterLast('@')?.substringBefore(':')?.trim()?.lowercase()
        if (host.isNullOrEmpty()) return false
        val own = ownPackage.lowercase()
        return host != own && !host.startsWith("$own.")
    }
}
