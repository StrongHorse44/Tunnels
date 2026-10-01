package io.github.stronghorse44.tunnels.certs

/** A certificate authority the major root programs removed for cause. */
data class DistrustedRoot(
    val name: String,
    /** One line a non-expert can read. */
    val reason: String,
    /** Subject organization prefixes (case-insensitive). */
    val organizations: List<String> = emptyList(),
    /** Exact subject common names (case-insensitive). */
    val commonNames: List<String> = emptyList(),
    /** SHA-256 fingerprints, uppercase hex without colons. Empty unless known with certainty. */
    val fingerprints: Set<String> = emptySet(),
)

/**
 * Well-known distrusted or compromised root CAs. Deliberately short: every entry here is a CA that
 * Mozilla, Google and Apple all removed, so a match is a real problem and not a difference of opinion.
 */
object DistrustedRoots {
    val entries: List<DistrustedRoot> = listOf(
        DistrustedRoot(
            name = "DigiNotar",
            reason = "DigiNotar was breached in 2011 and issued fraudulent certificates for Google and others; every root program removed it",
            organizations = listOf("DigiNotar"),
            commonNames = listOf("DigiNotar Root CA", "DigiNotar Root CA G2", "DigiNotar PKIoverheid CA Overheid en Bedrijven"),
        ),
        DistrustedRoot(
            name = "WoSign",
            reason = "WoSign back-dated SHA-1 certificates and hid its ownership of StartCom; browsers removed it in 2016 and 2017",
            organizations = listOf("WoSign CA Limited"),
            commonNames = listOf("Certification Authority of WoSign", "Certification Authority of WoSign G2", "CA WoSign ECC Root", "CA 沃通根证书"),
        ),
        DistrustedRoot(
            name = "StartCom",
            reason = "StartCom was secretly bought by WoSign and distrusted with it in 2016 and 2017",
            organizations = listOf("StartCom Ltd."),
            commonNames = listOf("StartCom Certification Authority", "StartCom Certification Authority G2"),
        ),
        DistrustedRoot(
            name = "CNNIC",
            reason = "CNNIC let an intermediate issue certificates for Google domains in 2015; Google and Mozilla removed its roots",
            organizations = listOf("CNNIC", "China Internet Network Information Center"),
            commonNames = listOf("CNNIC ROOT", "China Internet Network Information Center EV Certificates Root"),
        ),
        DistrustedRoot(
            name = "TrustCor",
            reason = "TrustCor shared ownership with a spyware vendor; Mozilla, Google and Apple removed it in 2022 and 2023",
            organizations = listOf("TrustCor Systems"),
            commonNames = listOf("TrustCor RootCert CA-1", "TrustCor RootCert CA-2", "TrustCor ECA-1"),
        ),
    )

    fun match(summary: CertSummary): DistrustedRoot? = match(summary.fingerprint, summary.subjectCn, summary.subjectO)

    /** The entry [fingerprint], [subjectCn] or [subjectO] belongs to, or null. Any argument may be null. */
    fun match(fingerprint: String?, subjectCn: String?, subjectO: String?): DistrustedRoot? {
        val fp = fingerprint?.replace(":", "")?.uppercase()
        val cn = subjectCn?.trim()
        val org = subjectO?.trim()?.lowercase()
        return entries.firstOrNull { entry ->
            (fp != null && fp in entry.fingerprints) ||
                (cn != null && entry.commonNames.any { it.equals(cn, ignoreCase = true) }) ||
                (org != null && entry.organizations.any { org.startsWith(it.lowercase()) })
        }
    }
}
