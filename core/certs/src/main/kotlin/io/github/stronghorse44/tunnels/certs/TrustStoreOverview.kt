package io.github.stronghorse44.tunnels.certs

import io.github.stronghorse44.tunnels.certs.TrustStoreKeys.value
import io.github.stronghorse44.tunnels.model.Observation

/** One certificate as the panel shows it. */
data class CaRow(
    val alias: String,
    val source: String,
    val subject: String,
    val org: String,
    val shortFingerprint: String,
    val expires: String,
    val keyAlgo: String,
    val distrusted: DistrustedRoot?,
) {
    val isUser: Boolean get() = source == TrustStoreKeys.SOURCE_USER
}

/** What the trust store panel needs, derived from a scan's observations (pure, so it is unit-tested). */
data class TrustStoreOverview(
    val systemCount: Int,
    val userCount: Int,
    val unreadableCount: Int,
    val skippedCount: Int,
    val userCas: List<CaRow>,
    val distrusted: List<CaRow>,
) {
    val isEmpty: Boolean get() = systemCount == 0 && userCount == 0 && userCas.isEmpty()

    companion object {
        fun from(observations: List<Observation>): TrustStoreOverview {
            val bySubject = observations.groupBy { it.subject }
            val summary = bySubject[TrustStoreKeys.SUMMARY].orEmpty()
            val rows = bySubject.filterKeys { it != TrustStoreKeys.SUMMARY }.map { (alias, obs) -> row(alias, obs) }
                .sortedWith(compareBy({ !it.isUser }, { it.subject.lowercase() }))
            return TrustStoreOverview(
                systemCount = summary.value(TrustStoreKeys.SYSTEM_COUNT)?.toIntOrNull() ?: rows.count { !it.isUser },
                userCount = summary.value(TrustStoreKeys.USER_COUNT)?.toIntOrNull() ?: rows.count { it.isUser },
                unreadableCount = summary.value(TrustStoreKeys.UNREADABLE_COUNT)?.toIntOrNull() ?: 0,
                skippedCount = summary.value(TrustStoreKeys.SKIPPED_COUNT)?.toIntOrNull() ?: 0,
                userCas = rows.filter { it.isUser },
                distrusted = rows.filter { it.distrusted != null },
            )
        }

        private fun row(alias: String, obs: List<Observation>): CaRow {
            val fingerprint = obs.value(TrustStoreKeys.FINGERPRINT)
            val subject = obs.value(TrustStoreKeys.SUBJECT).orEmpty()
            val org = obs.value(TrustStoreKeys.ORG).orEmpty()
            return CaRow(
                alias = alias,
                source = obs.value(TrustStoreKeys.SOURCE) ?: TrustStoreKeys.sourceOf(alias) ?: TrustStoreKeys.SOURCE_SYSTEM,
                subject = subject.ifBlank { "unnamed certificate" },
                org = org,
                shortFingerprint = fingerprint?.let(CertSummary::shortForm) ?: "",
                expires = obs.value(TrustStoreKeys.EXPIRES).orEmpty(),
                keyAlgo = obs.value(TrustStoreKeys.KEY_ALGO).orEmpty(),
                distrusted = DistrustedRoots.match(fingerprint, subject, org),
            )
        }
    }
}
