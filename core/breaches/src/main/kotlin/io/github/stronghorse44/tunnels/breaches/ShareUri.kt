package io.github.stronghorse44.tunnels.breaches

/**
 * The only address the share provider serves: `content://<authority>/catalogue/<token>` with a 128-bit hex token
 * (specs/B11-linx.md section 10.4). The checks are plain Kotlin so they can be tested without Android; the provider
 * hands it the pieces of the Uri.
 */
object ShareUri {
    const val SEGMENT = "catalogue"

    /** The manifest's `${applicationId}.breaches`: the release build's is io.github.stronghorse44.tunnels.breaches (frozen, section 10.4). */
    fun authorityFor(packageName: String): String = "$packageName.breaches"

    /** The token of `content://<authority>/catalogue/<token>`, or null for any other address. */
    fun tokenOf(
        expectedAuthority: String,
        scheme: String?,
        authority: String?,
        pathSegments: List<String>,
        hasQueryOrFragment: Boolean,
    ): String? {
        if (scheme != "content" || authority != expectedAuthority || hasQueryOrFragment) return null
        if (pathSegments.size != 2 || pathSegments[0] != SEGMENT) return null
        return pathSegments[1].takeIf { BreachHolder.isToken(it) }
    }

    /** `breaches-<yyyyMMdd>.txt` for a `fetched` time like 2026-10-05T23:00:00Z. */
    fun displayName(fetched: String): String = "breaches-" + fetched.take(10).replace("-", "") + ".txt"
}
