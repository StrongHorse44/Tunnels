package io.github.stronghorse44.tunnels.certs

import io.github.stronghorse44.tunnels.model.Observation

/**
 * Observation schema of the trust store tunnel. One subject per `AndroidCAStore` alias
 * (`system:<hash>.<n>` or `user:<hash>.<n>`) plus a [SUMMARY] subject with counts.
 */
object TrustStoreKeys {
    const val TUNNEL_ID = "trust_store"
    const val SUMMARY = "summary"

    const val SOURCE = "ca:source"
    const val FINGERPRINT = "ca:fingerprint"
    const val SUBJECT = "ca:subject"
    const val ORG = "ca:org"
    const val ISSUER = "ca:issuer"
    const val EXPIRES = "ca:expires"
    const val KEY_ALGO = "ca:keyAlgo"
    const val SELF_SIGNED = "ca:selfSigned"

    const val SYSTEM_COUNT = "ca:systemCount"
    const val USER_COUNT = "ca:userCount"
    const val UNREADABLE_COUNT = "ca:unreadableCount"
    const val SKIPPED_COUNT = "ca:skippedCount"

    const val SOURCE_SYSTEM = "system"
    const val SOURCE_USER = "user"

    /** Keys every certificate subject carries. */
    val certificateKeys: Set<String> = setOf(SOURCE, FINGERPRINT, SUBJECT, ORG, ISSUER, EXPIRES, KEY_ALGO, SELF_SIGNED)

    /** Keys the [SUMMARY] subject carries. */
    val summaryKeys: Set<String> = setOf(SYSTEM_COUNT, USER_COUNT, UNREADABLE_COUNT, SKIPPED_COUNT)

    /** `system` or `user` from an alias prefix; null for anything else. */
    fun sourceOf(alias: String): String? = when {
        alias.startsWith("$SOURCE_SYSTEM:") -> SOURCE_SYSTEM
        alias.startsWith("$SOURCE_USER:") -> SOURCE_USER
        else -> null
    }

    fun isUserAlias(alias: String) = sourceOf(alias) == SOURCE_USER

    /** The observations for one certificate; [alias] becomes the subject. */
    fun observations(alias: String, summary: CertSummary): List<Observation> {
        val source = sourceOf(alias) ?: SOURCE_SYSTEM
        fun obs(key: String, value: String) = Observation(TUNNEL_ID, alias, key, value)
        return listOf(
            obs(SOURCE, source),
            obs(FINGERPRINT, summary.fingerprint),
            obs(SUBJECT, summary.displayName),
            obs(ORG, summary.subjectO ?: ""),
            obs(ISSUER, summary.issuerCn ?: ""),
            obs(EXPIRES, summary.notAfter),
            obs(KEY_ALGO, summary.keyDescription),
            obs(SELF_SIGNED, summary.selfSigned.toString()),
        )
    }

    fun summaryObservations(systemCount: Int, userCount: Int, unreadable: Int = 0, skipped: Int = 0): List<Observation> = listOf(
        Observation(TUNNEL_ID, SUMMARY, SYSTEM_COUNT, systemCount.toString()),
        Observation(TUNNEL_ID, SUMMARY, USER_COUNT, userCount.toString()),
        Observation(TUNNEL_ID, SUMMARY, UNREADABLE_COUNT, unreadable.toString()),
        Observation(TUNNEL_ID, SUMMARY, SKIPPED_COUNT, skipped.toString()),
    )

    /** Value of [key] among one subject's observations. */
    fun List<Observation>.value(key: String): String? = firstOrNull { it.key == key }?.value
}
